# Offline workload injection

**Before:** adopting the cache meant editing each Deployment's pod template by hand with the chart's helpers.
**After:** annotate a Deployment, and `mnemo-warmup inject` rewrites the rendered manifests: it wraps the launch, adds the init containers and volumes, and emits the trainer's CronJob, ServiceAccount, Role and RoleBinding in the same stream. It does not touch the cluster, a registry or any Secret ([[docs/adr/0007-offline-injection-contract.md]]).

What a user gets:
- A preview that names the discovered launch and everything the trainer copies from the workload (names only, never values), plus the changed paths.
- Reinjection that gives the same result, and a way out: setting the annotation to false, or removing it, restores the original launch and drops the generated resources. Edits to anything the injector owns are refused, not overwritten.
- A whole-stream guarantee: on any problem nothing is emitted and the exit is non-zero, so a deploy cannot half-apply.

Checked on a real kind cluster through `helm upgrade --install --post-renderer`: the app starts without an archive, the trainer trains once, a restarted pod uses the archive with `java` as PID 1, an unchanged upgrade changes nothing, disabling removes exactly what was generated, and re-enabling reuses the archive ([[§1]], [[§3]]). The script form of the post-renderer is Helm 3's interface; Helm 4 uses plugins and is not covered yet.
