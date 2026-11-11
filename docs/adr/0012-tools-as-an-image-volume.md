# ADR 0012: deliver the tools as an image volume, and fetch inside the application container

Status: accepted (v0.2.6), opt-in

## Context

Two init containers run before every application start: `mnemo-warmup-install` copies the tools out of the tools image into a
shared volume, and `mnemo-warmup-fetch` (in the application image) looks the archive up. On the benchmark the work they do is
small (the native fetch is about 80 ms, `run` about 50 ms, ADR 0010 and 0011) but a pod with them starts the application
about 1.75 s later than a pod without, in a stage-by-stage timeline of the Jenkins runs: the kubelet starts the init containers one
after the other and waits for each to finish before the next. That is Kubernetes's cost of having two more containers, not ours,
and it cancels most of what the archive saves (docs: `benchmarks/README.md`).

The only way to remove it is to have fewer containers. Kubernetes can mount an OCI image as a read-only volume (the
`image` volume source; beta in Kubernetes 1.33, with container runtime support: containerd 2.1 or later, CRI-O 1.31 or
later). Mounted so, the tools image needs no container to unpack it. It was tried first: on a kind node running Kubernetes
1.36.1 and containerd 2.3.1 the volume mounts read-only without a feature-gate change, and the launcher binary runs from it.

Constraints: a cache problem never stops an application (§1) and the start-path helpers fail open (§3); the Secret that grants
read access to the archive store should not be readable by the application's own code; the core library gets no new
dependency (§4).

## Decision

A new, **opt-in** delivery mode, selected by `warmup.mnemo.io/tools-delivery: image-volume` on the pod template (the injector)
or `tools.delivery: image-volume` (the Helm chart). The default stays `init-container`, which is exactly today's behaviour.

- **No init containers on the start path.** The `mnemo-tools` volume is an `image` volume of the tools image. Everything the old
  volume held is there, under `opt/mnemo-warmup`, so the launcher is
  `/mnemo-tools/opt/mnemo-warmup/bin/mnemo-launcher`. (The volume is mounted whole: `subPath` on image volumes is newer than the
  feature itself, and nothing here should depend on it.)
- **The fetch runs in the application container's own entrypoint.** `mnemo-launcher exec` accepts the fetch options before
  `--`: `exec <fetch options> -- <command>` runs the fetch, then decides and replaces itself with the application, as before.
  The fetch fails open exactly as the init container did (§3): every problem is a note and the application starts uncached.
  The launcher still becomes the JVM (`exec`), so the JVM is PID 1 and gets SIGTERM directly.
- **The application container therefore mounts the cache read-write** (it was read-only) and a scratch volume, and the fetch
  uses the application's own environment and security context. These are the things the init container copied from it.
- **The registry pull Secret is refused in this mode.** The injector stops with a message and the chart fails to render. The Secret
  would have to be mounted into the application container, where the application's code can read it; the init container never
  gave it that. Anonymous registries and registries reached through the pod's workload identity work; a pod that needs a
  pull Secret keeps the init-container mode. (The push Secret is the trainer's, unchanged.)
- **The trainer uses the image volume too:** its install init container goes away and its tools volume becomes the image volume.
  Its launch paths use the same prefix.
- **A cluster that cannot mount image volumes cannot start the pod.** This is a deployment failure, in the same category as a
  missing Secret or an image that cannot be pulled (`docs/operations.md`), not a cache failure, and §1 and §3 are about cache
  problems. It is the reason the mode is opt-in, and it is documented where the annotation is.
- **Removal is the same operation as before:** disabling the annotation (or setting it to `init-container`) and rendering again
  restores the original Deployment. The injector records which mode it applied, so it restores the right fields.

## Rejected

- **Make it the default.** Clusters, runtimes and managed Kubernetes versions differ in image volume support. A default that
  fails to start pods on some of them is not an optimisation we may impose (§1).
- **Keep the fetch init container and drop only the install container.** It keeps the Secret out of the application, and it
  saves one container instead of two. It is the right shape if a pull Secret is needed with image volumes; it can be added as a
  third value without changing this one.
- **A mutating admission webhook** instead of rendering the Deployment: a new always-on component on the cluster's critical path.

## Consequences

- The fetch's CPU and memory now count against the application container's limits. The native path is tiny; the Java fallback
  (a miss, an ECR host, a Spring fat JAR) runs a JVM in the container, which a tight memory limit has to allow for briefly.
- The `fetch` output appears in the application container's log, not in a container of its own.
- The tools image is pulled by the volume the same way an init container's image is, so a private mirror and `--tools-image`
  work the same.
- Whether this makes pods Ready sooner is a measurement, not a claim: it is checked with the benchmark in
  `benchmarks/README.md` before the documentation says anything about speed.
