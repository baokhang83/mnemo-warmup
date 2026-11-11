# Configuration and requirements

How the annotation path decides what to change, what you can override, and what your application and cluster need. For the command-line options of `build`, `push`, `fetch` and the rest, see the [reference](reference.md). For the generated resources and the preview output, see the [offline injector guide](offline-injector.md).

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
| `warmup.mnemo.io/tools-delivery` | no | How the tools reach the pod: `init-container` (the default, described below) or `image-volume`, which leaves the pod with **no init containers** on the start path. See [Tools delivery](#tools-delivery-init-containers-or-an-image-volume). It cannot be combined with `pull-secret`. |

**Three annotations are required today, not two:** `enabled`, `store` and `exit-mode`. The injector stops with `missing warmup.mnemo.io/exit-mode` otherwise, because it does not guess how your application signals that it has started.

The injector also needs a namespace for the trainer resources. If your rendered Deployment has no `metadata.namespace` (Helm output usually has none), pass `--namespace` ([guide](offline-injector.md)).

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

`warmup.mnemo.io/tools-delivery: "image-volume"` removes both:

- the tools image is mounted as a read-only **image volume**, so nothing has to copy it, and the tools are at `/mnemo-tools/opt/mnemo-warmup/` in the pod;
- the launcher does the fetch itself, in your application container's entrypoint, and then replaces itself with `java` as before (`mnemo-launcher exec <fetch options> -- java ...`), so the JVM is still PID 1;
- the trainer pod uses the same volume and has no init container either;
- the application container mounts the cache read-write (it writes the fetched archive) and a scratch volume, and the fetch runs with the application's own environment, security context and resource limits. The native fetch is small; the Java fallback (a first deploy, an ECR registry, a Spring Boot fat JAR) runs a JVM inside the container briefly, so leave room for it in a tight memory limit.

What it needs and what it costs:

- **A cluster and container runtime that support image volumes** (Kubernetes 1.33 or later, where the feature is beta, and containerd 2.1 or later or CRI-O 1.31 or later; check yours). On a cluster without them a pod with the volume **does not start**. That is a deployment failure, like a missing Secret, not a cache failure, and it is why the mode is opt-in. It was verified on kind with Kubernetes 1.36.1 and containerd 2.3.1.
- **No `pull-secret`.** The fetch runs in the application container, so the Secret would be readable by the application's code. The injector stops with a message when the two are combined. Anonymous registries and registries reached through workload identity work. `push-secret` belongs to the trainer and is fine.
- The fetch's log lines appear in the application container's log, not in a container of their own.

To go back, remove the annotation or set it to `init-container` and render again: the injector records the mode it applied and restores the original Deployment either way.

The trainer copies what the application needs to start: environment variables and `envFrom` references, volumes and mounts, the image pull policy and security context, image pull secrets, node selector, tolerations and affinity. The preview lists these by **name only**; review it, because the trainer starts your application with that configuration.

## Not supported in this release

- Workloads other than `Deployment`; launches with `-cp` and a main class; JVM options that start with `@` (argument files).
- Per-workload trainer schedule, deadline or resources.
- Registry login through credential helpers, GKE and AKS workload identity, and EKS Pod Identity ([registry authentication](registry-auth.md)).
- Helm 4 post-renderer plugins: the post-renderer here is a script path, which Helm 3 accepts ([guide](offline-injector.md)).
