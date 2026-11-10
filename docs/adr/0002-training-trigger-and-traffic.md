# ADR 0002: Who trains, and training traffic

Status: accepted (2026-11-10) · Resolves issue #12 · Unblocks #16, #17

## Context

An archive is written only when the training JVM exits cleanly, so a serving pod cannot also be
the trainer. Replicas start together, so several can find "no archive" at the same moment.
Constitution §1 applies: a missing archive or a failed trainer must never stop an application.

## Decision

**Trigger: one Job per fingerprint, created by a Helm hook.**
- The chart renders a post-install/upgrade hook Job named `train-<fingerprint>`. Kubernetes
  rejects a second Job with the same name, which de-duplicates concurrent attempts without a lock.
- The Job first checks the archive store. If the fingerprint exists, it exits 0. Re-running is safe.
- It runs in the real cluster with the application's config and secrets, then uploads the archive
  (store: ADR 0001, on PR #36).
- Pods that start before the archive exists run uncached. Later rollouts use the archive.
- Application pods need no permission to create Jobs.

**Traffic: `spring-refresh` by default, `http-probe` opt-in.**
- `spring-refresh` (context refresh, then exit) is deterministic and needs no traffic.
- `http-probe` is enabled by chart values (URL list) to archive lazily loaded classes.
- A user warmup script is not supported in v0.2.0.

## Rejected

- **First pod trains:** needs a distributed lock, a training mode in the serving path, and a pod
  that cannot serve while it trains. It also puts lock failures in the startup path.
- **Init container creates the Job:** works without Helm but gives every app pod Job-create RBAC.
- **User warmup script:** runs arbitrary code with cluster secrets. Revisit after v0.2.0.

## Consequences

- The Helm chart (#17) owns the hook Job and the `cacheType` and traffic values.
- The trainer command needs an "exit 0 if archive exists" check (#15, #16).
- Non-Helm users must create the Job themselves: documented, not automated.
