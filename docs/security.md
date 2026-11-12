# Security and blast radius

How `mnemo-warmup` isolates permissions, handles secrets and credentials, and ensures a cache failure never disrupts a running workload.

## Least-privilege Kubernetes RBAC

- **No cluster-wide permissions.** `mnemo-warmup` requires no `cluster-admin`, custom controller, CRD, or mutating admission webhook.
- **Namespace-scoped trainer role.** For each opted-in Deployment, the injector generates a dedicated `ServiceAccount`, `Role`, and `RoleBinding` inside the workload's own namespace.
- **Leases only.** The trainer's `Role` grants `get`, `create`, `update`, and `delete` permissions strictly on `coordination.k8s.io/leases` so concurrent training jobs coordinate safely without overlapping. Application pods require no extra RBAC.

## Secret and configuration isolation

- **Names only, never values.** When rendering the trainer `CronJob`, the offline injector copies environment variable names, `envFrom` Secret/ConfigMap references, and volume mount paths from your Deployment. Secret values are never read, printed in `--preview` output, or written into `mnemo-warmup.manifest.json`.
- **No `pull-secret` exposure in `image-volume` mode.** Because `tools-delivery: image-volume` runs the fetch step inside the application container's entrypoint, the injector explicitly blocks combining `image-volume` with `warmup.mnemo.io/pull-secret` so application code cannot read the mounted registry credential Secret.
- **Log redaction.** Registry tokens, temporary STS credentials, and authorization headers are never written to stdout, stderr, or error messages.

## Scoped registry credentials

- **Separation of read and write access.** Application pods only ever need read-only (`pull`) permissions on the archive repository. Only the asynchronous trainer `CronJob` uses write (`push`) credentials.
- **Dedicated archive repository.** Store archives in a dedicated OCI repository separate from your application images, and omit delete permissions (`ecr:BatchDeleteImage`) from both the fetch and trainer roles. See [archive retention](archive-retention.md) for bounding storage safely via registry lifecycle policies.

## Execution safety and fail-safe guarantees

- **Distroless and shell-free support.** Every injected step executes through the static `mnemo-launcher` binary, requiring no `/bin/sh` or package manager inside your application image.
- **Never fatal on cache failure.** The launcher relies on the JVM's default `-Xshare:auto` behavior and never passes `-Xshare:on`. If the archive is missing, corrupted (SHA-256 mismatch), expired, or unreachable, the launcher logs the reason and starts the JVM normally with exit code `0`.
