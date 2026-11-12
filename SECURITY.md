# Security Policy

## Supported Versions

`mnemo-warmup` is currently in active pre-1.0 development (`alpha`). Security fixes are applied to the latest minor release line on `main`.

| Version | Supported |
| ------- | --------- |
| `0.2.x` | :white_check_mark: |
| `< 0.2.0` | :x: |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues or discussions.**

If you discover a security vulnerability in `mnemo-warmup` (including issues related to RBAC scope, credential handling, archive integrity verification, or container privilege escalation), please report it privately:

1. **GitHub Private Vulnerability Reporting (Preferred):** Go to the [**Security** tab](https://github.com) of this repository and click **"Report a vulnerability"** to open a private security advisory.
2. **Response Timeline:** You will receive an acknowledgment within **48 hours**. Once confirmed, we will work on a patch and coordinate a disclosure timeline with you before publishing a fix.

## Security Model & Scope

For details on `mnemo-warmup`'s least-privilege Kubernetes RBAC, secret isolation, registry credential boundaries, and fail-safe execution guarantees, see [docs/security.md](docs/security.md).

### Out of Scope
- **Application startup side effects:** By design, the asynchronous trainer `CronJob` executes a real instance of your application image inside the cluster (`spring-refresh` or `http-probe`). Operators are responsible for reviewing `mnemo-warmup inject --preview` and ensuring their training environment isolates production databases, external APIs, or scheduled jobs.
- **Registry storage growth:** `mnemo-warmup` intentionally omits registry delete permissions (`ecr:BatchDeleteImage`). Unbounded archive growth is managed by the operator's OCI registry lifecycle policy.
