# Design: end-to-end verification of the annotated adoption path (#59)

A new script, `scripts/e2e-kind-inject.sh`, drives a plain annotated Deployment through the real adoption path on a kind cluster: `helm install --post-renderer` with the injector, an OCI registry as the archive store, the native launcher, and the trainer CronJob the injector generates. The existing `scripts/e2e-kind.sh` keeps covering the chart path.

## Shapes

```mermaid
classDiagram
  class E2eScript {
    scripts/e2e-kind-inject.sh
    steps with ok and fail checks
    N repeats for start-to-Ready
  }
  class AnnotatedChart {
    examples/annotated-app
    plain Deployment + 3 annotations
    no mnemo-warmup subchart
  }
  class Injector {
    mnemo-warmup inject
    post-renderer
  }
  class KindCluster {
    local registry:2 on kind network
    app images loaded
  }
  class AppImages {
    temurin JRE image
    distroless java21 image
  }
  class Report {
    pass or fail per scenario
    median start-to-Ready, cached vs not
    unverified environments listed
  }
  E2eScript --> AnnotatedChart : helm install
  AnnotatedChart --> Injector : post-render
  E2eScript --> KindCluster
  KindCluster --> AppImages
  E2eScript --> Report
```

## Scenario flow

```mermaid
sequenceDiagram
  participant T as e2e script
  participant H as helm + injector
  participant K as kind cluster
  participant R as registry
  T->>H: preview (names only, no values)
  T->>H: install annotated chart
  H->>K: Deployment + CronJob + RBAC
  K-->>T: Ready, no archive (fetch: none)
  T->>K: run trainer job
  K->>R: push archive
  T->>K: restart pods
  K-->>T: Ready, class from archive, java is PID 1
  T->>K: delete pod (SIGTERM reaches java, no SIGKILL)
  T->>H: upgrade with changed JVM option
  K-->>T: Ready, uncached, logged reason
  T->>R: corrupt archive blob, restart pods
  K-->>T: Ready, digest rejected, uncached
  T->>R: stop registry, restart pods
  K-->>T: Ready, store unreachable, uncached
  T->>H: upgrade with enabled false
  K-->>T: original command restored, generated resources gone
```

## Scope notes

- Correctness is asserted in CI. Start-to-Ready is measured from pod conditions with repeats and reported, with no speed threshold (kind timings are noisy).
- Architectures: the local run is the host's (arm64 here). CI runs amd64 and arm64 runners; any environment not run is listed as unverified in the docs.
- Real EKS/ECR is not exercised by this script (no cluster available); the docs keep that marked unverified.
