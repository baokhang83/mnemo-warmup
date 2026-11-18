# Configuration and requirements

How the annotation path decides what to change, what you can override, and what your application and cluster need. For the command-line options of `build`, `push`, `fetch` and the rest, see the [reference](reference.md). For the generated resources and the preview output, see the [offline injector guide](offline-injector.md).

## CLI installation options

The [installer](../scripts/install.sh) needs Java 21 or newer to run the CLI. It defaults to the published `v0.3.0-beta.2` release. Set `MNEMO_VERSION` to a different supported release tag and `MNEMO_INSTALL_PREFIX` to an absolute directory to change where the CLI is installed:

```bash
curl -fsSL https://raw.githubusercontent.com/baokhang83/mnemo-warmup/main/scripts/install.sh | \
  MNEMO_VERSION=v0.3.0-beta.2 MNEMO_INSTALL_PREFIX="$HOME/.local" sh
export PATH="$HOME/.local/bin:$PATH"
```

The installer verifies SHA-256 digests for the JAR and wrapper. These checks detect corrupt or mismatched downloads; they do not independently authenticate a compromised release source.

Alternatively, extract the CLI from the tools image:

```bash
docker run --rm -v "$PWD":/out --entrypoint cp ghcr.io/baokhang83/mnemo-warmup-tools:0.3.0-beta.2 -r /opt/mnemo-warmup /out/
export PATH="$PWD/mnemo-warmup/bin:$PATH"
mnemo-warmup --version
```

## Requirements

- **Kubernetes** with `batch/v1` CronJobs (1.21 or newer) and the `coordination.k8s.io/v1` Lease API. The injector handles `Deployment` resources only.
- **Application image:** Java **21 or newer** (the trainer fails visibly without it; `fetch` and start fall back to an ordinary start). With the injector the image needs no shell, so distroless Java images work. The Helm-chart setup does need `/bin/sh` ([chart guide](helm-chart.md)).
- **Launch command:** an explicit `java [JVM options] -jar <jar> [arguments]` in the container's `command` and `args`. Launches with `-cp` and a main class are not supported by the injector.
- **JARs fixed at build time:** the trainer and every pod must run the same files. For Spring Boot 3.3 or newer, extract the JAR when you build the image ([below](#spring-boot)); another executable JAR needs its dependencies listed in its `Class-Path`.
- **An OCI registry** reachable from the cluster, for archives: [authentication](registry-auth.md) and storage are covered there. Archives can reach hundreds of MB, so check the registry's blob-size limit, and keep tag immutability off for the archive repository.
- **A workload whose startup can run once more, for real, in a separate trainer pod** ([operations](operations.md#what-it-costs)).

## Annotations

Set these on the Deployment's **pod template** (`spec.template.metadata.annotations`). Values are strings, so quote `"true"`.

| Annotation | Required | Meaning |
|---|---|---|
| `warmup.mnemo.io/enabled` | yes | `"true"` turns adoption on. `"false"` (or leaving the annotations out) leaves the workload untouched and restores fields the injector owns on an already-injected stream. |
| `warmup.mnemo.io/store` | yes | The archive repository, `oci://host[:port]/path`. Not your application image repository. |
| `warmup.mnemo.io/exit-mode` | yes | How the trainer knows startup is done: `spring-refresh` (Spring Boot: the context has refreshed) or `http-probe` (any application: an HTTP URL answers). |
| `warmup.mnemo.io/probe-url` | with `http-probe` only | An HTTP URL reachable from the trainer pod. Not allowed with `spring-refresh`. |
| `warmup.mnemo.io/container` | if the pod has several containers | The name of the application container. |
| `warmup.mnemo.io/command` | no | A complete JSON string array for the launch, for an image whose Java command comes from `ENTRYPOINT`, for example `["java","-jar","/app/app.jar"]`. Replaces `command` plus `args` for discovery only. |
| `warmup.mnemo.io/pull-secret` | no | Name of a `kubernetes.io/dockerconfigjson` Secret in the workload's namespace with **pull** access to the archive repository. [Details](registry-auth.md#docker-config-secrets). |
| `warmup.mnemo.io/push-secret` | no | The same with **push** access, used by the trainer. |
| `warmup.mnemo.io/plain-http` | no | `"true"` talks plain HTTP to the registry. Local registries and tests only. |
| `warmup.mnemo.io/tools-delivery` | no | How the tools reach the pod: `init-container` (the default) or `image-volume`. A pull Secret or node cache uses a fetch init container even with an image volume. See [Tools delivery](#tools-delivery-init-containers-or-an-image-volume). |
| `warmup.mnemo.io/signing-public-keys` | no | The public keys pods trust, as printed by `mnemo-warmup keygen`: `id=base64`, several separated by commas. **Setting it makes every pod require a valid signature.** See [Signed archives](#signed-archives-opt-in). |
| `warmup.mnemo.io/signing-key-secret` | no | Name of a Secret with the private key under `signing.key`. Mounted into the trainer only, which then signs everything it publishes. |
| `warmup.mnemo.io/isolated-training` | no | `"true"` trains in a separate pod that never holds the signing key or push credential; a coordinator signs and publishes. Requires signing and the four annotations below. See [Isolated training](#isolated-training-opt-in). |
| `warmup.mnemo.io/publisher-image` | with isolated training | Trusted coordinator image, pinned by digest (`...@sha256:...`). It must contain the same JDK build and application files, at the same paths, as the application image. The tools image must be pinned by digest too. |
| `warmup.mnemo.io/publisher-java` | with isolated training | Absolute path of the `java` executable in the publisher image. |
| `warmup.mnemo.io/handoff-claim` | with isolated training | An existing PersistentVolumeClaim, with POSIX permissions, through which the training pod hands the archive to the coordinator. |
| `warmup.mnemo.io/handoff-fs-group` | no | The handoff PVC's root group, given to both pods as `fsGroup`. Default `65532`. |
| `warmup.mnemo.io/training-allowed-secrets` | if training uses Secrets | Comma-separated names of the only Secrets the training pod may reference (volumes, `env`, `envFrom`). Any other Secret reference is refused at render; the signing and push Secrets can never be listed. |
| `warmup.mnemo.io/node-cache-url` | with node cache | HTTPS origin of the node-local cache Service. |
| `warmup.mnemo.io/node-cache-ca-configmap` | with node cache | ConfigMap containing the cache CA certificate under `ca.crt`. |
| `warmup.mnemo.io/node-cache-audience` | with node cache | Audience for the projected service-account token. |

**Three annotations are required today, not two:** `enabled`, `store` and `exit-mode`. The injector stops with `missing warmup.mnemo.io/exit-mode` otherwise, because it does not guess how your application signals that it has started.

The injector also needs a namespace for the trainer resources. If your rendered Deployment has no `metadata.namespace` (Helm output usually has none), pass `--namespace` ([guide](offline-injector.md)).

## Choosing the store

`warmup.mnemo.io/store` is `oci://` followed by a registry repository path, written like an image reference without a tag. mnemo-warmup adds the tags itself, one `fp-<fingerprint>` tag per fingerprint.

- **Use the registry you already have,** with a new repository in it.
- **Use one repository per application, holding only caches.** Each cache is typically hundreds of MB and nothing deletes old ones, so give this repository its own lifecycle policy on `fp-` tags, one that never touches application images ([archive retention](archive-retention.md)). Keep tag immutability off for it.
- **Make sure the repository exists, or that the registry creates it on first push.**

| Registry | Example `store` value | Create it first? |
| :--- | :--- | :--- |
| GitHub Container Registry | `oci://ghcr.io/my-org/my-app-warmup` | No, created on first push |
| Docker Hub | `oci://registry-1.docker.io/my-org/my-app-warmup` | No, created on first push |
| Harbor | `oci://harbor.example.com/my-project/my-app-warmup` | The project must exist |
| Amazon ECR | `oci://123456789012.dkr.ecr.eu-west-1.amazonaws.com/my-app-warmup` | Yes, unless create-on-push is enabled |
| Google Artifact Registry | `oci://europe-west1-docker.pkg.dev/my-project/my-repo/my-app-warmup` | The Artifact Registry repository must exist |

These rows follow each registry's usual conventions. They are illustrative and have not each been tested with mnemo-warmup.

- **Give the tool credentials for it.** Image-pull credentials are not used, because the tool talks to the registry from inside pods. The trainer needs pull and push, the application pods pull only ([registry authentication](registry-auth.md)).

A wrong store or a refused login does not fail the deployment: pods start without a cache and the trainer exits 0. After the first training run, check the trainer's log for `train: TRAINED ...` ([README](../README.md#then-train-and-check-that-it-works)).

## How the launch command is read

The selected container must have an explicit `command` and optional `args` that form:

```text
java [JVM options] -jar <jar> [application arguments]
```

- The first word must be `java` or end in `/java`.
- Everything between `java` and `-jar` is a JVM option. Each must start with `-`. `-cp`, `--class-path`, `-classpath` and `@argfiles` are refused.
- The word after `-jar` is the JAR path. The injector gives the JVM options to `fetch` and to the trainer, so **a changed JVM option is a new fingerprint**: new pods start without an archive until the trainer has trained one for the new options, and rolling back finds the old archive again.
- Anything else is reported by the preview with the reason, and nothing is changed. Use `warmup.mnemo.io/command` when the image supplies the Java command.

## Spring Boot

An archive remembers the JAR files it was trained on, so the trainer and every pod must run identical files at identical paths. Extract the Spring Boot JAR when you build the image:

```dockerfile
RUN java -Djarmode=tools -jar target/app.jar extract --destination /out
...
COPY --from=build /out /app
```

and run `java -jar /app/app.jar` (so the annotation path sees `-jar /app/app.jar`). The trainer starts the application for real and stops it once the Spring context has refreshed (`exit-mode: spring-refresh`). Complete, buildable examples: [`examples/spring-boot/Dockerfile`](../examples/spring-boot/Dockerfile) (Temurin) and [`examples/spring-boot/Dockerfile.distroless`](../examples/spring-boot/Dockerfile.distroless) (no shell).

The image's JDK decides the cache type: AppCDS on JDK 21 and later, the Leyden AOT cache on JDK 25. The annotation path was run end to end on JDK 21 (AppCDS) only; the Helm-chart path covers both.

## What the injector adds

For each opted-in Deployment, by default: two init containers (`mnemo-warmup-install` copies the launcher and tools from the tools image; `mnemo-warmup-fetch` runs `fetch` in your application's image), a changed application command that starts `java` through the launcher so it is still PID 1, shared `emptyDir` volumes, and a trainer **CronJob** with its ServiceAccount, Role (leases only) and RoleBinding. The CronJob runs every 10 minutes, does not overlap runs and stops a run after one hour; these values are not configurable in this release.

### Tools delivery: init containers or an image volume

By default (`init-container`) two init containers run before every start, one after the other: the first copies the tools out of the tools image, the second fetches the archive. Their own work takes well under a second, but the kubelet starts containers one at a time and each costs a start-up, so on the benchmark clusters they delayed the application's start by about 1.75 s ([ADR 0012](adr/0012-tools-as-an-image-volume.md)).

`warmup.mnemo.io/tools-delivery: "image-volume"` removes the install init container. When no pull Secret or node cache is configured, it also removes the fetch init container:

- the tools image is mounted as a read-only **image volume**, so nothing has to copy it, and the tools are at `/mnemo-tools/opt/mnemo-warmup/` in the pod;
- without a pull Secret or node cache, the launcher does the fetch in the application container's entrypoint, then replaces itself with `java` as before (`mnemo-launcher exec <fetch options> -- java ...`), so the JVM is still PID 1;
- the trainer pod uses the same volume and has no init container either;
- the application container mounts the cache read-write (it writes the fetched archive) and a scratch volume, and the fetch runs with the application's own environment, security context and resource limits. The native fetch is small; the Java fallback (a first deploy, an ECR registry, a Spring Boot fat JAR) runs a JVM inside the container briefly, so leave room for it in a tight memory limit.

What it needs and what it costs:

- **A cluster and container runtime that support image volumes** (Kubernetes 1.33 or later, where the feature is beta, and containerd 2.1 or later or CRI-O 1.31 or later; check yours). On a cluster without them a pod with the volume **does not start**. That is a deployment failure, like a missing Secret, not a cache failure, and it is why the mode is opt-in. It was verified on kind with Kubernetes 1.36.1 and containerd 2.3.1.
- **Pull Secret or node cache:** the injector creates a fetch init container to keep registry credentials and cache identity out of the application container. Without either, fetch log lines appear in the application container's log.

The optional [node-local archive cache](../deploy/helm/mnemo-nodecache/README.md) requires all three node-cache annotations, a matching cache Service, CA, and authorized service account. If the cache is unavailable, the native fetcher falls back to the registry; the Java fallback does not use the node cache.

To go back, remove the annotation or set it to `init-container` and render again: the injector records the mode it applied and restores the original Deployment either way.

The trainer copies what the application needs to start: environment variables and `envFrom` references, volumes and mounts, the image pull policy and security context, image pull secrets, node selector, tolerations and affinity. The preview lists these by **name only**; review it, because the trainer starts your application with that configuration.

## Signed archives (opt-in)

By default a pod checks that an archive is intact, not who published it. Signing closes that: the trainer signs each archive, and a pod that has been given the matching public key uses an archive only if the signature checks out. Anything else (unsigned, signed by another key, altered, or moved from another repository) is treated as missing: the pod starts without a cache and the log says why. It never stops the application.

**Turn it on**

1. Make a key pair. The private key goes to a file that only you can read and is never printed or overwritten; the public key is printed.
   ```bash
   mnemo-warmup keygen --out signing.key
   ```
2. Give the private key to the trainer as a Secret in the workload's namespace, then keep `signing.key` somewhere safe (a password manager or secret store) and remove the local copy.
   ```bash
   kubectl -n my-namespace create secret generic my-app-signing --from-file=signing.key=signing.key
   ```
3. Add two annotations to the Deployment's pod template (or, with the Helm chart, `signing.publicKeys` and `signing.keySecret`):
   ```yaml
   warmup.mnemo.io/signing-public-keys: "21fe31dfa154a261=11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="   # from keygen
   warmup.mnemo.io/signing-key-secret: my-app-signing
   ```
4. **If the trainer runs as a non-root user,** give the pod an `fsGroup` (in the Deployment's pod `securityContext`, which is copied to the trainer pod; with the chart, `trainer.podSecurityContext.fsGroup`). A Secret file is owned by root and is readable by its owner and its group only (mode 0440), so a non-root trainer reads it through the group. The preview and the chart refuse a trainer that is known to be non-root without an `fsGroup`; when the image decides the user, the preview says to check. A trainer that cannot read the key logs `permission denied` with this fix and **publishes nothing**, never an unsigned archive.
5. Run the preview. It lists the key ids pods will trust and the Secret the trainer signs with, and warns if only one of the two annotations is set. A typo in the key list is an error here, not a key that silently trusts nothing.

**What to expect on the first rollout.** Existing archives are unsigned, so the trainer counts them as incomplete and publishes signed ones on its next run (the CronJob runs every 10 minutes). Until then pods start without a cache and the fetch log says `... is not signed; not using it`. That is the normal, safe behaviour.

**Rotate a key.** (1) `keygen` a new key. (2) Add its public key to the pods' list next to the old one and roll out; pods now accept both. (3) Switch the trainer's Secret to the new key. (4) When nothing signed with the old key is wanted, remove the old public key from the list and roll out.

How the trainer behaves, so you are not surprised: it is given the same list as the pods and treats an entry as complete if **any listed key** signed it. So after step 3 the existing archive keeps the **old** key's signature, and nothing is re-published, until step 4; after step 4 the pods would refuse that archive, the trainer sees the same thing, and re-publishes it under the new key. Several trainers (for example one per cluster) can share a repository and use different keys, as long as every pod and every trainer lists all of those keys: none overwrites another's entry. A trainer whose own key is **not** on the list stops with an error (`the signing key ... is not in MNEMO_WARMUP_SIGNING_PUBLIC_KEYS`) instead of publishing archives that every pod would refuse.

**If you lose the private key,** no pod breaks: archives it signed keep verifying, because pods hold only the public key. You just cannot publish new archives until you create a new key and roll it out as above.

**What it protects, and what it does not.** It stops anyone who can write to the archive repository, or sit on the network path, but does not hold the private key. It does **not** stop code running inside the trainer container during training, because that container runs your application and also holds the key. [Isolated training](#isolated-training-opt-in) keeps the key away from application code ([ADR 0013](adr/0013-signed-archives.md), stage B); without it, give a workload that runs untrusted code its own key (one key per namespace is the default, one per workload where the trust differs).

**How it works, briefly.** The signed text is fixed: the archive's fingerprint, the digest of the stored manifest, the digest and size of the archive, and the repository. A pod rebuilds that text from what it actually received and checks the signature against it, so a valid signature cannot be moved to another archive or to another repository path. `fetch` checks before it downloads the archive, and `run` checks again at every start against the files on disk.

**Two limits worth knowing.**

- **The registry host is not part of what is signed.** The trainer and the pods may reach one registry under different names (a mirror, for example), so only the repository path is signed. A signed archive copied to **another registry under the same repository path** therefore verifies there too. Use separate keys for registries that are different trust domains.
- **The second check narrows the window; it does not close it against a writer.** `run` detects changes made to the cache directory between the fetch and the start. A process that can write to the same underlying volume could still swap the archive in the instant between that check and the JVM opening it. Mount the cache **read-only** in every container that only consumes it: that protects against writes through that mount only. (The chart and the injector already mount it read-only in the application container, except in image-volume mode, where the entrypoint fetches and so must write it.) Another container or process with write access to the same volume can still race the JVM open.

## Isolated training (opt-in)

With `warmup.mnemo.io/isolated-training: "true"` (or `trainer.isolation.enabled` in the Helm chart) the trainer is split in two ([ADR 0013](adr/0013-signed-archives.md), stage B):

- a **coordinator** CronJob runs the trusted publisher image as UID 65532 with the signing key, the push credential and a Kubernetes token. It checks the store, and only when an archive is missing creates a **training pod**, waits for it to succeed, then copies the archive from the handoff into its own temporary directory, checks it against a manifest it computed itself, signs and publishes it;
- the **training pod** runs your application image under its own ServiceAccount with no API token, no signing key and no push credential. It writes into a fresh per-run subdirectory of the handoff PVC and nothing else. The pod template is checked before it is created, against allow-lists: it must satisfy Pod Security `restricted` (the coordinator adds `runAsNonRoot: true` and a `RuntimeDefault` seccomp profile when absent, drops all capabilities and forbids privilege escalation), and host namespaces, `hostPath`, root users, `seLinuxOptions`, `windowsOptions`, `sysctls`, `procMount: Unmasked`, `Unconfined` profiles, added capabilities, any projected source other than `configMap`, `secret` and `downwardAPI`, and any Secret that is not explicitly allowed are refused. The image must therefore not run as root, or the container must set a non-zero `runAsUser`.

The template lives in an immutable ConfigMap named after its content, and the coordinator refuses it unless its SHA-256 matches the digest in the CronJob: write access to ConfigMaps is often granted more widely than pod creation, and must not be enough to choose what the coordinator creates. Training pods carry the label `warmup.mnemo.io/role: training`, which only the coordinator sets, so a NetworkPolicy or admission policy can select them (for example to block the cloud metadata address `169.254.169.254`). They are owned by the coordinator pod, so if the coordinator is evicted or deleted the training pod is garbage-collected too.

The injector generates two ServiceAccounts, a Role (leases, and get/create/delete pods), a RoleBinding, an immutable ConfigMap with the training pod template and the coordinator CronJob. The Deployment itself is injected exactly as without isolation. `envFrom` and `image-volume` tools delivery are not supported in isolated mode yet: the coordinator needs the target's `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS` as explicit values, and only those two variables are copied from the application container (as `MNEMO_TARGET_*` data, never as options of the coordinator's own JVM). If the image itself sets those variables, also set them on the container.

Before you turn it on:

1. **Build the publisher image.** Use the application image's base JDK (same vendor and build) and copy in the application files at the same paths and with the same modification times. The simplest way is `FROM` the application image, with the Java executable and the application files readable by UID 65532. Pin it, and the tools image, by digest.
2. **Create the handoff PVC** in the workload's namespace. Its root directory's group must be the `handoff-fs-group`, and the filesystem must honour POSIX group permissions (the coordinator refuses to start otherwise). A `ReadWriteOnce` volume can require both pods on the same node; use `ReadWriteMany` storage when they may be scheduled apart. Keep it dedicated to this workflow. The coordinator copies the archive into its own size-limited `emptyDir` without following links and with time limits on opening and copying, so a named pipe or a slow writer fails one run instead of blocking it.
3. **Restrict the namespace.** The coordinator may create pods, and a pod in that namespace can mount any Secret there or run as any ServiceAccount there. Use a dedicated namespace and admission policy (Pod Security `restricted`, or a policy engine) so that pods it creates cannot do more than the template allows. Make sure the training ServiceAccount has no cloud IAM rights to push or sign, including through workload identity or the node metadata server; a missing token mount alone does not prevent that.
4. **Know what is trusted.** The publisher image, the tools image, the JDK and the generated templates are trusted. A signature proves which publisher signed the bytes, not that the application that trained them is benign.

A failed or timed-out run keeps its handoff directory, because a deleted pod may still be running. The training pod's `warmup.mnemo.io/handoff` annotation names the directory; remove it only after the pod is gone. Training code can also force a directory to be kept (for example by creating a subdirectory the coordinator may not remove), so **monitor the number of `mnemo-run-*` directories** on the PVC and the coordinator's `retained handoff` log lines; a growing count means the PVC will fill. Disable isolation by removing the annotations and rendering again: the classic trainer comes back.

## Limits on the fetch

A slow, huge or broken registry must never keep a pod from starting, so the fetch stops itself and the pod starts without a cache (exit code 0, the reason in the log). Two environment variables on the application container set the limits; a value that is not a positive whole number is ignored with a log line and the default applies.

| Variable | Default | Meaning |
|---|---|---|
| `MNEMO_WARMUP_FETCH_DEADLINE_SECONDS` | `120` | The whole fetch (login, manifest, download, check) must be over by then, however slowly bytes arrive. `--store-timeout-seconds` is separate: it only catches total silence for that long. |
| `MNEMO_WARMUP_MAX_ARCHIVE_BYTES` | `1073741824` (1 GiB) | The largest archive the fetch will download. A larger declared size is refused before any of it is requested. The fetch also refuses an archive that does not fit in the free space of the cache volume. |

The archives measured so far were 54 to 224 MiB. Raise the size limit for an application whose archive is larger, and the deadline for a slow network.

**Where this applies:** both fetches. The native fetch (the common path) and the Java fetch (ECR, proxies, unknown certificate authorities, fat JARs) read the same two variables and refuse in the same way. The Java fetch also refuses a manifest larger than 1 MiB, a manifest blob larger than 8 MiB and a token response larger than 1 MiB, and if it ever runs out of memory the pod still starts without a cache (unless `--strict`). The limits apply to pulls only: the trainer can still publish an archive larger than the cap, but pods will not fetch it until you raise `MNEMO_WARMUP_MAX_ARCHIVE_BYTES`.

## Not supported in this release

- Workloads other than `Deployment`; launches with `-cp` and a main class; JVM options that start with `@` (argument files).
- Per-workload trainer schedule, deadline or resources.
- Registry login through credential helpers, GKE and AKS workload identity, and EKS Pod Identity ([registry authentication](registry-auth.md)).
- Helm 4 post-renderer plugins: the post-renderer here is a script path, which Helm 3 accepts ([guide](offline-injector.md)).
