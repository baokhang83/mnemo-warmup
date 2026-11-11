# Operating mnemo-warmup

What it costs, how to check that it works, what can and cannot stop a pod, and how to bound storage and remove it. Setup is in the [README](../README.md) and [configuration](configuration.md).

## What it costs

- **Training runs a real instance of your application**, once per new fingerprint (for Spring Boot until the context has refreshed). Whatever startup does (connections, migrations, background jobs, calls to other services) also happens in the trainer pod. The trainer copies your environment, `envFrom`, volumes and mounts; review the [preview](offline-injector.md) and give it a safe configuration.
- **Pods do extra work before they are ready:** two init containers, the archive download, and hashing the classpath JARs and the archive. Larger classpaths and archives cost more. The saving only counts if it is larger than this.
- **One archive per fingerprint** (per image, JDK and JVM options) is stored in your registry and is never deleted by this tool. See [retention](#retention).
- **The first pods start uncached.** Training is asynchronous: the trainer runs on its schedule (every 10 minutes) after you deploy, and only pods that start after it has finished can use the archive.

## Check that it works

After the trainer has finished (`kubectl get jobs`; its log says `train: TRAINED` or `ALREADY_STORED`), start new pods and read what they did:

```bash
kubectl -n NS rollout restart deployment/my-app
kubectl -n NS logs POD -c mnemo-warmup-fetch    # Archive: ... (APP_CDS)   or   (LEYDEN_AOT) on JDK 25
kubectl -n NS logs POD -c app | grep "run:"     # run: using APP_CDS archive ...
```

A trained, selected archive shows the integration works. It does **not** show that pods became ready faster.

## Measure the benefit

Compare **pod creation to Ready** with and without the cache over several restarts, including the init containers:

```bash
kubectl -n NS get pods -l app=my-app -o json | jq -r '.items[] | [.metadata.name,
  (((.status.conditions[]|select(.type=="Ready").lastTransitionTime|fromdate) - (.metadata.creationTimestamp|fromdate)))] | @tsv'
```

Pod condition times have a resolution of one second. For the JVM's own start-up figure, read the application log ("Started ... in N seconds") or run `mnemo-warmup bench --jar app.jar`, which compares no cache, AppCDS and AOT locally ([reference](reference.md#measure-the-savings-bench)).

For scale, not as a prediction: on the small example application on an arm64 kind cluster, pod start-to-Ready was 2 to 3 seconds with and without the archive, while the JVM-reported start was a median 0.48 s without and 0.31 s with it ([details](offline-injector.md#verification)). A real application with a large classpath will differ.

## What can still stop a pod

Failures **looking up or validating** the archive never stop a pod; it starts the ordinary way and its log says why.

| Situation | What you see |
|---|---|
| No archive yet (first deploy) | `fetch`: "No usable archive"; `run`: "no archive was fetched" |
| A JVM option differs from when the archive was trained | a new fingerprint: `fetch` finds none and the trainer trains one on its next run |
| New image or JARs | a new fingerprint, the same |
| Registry unreachable or login refused | `fetch` logs the reason and exits 0; the pod becomes Ready after `fetch` gives up (about 15 s with an unreachable registry in the end-to-end run) |
| Stored archive damaged | rejected by the digest check; the pod starts uncached |
| The image has no Java, or Java older than 21 | the launcher skips the tool and your app starts as before |

**Deployment failures are not covered**, because the pod cannot get as far as running the tool:

- a `pull-secret` or `push-secret` that does not exist in the namespace;
- the tools image or the application image cannot be pulled;
- with the Helm chart, an application image without `/bin/sh`;
- an application command that is wrong in the first place.

Pass `--strict` to `fetch` only if you want a missing archive to fail the pod. Do not do this in production start paths.

## Retention

Archives accumulate: one per fingerprint, hundreds of MB each, and nothing here deletes them. Bound the growth with your registry's lifecycle policy, scoped to a dedicated archive repository and to `fp-` tags. [Archive retention](archive-retention.md) explains what a deletion costs (a cache miss and one re-training run, not an outage), why a time-to-live rule does not protect running versions, and includes a reviewable Amazon ECR policy.

## Disable or remove it

To stop using the cache for a workload, remove the `warmup.mnemo.io/*` annotations (or set `enabled` to `"false"`) and redeploy through the injector. The next render has the original command, no init containers or volumes, and, with Helm, Helm removes the generated CronJob, ServiceAccount, Role and RoleBinding. With a plain `kubectl apply` of the injected output, delete the generated resources yourself: `kubectl -n NS delete cronjob,serviceaccount,role,rolebinding -l warmup.mnemo.io/owner=my-app`.

Archives stay in the registry until your retention policy or an operator removes them. Leases left behind expire on their own (`kubectl -n NS get lease | grep mnemo-warmup`).

## Known limitations

- **Trainer service account and IRSA.** The injector generates the trainer's ServiceAccount and has no annotation for it, so the EKS role annotation for the push role must be added by your own patch after rendering. The application's service account is yours and carries the pull role. See [registry authentication](registry-auth.md#eks-and-ecr).
- **Trainer schedule, deadline and resources** are fixed (every 10 minutes, one hour, none set).
- **Verified on:** kind with Helm 3, a plain-HTTP registry, JDK 21 (AppCDS), a Temurin and a distroless Java 21 image, arm64 locally; amd64 and arm64 in CI once that job has run. **Not verified:** a live EKS and ECR, Helm 4, JDK 25 through the injector, multi-container pods.
- Alpha: command-line options, annotations and the manifest format may still change.
