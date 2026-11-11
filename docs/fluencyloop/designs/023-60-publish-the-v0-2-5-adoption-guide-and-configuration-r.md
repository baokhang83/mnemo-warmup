# Design: v0.2.5 adoption guide and configuration reference (#60)

Documentation only. The README is held on the released 0.2.0 chart path until v0.2.5 is tagged. The new pages and a ready-to-swap README are written now, with every command checked against the CLI built from `main`.

## Shapes

```mermaid
classDiagram
  class README {
    value, alpha status
    runnable example
    quickstart, preview, deploy
    links only, no reference detail
  }
  class Configuration {
    docs/configuration.md
    annotations and overrides
    requirements, Spring Boot prep
  }
  class RegistryAuth {
    docs/registry-auth.md
    dockerconfig Secrets
    ECR link
  }
  class Operations {
    docs/operations.md
    cost, measurement
    fail-open limits, removal
  }
  class HelmChart {
    docs/helm-chart.md
    the existing chart wiring, moved
  }
  class Existing {
    offline-injector.md
    aws-workload-identity.md
    archive-retention.md
    reference.md
  }
  class ReleaseKit {
    docs/release/README.v0.2.5.md
    docs/release/v0.2.5.md checklist
  }
  README --> Configuration
  README --> RegistryAuth
  README --> Operations
  README --> HelmChart
  Configuration --> Existing
  RegistryAuth --> Existing
  Operations --> Existing
  ReleaseKit ..> README : swapped in at release
```

## Reader flow (quickstart)

```mermaid
sequenceDiagram
  participant U as Operator
  participant R as README
  participant C as helm + injector
  participant K as Cluster
  U->>R: read value, status, requirements
  U->>R: run the example (kind)
  U->>C: annotate Deployment (3 annotations)
  U->>C: helm template | inject --preview
  C-->>U: workload, copied names, changed paths
  U->>C: helm upgrade --post-renderer
  C->>K: Deployment + trainer CronJob
  U->>K: run trainer job, restart, read fetch and app logs
  Note over U,K: measure start-to-Ready before trusting it
```

## Scope notes

- The draft promised two annotations; the shipped injector requires three (`exit-mode` is mandatory). The docs say three and record the gap.
- Anything not shipped or not run is marked unverified (JDK 25 via the injector, real EKS/ECR, Helm 4, CI arm64 job).
