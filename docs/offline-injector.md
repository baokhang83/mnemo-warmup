# Offline workload injector

The injector reads a complete rendered Kubernetes YAML stream from standard input. It does not contact the cluster. It emits transformed manifests on standard output and diagnostics on standard error. It requires Helm or another deployer to apply and remove the resulting resources.

Annotate the Deployment pod template:

```yaml
warmup.mnemo.io/enabled: "true"
warmup.mnemo.io/store: "oci://registry.example.com/team/app-warmup"
warmup.mnemo.io/exit-mode: "spring-refresh"
```

For `http-probe`, set `warmup.mnemo.io/probe-url` to an HTTP URL reachable from the trainer pod. Select one container with `warmup.mnemo.io/container` if the pod has more than one. The selected container must have an explicit `command` and optional `args` forming `java [options] -jar <jar> [args]`. For an image-supplied entrypoint, supply a complete JSON string array through `warmup.mnemo.io/command`.

For a private archive registry, `warmup.mnemo.io/pull-secret` and `warmup.mnemo.io/push-secret` name separate `kubernetes.io/dockerconfigjson` Secrets in the workload namespace. Image-pull credentials do not automatically grant access to the archive store. An absent Secret prevents a pod that mounts it from starting. The trainer runs the application with its configuration; review startup side effects and copied mounts before use.

```sh
helm template my-app ./my-chart --namespace my-namespace \
  | mnemo-warmup inject --namespace my-namespace --preview

helm upgrade --install my-app ./my-chart --namespace my-namespace \
  --post-renderer ./scripts/mnemo-warmup-inject \
  --post-renderer-args=--namespace=my-namespace
```

The adapter requires `mnemo-warmup` on `PATH`. **Helm version:** this form (`--post-renderer` with a path to an executable) is Helm 3's interface. Helm's reference for 4.3.1 describes `--post-renderer` as taking *the name of a post-renderer plugin* (and lists `--post-renderer-args`), so on Helm 4 a script path is not expected to work; a plugin is needed, and this repository does not ship one yet. This has not been tested on Helm 4. Until then, with Helm 4 render and inject in a pipeline (`helm template ... | mnemo-warmup inject --namespace ... | kubectl apply -f -`, or commit the result for GitOps), keeping in mind that resources applied that way are not tracked as a Helm release, so removal needs your deployer to prune them. Preview prints workload details and changed paths without field values. Helm output contains the complete transformed stream; an error returns nonzero without emitting a partial stream. If the rendered Deployment does not specify `metadata.namespace`, pass `--namespace` so trainer resources use the release namespace.

**Tools image.** The generated `mnemo-warmup-install` init containers run `/opt/mnemo-warmup/bin/mnemo-launcher install /mnemo-tools` in `ghcr.io/baokhang83/mnemo-warmup-tools:<version>`, where `<version>` is the release version of the `mnemo-warmup` that runs the injector (the CLI is shipped in that same image). A development build has no release version and stops with a message asking for `--tools-image <image>`; pass it explicitly (it is also how to point at a private mirror), for example `--post-renderer-args=--tools-image=registry.example.com/mirror/mnemo-warmup-tools:0.2.5`. A stream with no opted-in workload never needs it.

**Without init containers.** Add `warmup.mnemo.io/tools-delivery: "image-volume"` to mount the tools image as an image volume and let the launcher fetch inside the application container, so the pod has no init containers on the start path. It needs a cluster that supports image volumes; with `pull-secret`, one isolated fetch init container remains; the [annotation reference](configuration.md#tools-delivery-init-containers-or-an-image-volume) and [ADR 0012](adr/0012-tools-as-an-image-volume.md) have the details. The preview shows `tools-delivery=` for each workload. Switching between the two modes, in either direction, is a re-render: the injector first restores the Deployment from what it recorded, then applies the mode now asked for.

**What the preview lists.** For each opted-in workload: container, image, JAR, store, trainer mode and tools image, then what the trainer and fetch containers copy from the workload, **by name only** (environment variable names, `envFrom` Secret/ConfigMap names, volume names and their kind, mount paths) and the archive credential Secret names. It never prints a value. Review that list: the trainer starts the application for real with that configuration.

**Generated trainer.** One CronJob (`*/10 * * * *`, `concurrencyPolicy: Forbid`, 3600 s deadline), ServiceAccount, Role (`get`, `create`, `update`, `delete` on `leases` only) and RoleBinding per workload, named after the Deployment with a short hash suffix. These settings are not configurable yet. Deployment names longer than 63 characters are rejected, because the Deployment name is used as a label value on the generated resources.

Disable by removing the opt-in annotation from the source Deployment and rerendering. Helm owns removal of generated resources. For an already-injected stream, set the annotation to `"false"`; the injector restores fields it owns and rejects changes to managed fields. It does not delete archives or external Secrets. Repeated transformation must not accumulate warmup resources.

Current limitations: this injector requires an explicit Java launch, a registry URL and a trainer mode. It does not inspect image metadata or infer readiness probes. Every generated container runs the static `mnemo-launcher`, so the application image needs no shell and distroless images work; it does need Java 21 or newer (the trainer fails visibly without it, the fetch and start steps fall back to an ordinary start). Details and differences from the shell launcher, such as `fetch --strict` and non-ASCII arguments, are in [the reference](reference.md#shell-free-distroless-application-images). Inspect the preview and generated trainer configuration before deploying. Kubernetes image, Secret and volume errors can still block a pod even though cache lookup and validation failures fall back to an ordinary start.

## Verification

`scripts/e2e-kind-inject.sh` runs the annotated path on a real kind cluster with Helm 3 and the injector as a post-renderer, using a plain Deployment with three annotations (`examples/annotated-app`) and a local registry as the archive store. It runs twice, once with an `eclipse-temurin` JRE image (has a shell) and once with `gcr.io/distroless/java21-debian12` (no shell). For each image it checks:

- **Preview:** names the workload, store and copied environment variable names; prints no value.
- **First start:** the application becomes Ready without an archive, `fetch` logs that it found none and exits 0, and the original `java` options, `-jar` and JAR follow the launcher in the container arguments. On the temurin image `java` is PID 1.
- **Asynchronous training:** a separate Job trains and uploads while the application stays Ready; a second Job finds the entry and does not train; the Lease is released.
- **Cache hit:** after a pod restart `fetch` finds the archive and the application's own class loads from it. Deleting the pod ends it in about a second, so SIGTERM reaches `java`.
- **Workload update and rollback:** a changed JVM option is a new fingerprint, because the injector passes the options to `fetch` and the trainer. The updated pod starts uncached with the reason logged, the trainer trains an archive for the new options, and rolling back finds the original archive again.
- **Invalid cache:** 4 KiB of the stored archive are overwritten with random bytes (same size). `fetch` rejects it by digest and the pod starts uncached and Ready. The test fails if the corruption step does not actually change the blob.
- **Registry failure:** with the registry stopped, the pod becomes Ready about 14 to 15 seconds after the pod is deleted (the time `fetch` takes to give up); once the registry is back, new pods use the archive again.
- **Removal:** with `warmup.enabled=false` the Deployment has its original command and no init containers or volumes, and Helm prunes the generated CronJob, ServiceAccount, Role and RoleBinding. This holds for both tools-delivery modes.
- **Image-volume mode:** the same scenarios run with `warmup.toolsDelivery=image-volume`: the pod has no init containers, the archive is fetched and used, the corrupted archive is rejected, the stopped registry does not stop the pod, and removal restores the Deployment.

```sh
scripts/e2e-kind-inject.sh                 # both images; needs docker, kind, kubectl, Helm 3, Java 21 and network access
scripts/e2e-kind-inject.sh distroless      # one image
REPEATS=5 KEEP=1 scripts/e2e-kind-inject.sh
```

**Start-to-Ready.** The script reports the pod's start-to-Ready time from its own condition timestamps (creation to Ready, and creation to the end of the init containers), `REPEATS` times with and without an archive, and the "Started ... in N seconds" figure that Spring prints. It reports and never fails on speed. On one run on an arm64 kind cluster (Docker Desktop on macOS), the example application started in a median 0.48 s without an archive and 0.31 s with one on both images. Pod conditions have a resolution of one second and the example application is very small, so the pod start-to-Ready times were 2 to 3 seconds both with and without an archive and the difference is not visible there. These numbers do not predict savings for a large application; use `mnemo-warmup bench` on your own JAR.

**Where it runs.** The `e2e` GitHub Actions workflow has a `kind-inject` job for linux/amd64 (`ubuntu-latest`) and linux/arm64 (`ubuntu-24.04-arm`). At the time of writing this was run locally on arm64 only; the CI job itself has not yet run. Treat everything below as unverified until it has.

**Not verified:**

- linux/amd64, until the CI job has run, and any architecture the cluster does not run natively;
- a real cloud registry and EKS/ECR workload identity: the script uses an unauthenticated plain-HTTP registry on the kind network (the ECR path is covered by unit tests with local fakes and by [the setup guide](aws-workload-identity.md) checklist);
- Helm 4 (see above), GitOps tools that apply rendered output, and clusters other than kind;
- JDK 25 (AOT) through the injector: the script uses JDK 21 (AppCDS), and `scripts/e2e-kind.sh` covers both JDKs through the chart;
- a Deployment with several containers, probes with a startup probe, or applications that need Secrets or volumes at start-up;
- whether the trainer repairs a stored archive whose bytes were corrupted without changing its size (the script shows that `fetch` rejects it, not what the trainer does next).

