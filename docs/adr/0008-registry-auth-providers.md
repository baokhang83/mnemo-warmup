# ADR 0008: registry login providers and ECR workload identity

Status: accepted (v0.2.5, issue 57)

## Context

`OciArchiveStore` held one fixed username and password read from a Docker-config file. ECR passwords expire after about 12 hours and are minted from AWS identity, so a fixed value cannot work for pods using IAM roles for service accounts. The core library has no runtime dependencies (constitution §4) and the fetch step must never stop an application from starting (§1, §3).

## Decision

- `RegistryAuth` supplies credentials each time the store needs a registry token and is told when the registry refuses them (`invalidate()`). A request that is still refused after authenticating triggers one refresh; if the provider returns the same login, the refusal is reported.
- `EcrRegistryAuth` exchanges AWS keys for an ECR login with a hand-written SigV4 signer, caches it until five minutes before expiry and re-requests it after `invalidate()`. Keys come from `AssumeRoleWithWebIdentity` (IRSA), optionally followed by `AssumeRole` for a second role. No AWS SDK.
- Selection happens in `Main.openStore`: a Docker-config entry for the registry wins; otherwise an ECR host with a pod web identity selects ECR; otherwise anonymous.
- Separate fetch and trainer identities are IAM and service-account configuration, not code.
- Transient AWS errors (connection failures, 429, 5xx) get up to three attempts; 4xx answers are not retried.

## Consequences

- We maintain about 300 lines of AWS client code and must keep them correct without an SDK's coverage. SigV4 is checked against AWS's published test vectors.
- STS is asked for JSON responses to avoid an XML parser; this is confirmed only against fakes until run on a live cluster.
- GKE and AKS providers, and EKS Pod Identity, plug in behind `RegistryAuth` and `AwsCredentialsSource` as follow-up work.
