# ADR 0005: The `train` command and the shape of the Helm chart

Status: accepted (2026-11-10) · Resolves issue #17 · Implements ADR 0002 · Builds on ADR 0003 and 0004

## Context

ADR 0002 decided that a CronJob computes the fingerprint, checks the store, takes a Lease, trains, uploads and
exits 0. No command did that sequence (`build` and `push` are separate), and the project plan says injecting the
cache into workloads belongs to mnemo-jvm-warden, not to a second controller here.

## Decision

**A `train` command** (`TrainingOrchestrator`): skip if the store has the fingerprint; otherwise take the lock,
check again, train, upload, release. Every outcome (`ALREADY_STORED`, `TRAINED`, `LOCKED_BY_OTHER`, `FAILED`) is
reported and exits 0, because the trainer is not on the pod start path and the next scheduled run retries;
`--strict` makes `FAILED` exit 1. Usage errors exit 2 so a misconfigured CronJob is visible. It requires the extracted
layout (ADR 0003). With `spring-refresh` it treats an extracted Spring Boot JAR as a Spring Boot app.

**An entry must be complete, not just present.** `ArchiveStore.inspect` (cheap, no archive download) checks that the
tag's manifest is ours, the config blob matches its digest and describes this fingerprint, and the archive blob exists
with the declared size. A tag whose entry is incomplete is `BROKEN`: the trainer trains again and `put` replaces it
(re-uploading missing blobs, rewriting the tag), so a damaged entry is repaired instead of being reported as done
forever. Same-length damage inside the archive is not detected here; `fetch` verifies the bytes before any pod uses them.
If the registry makes tags immutable, the rewrite is refused and the run reports `FAILED`: disable tag immutability for
the archive repository, or delete the broken tag.

**The lock is a Kubernetes `Lease`** named `mnemo-warmup-<fingerprint hex>` (`KubernetesLeaseLock`). Create is atomic;
an unrenewed lease expires and is taken over with an update that carries the resourceVersion, so one taker wins;
the holder renews every third of the duration. It trusts itself for only two thirds of the duration after the last
successful renewal (monotonic clock), so a partition makes it stop claiming the lease before it can expire and be taken;
a definite loss (lease deleted, another holder, a conflicting update) is permanent, a transient API error is not. It does
not publish if the lock is lost. The lock is advisory: if it is lost during the upload, a second trainer may publish too,
which is harmless because each entry is a digest-verified manifest and archive pair written by one atomic tag update, and the
trainer reports it. The client uses the
JDK HTTP client and the pod's service-account token (no dependency), pinned to HTTP/1.1. `--lock none` disables it.

**The chart owns the trainer, not your Deployment.** `deploy/helm/mnemo-warmup` renders a ServiceAccount, a Role and
RoleBinding (`get create update delete` on `leases`, nothing else) and the trainer CronJob (`concurrencyPolicy: Forbid`,
not a Helm hook). It also ships named templates that take the chart's values map as context and that the application's
own chart includes when this chart is its (aliased) dependency: `mnemo-warmup.volumes`, `installInitContainer`,
`fetchInitContainer`, `volumeMounts`, `env`, `command`, `appImage`. The fetch init container and the trainer are rendered
from the same helper, so they pass identical `--jar`, `--jvm-arg`, `--image-digest`, `--cache-type` and `--store`.
Registry credentials are two separately mounted `dockerconfigjson` Secrets: `store.pullSecret` (fetch) and `store.pushSecret` (trainer).

## Rejected

- **The chart also renders the application Deployment:** duplicates a Deployment and drifts from what teams run.
- **Training without a lock:** duplicates work; ADR 0002 chose the Lease after review.
- **A Kubernetes client library:** a large dependency tree for four REST calls.

## Consequences

- The application's JVM-affecting environment (`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`) and JVM options must be
  repeated in `app.env` / `app.jvmArgs`; the chart cannot read them from your Deployment. A mismatch means no archive match, not a failure.
- The first archive can take up to one schedule interval (default 10 minutes).
- The default tools image tag (`0.2.0`) exists only after the first release tag is published.
- Validated with `scripts/test-chart.sh` (lint, rendering, a parent-chart fixture, optional server-side dry-run) and on a
  kind cluster for RBAC and the Lease API. An end-to-end run of the whole chain on a cluster is issue #18.
