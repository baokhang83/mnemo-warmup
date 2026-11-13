# Keeping archive storage bounded

mnemo-warmup stores one archive per fingerprint in an OCI registry and never deletes anything. This guide explains how the store grows, what deleting an archive costs, and how to bound growth with your registry's own lifecycle policy. mnemo-warmup adds no delete command and needs no delete permission for any of this: the registry applies the policy itself, with its own authority.

## How the store grows

- Each fingerprint is one tag, `fp-<64 hex characters>`, on a manifest with two blobs: a small config blob (the manifest JSON) and the archive itself, typically hundreds of MB ([ADR 0001](adr/0001-archive-store.md)).
- The fingerprint covers the JDK build, the classpath, the JVM flags, the cache type and the image digest when one is given. A new image build, a JDK patch release or a changed fingerprinted JVM flag can produce a new fingerprint and a new archive. `SoftMaxHeapSize` is excluded; see [archive compatibility](archive-compatibility.md). Old fingerprints are never reused or overwritten by newer ones.
- Growth is roughly *archives per release x number of releases x archive size*. For illustration: 300 MB per archive, one archive per release (AppCDS), 20 releases a month is about 6 GB a month. Measure your own archive size with `mnemo-warmup build` before deciding on a budget.

## What deleting an archive costs

Deleting an archive costs a cache miss, not an outage ([constitution](fluencyloop/constitution.md) §1 and §3):

1. A pod whose fingerprint is gone gets "no archive" from `fetch` and starts the application with an ordinary JVM start, as if it had never been trained.
2. The trainer finds the tag missing on its next scheduled run, trains again and pushes the archive back, so later pods are cached again.

So the cost of a wrong deletion is slower starts for every pod that starts between the deletion and the next successful training run, plus one training run. It is worth avoiding for a version that is running or that you may roll back to, but nothing fails.

## What to keep in mind before you delete

- **Scope the policy to the archive repository.** Use a repository that holds only mnemo-warmup archives, and match only `fp-` tags. Never apply a rule to a repository that also holds application images.
- **Do not add a rule for untagged images.** The trainer uploads blobs before it creates the tag. Leave removal of unreferenced blobs to the registry's own garbage collection, which is not something this guide has verified for any registry.
- **Tags are mutable by design.** The tag name is derived from the content's fingerprint, so a tag normally always points at the same archive. The trainer pushes a tag again only to repair an incomplete entry. With a registry setting that makes tags immutable, that repair push is not covered by any test here.
- **Shared consumers.** Fingerprints do not record which application or environment created them. If several applications, clusters or environments share one repository, a busy application's frequent builds can push other applications' archives out of a "newest N" window. Use one repository per application or team, or size N for the busiest consumer.
- **Active deployments and rollbacks.** A lifecycle rule cannot see which fingerprints are running or are rollback targets. A rule that keeps the newest N archives by push time only protects the most recent builds. A release that has been running for months can fall outside the window, and so can the version you roll back to after a series of bad releases.
- **Age-based expiry does not protect active versions.** An expiry-after-N-days rule (a TTL) deletes archives by how long ago they were pushed. It does not know whether the version is still deployed, so a stable release that was not rebuilt for N days loses its archive while it keeps running. Do not read a TTL as "unused for N days". Use a count-based rule, or set the TTL far longer than your oldest running release.
- **Ongoing uploads.** The newest archive is never among the oldest N, so a count-based rule does not select an upload that has just completed. Registries evaluate lifecycle rules on their own schedule, so do not rely on an exact moment.
- **Choose N from your deployments.** N should be at least the number of fingerprints that can be live at once (versions and cache types in flight across all environments sharing the repository) plus the rollback depth you want. The example below uses 30 as a placeholder, not a recommendation for your workload.

## Example: Amazon ECR

[`examples/ecr-lifecycle-policy.json`](examples/ecr-lifecycle-policy.json) keeps the newest 30 images in the repository whose tag matches `fp-*` and expires older ones:

```json
{
  "rules": [
    {
      "rulePriority": 1,
      "description": "Keep the newest 30 mnemo-warmup archives (tags fp-*); expire older ones",
      "selection": {
        "tagStatus": "tagged",
        "tagPatternList": ["fp-*"],
        "countType": "imageCountMoreThan",
        "countNumber": 30
      },
      "action": { "type": "expire" }
    }
  ]
}
```

What the AWS documentation confirms about this policy:

- The structure above (`rulePriority`, `selection`, `action`) and its fields. `tagPatternList` is the documented way to select tagged images, and `countUnit` must be omitted for `imageCountMoreThan`.
- `imageCountMoreThan` sorts images from youngest to oldest by push time and expires the ones beyond the count, always older before newer.
- Expiry happens within 24 hours after the criteria are met.
- A lifecycle policy applies to one repository in one Region.
- Actions are recorded in CloudTrail.

Apply it only after you have looked at what it would do:

1. Open the repository in the ECR console, choose lifecycle policy, paste the JSON and use **Save and run test**. The test lists the images the policy would expire without deleting anything.
2. Check that the listed archives are ones you can afford to lose, and that nothing outside `fp-` tags appears.
3. Apply it, either from the console or with `aws ecr put-lifecycle-policy --repository-name <name> --lifecycle-policy-text file://ecr-lifecycle-policy.json`.

Limitations that the AWS documentation does not settle, and that have not been tested with mnemo-warmup archives:

- Whether lifecycle rules treat these OCI artifacts (custom `artifactType` and config media type) the same as container images. The documentation speaks of "images" throughout.
- Whether pushing a tag again changes the push time used for ordering.
- Behaviour with immutable tags.
- That `sinceImagePulled` rules would count a `fetch` as a pull. Do not use a pull-based rule to protect active archives until you have checked it on your registry.
- The maximum number of rules and the policy size limit.

The Region matters: the policy only acts on the repository in the Region where you created it.

## Other registries

Most registries have a similar feature (Harbor retention rules, Google Artifact Registry cleanup policies, Azure Container Registry retention). Apply the same rules: a dedicated repository, match only `fp-` tags, count-based and conservative, preview before enabling, and check how the feature treats OCI artifacts and tag updates. This guide does not provide or verify policies for them.

## What this release does not do

- No automatic deletion and no `prune` command.
- No delete permission is granted to the fetch or trainer roles. The [ECR roles](aws-workload-identity.md) deliberately omit `ecr:BatchDeleteImage`.
- No tracking of which fingerprints are active, so retention cannot protect them for you.
