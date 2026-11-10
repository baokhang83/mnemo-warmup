# ADR 0001: Archive store backend

Status: accepted, revised after review (2026-11-10) · Resolves issue #11 · Unblocks #12, #13, #15

## Context

Each fingerprint (JDK build + classpath + JVM flags) has one AppCDS or AOT archive, trained once
and fetched by every pod's init container. Archives are hundreds of MB. The store must support:
upload from the trainer, lookup by fingerprint, integrity verification, and fast cold fetch.
Constitution §1 applies: an unreachable store or missing archive means a plain JVM start, never a
failed pod.

## Options

| | OCI registry | S3 / GCS | RWX PVC |
|---|---|---|---|
| Auth | Same registry, but pods need their own credentials (see Decision) | Separate IAM / workload identity | Kubernetes volume mount, no network auth |
| Integrity | Content digest (SHA-256) built in | We add a checksum ourselves | We add a checksum ourselves |
| Large blobs | Good; some registries cap blob size | Native | Native |
| Retention | Registry GC may drop untagged blobs | Lifecycle rules | Manual |
| Availability | Every cluster that runs images | Needs a bucket per environment | Needs an RWX storage class; often missing |
| Client code | OCI client needed | Simple SDK | Plain file I/O |

## Decision

Implement the **OCI registry** backend first. Store each archive as an OCI artifact and verify
its content digest before `fetch` writes the archive.

**Tag encoding.** `ArchiveManifest.fingerprint()` returns `sha256:<hex>`, and a colon is not valid
in an OCI tag (a colon form would be read as a digest reference). The tag is `fp-<hex>`: the
`sha256:` prefix is replaced by `fp-`. It is 67 characters, within the 128-character tag limit.
The mapping is reversible (`fp-` to `sha256:`) and is implemented once in the store, shared by
`put`, `get` and `exists`. The tag is a lookup name only; integrity comes from the artifact's
own content digest, which is a different value.

**Credentials.** Kubernetes passes `imagePullSecrets` to the kubelet for image pulls. It does not
give them to the init container or the trainer, so in-pod clients need their own credentials.
- `fetch` (init container) needs **pull** scope. The trainer needs **pull and push** scope on the
  archive repository. Use separate credentials: the read-only one must not be able to push.
- Delivery: a Docker-config style Secret mounted into the container, named by chart values
  (`store.pullSecret` for fetch, `store.pushSecret` for the trainer). A user may point
  `pullSecret` at the same content as their image pull secret if its scope is sufficient. The
  chart never copies it implicitly.
- Clusters that use a node credential provider (cloud registries such as ECR, GCR, ACR) have no
  mountable secret. There the pod uses workload identity (for example IRSA or GKE Workload
  Identity) through the registry's standard token flow, or an explicit Secret.
- If credentials are missing or rejected, `fetch` logs the reason and the pod starts uncached
  (§1). The trainer logs it and exits 0 (ADR 0002).

Hide the store behind a small interface (`put`, `get`, `exists` by fingerprint) so S3/GCS can be
added later without changes to `fetch` or `run`. RWX PVC is not planned.

## Consequences

- Adopters need no new infrastructure beyond their image registry, but they must deliver pull and
  push credentials to the pods (see Decision). This is the main adoption cost of OCI.
- We take on an OCI client dependency (library or minimal HTTP implementation: tracked in #15).
- Registries with low blob-size limits or aggressive garbage collection of untagged artifacts
  need documented settings. The trainer must tag every upload so GC does not remove it.
- Every failure (auth, network, digest mismatch, missing tag) maps to "no archive": `fetch` logs
  the reason and the pod starts uncached.
