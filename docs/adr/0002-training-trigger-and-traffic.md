# ADR 0002: Who trains, and training traffic

Status: accepted, revised after review (2026-11-10) · Resolves issue #12 · Unblocks #16, #17

## Context

An archive is written only when the training JVM exits cleanly, so a serving pod cannot also be
the trainer. Replicas start together, so several can find "no archive" at the same moment.
Constitution §1 applies: a missing archive or a failed trainer must never stop an application.

## Decision

**Trigger: a chart-owned CronJob that trains only when the store has no archive.**
- The chart renders a plain CronJob (not a Helm hook) with `concurrencyPolicy: Forbid`. Helm does
  not wait for it, so a failed or slow trainer cannot fail, time out, or roll back a release
  (`--wait`, `--atomic`). Fail-open (§1) therefore holds at release time as well as at pod start.
- The chart supplies only the image digest, cache type and traffic values. It never computes the
  fingerprint. The trainer computes it by running the same `mnemo-warmup` fingerprint code that
  `fetch` uses against the real image, so trainer and fetcher always agree.
- Each run: compute the fingerprint, check the store, and exit 0 if the archive exists.
- If the archive is missing, the trainer takes a Kubernetes `Lease` named after the fingerprint.
  Creating a Lease is atomic, so two releases or two CronJobs in one namespace cannot both train.
  A Lease that is not renewed expires, so a crashed trainer does not block training. The trainer's
  own ServiceAccount holds the Lease permission. App pods get no extra RBAC.
- Then it trains, uploads (ADR 0001), and releases the Lease. Failures log a reason and exit 0.
  The next scheduled run retries.
- Pods that start before the archive exists run uncached. Later rollouts use the archive.
- Cost: the first archive can take up to one schedule interval to start training (default
  `*/10 * * * *`, configurable). This is acceptable for an optimisation.

**Traffic: `spring-refresh` by default, `http-probe` opt-in.**
- `spring-refresh` (context refresh, then exit) is deterministic and needs no traffic.
- `http-probe` is enabled by chart values (URL list) to archive lazily loaded classes.
- A user warmup script is not supported in v0.2.0.

## Rejected

- **Helm hook Job named by fingerprint (first draft):** a failed hook fails the release; Helm's
  default delete policy removes a running hook of the same name; the chart cannot know the
  fingerprint at render time. Rejected after review.
- **First pod trains:** needs a distributed lock, a training mode in the serving path, and a pod
  that cannot serve while it trains. It also puts lock failures in the startup path.
- **Init container creates the Job:** works without Helm but gives every app pod Job-create RBAC.
- **User warmup script:** runs arbitrary code with cluster secrets. Revisit after v0.2.0.

## Consequences

- The Helm chart (#17) owns the CronJob and the `cacheType` and traffic values.
- The trainer command needs: fingerprint computation, an "exit 0 if archive exists" check, Lease
  acquire/release with expiry, and exit 0 on every failure (#15, #16).
- The chart needs a small Role for the trainer ServiceAccount (`leases` create/get/update/delete).
- Non-Helm users must create the Job themselves: documented, not automated.
