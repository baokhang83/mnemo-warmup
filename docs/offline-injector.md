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

**Tools image.** The generated `mnemo-warmup-install` init containers use `ghcr.io/baokhang83/mnemo-warmup-tools:<version>`, where `<version>` is the release version of the `mnemo-warmup` that runs the injector (the CLI is shipped in that same image). A development build has no release version and stops with a message asking for `--tools-image <image>`; pass it explicitly (it is also how to point at a private mirror), for example `--post-renderer-args=--tools-image=registry.example.com/mirror/mnemo-warmup-tools:0.2.0`. A stream with no opted-in workload never needs it.

**What the preview lists.** For each opted-in workload: container, image, JAR, store, trainer mode and tools image, then what the trainer and fetch containers copy from the workload, **by name only** (environment variable names, `envFrom` Secret/ConfigMap names, volume names and their kind, mount paths) and the archive credential Secret names. It never prints a value. Review that list: the trainer starts the application for real with that configuration.

**Generated trainer.** One CronJob (`*/10 * * * *`, `concurrencyPolicy: Forbid`, 3600 s deadline), ServiceAccount, Role (`get`, `create`, `update`, `delete` on `leases` only) and RoleBinding per workload, named after the Deployment with a short hash suffix. These settings are not configurable yet. Deployment names longer than 63 characters are rejected, because the Deployment name is used as a label value on the generated resources.

Disable by removing the opt-in annotation from the source Deployment and rerendering. Helm owns removal of generated resources. For an already-injected stream, set the annotation to `"false"`; the injector restores fields it owns and rejects changes to managed fields. It does not delete archives or external Secrets. Repeated transformation must not accumulate warmup resources.

Current limitations: this injector requires an explicit Java launch, a registry URL and a trainer mode. It does not inspect image metadata or infer readiness probes. The generated launcher currently needs `/bin/sh`; distroless support is tracked separately. Inspect the preview and generated trainer configuration before deploying. Kubernetes image, Secret and volume errors can still block a pod even though cache lookup and validation failures fall back to an ordinary start.
