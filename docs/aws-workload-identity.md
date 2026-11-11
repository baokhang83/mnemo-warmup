# Amazon ECR with EKS workload identity

mnemo-warmup can log in to an Amazon ECR archive store with the identity of the pod, so no registry password is stored in a Secret. It uses IAM roles for service accounts (IRSA) on EKS. GKE and AKS are not supported yet, and EKS Pod Identity is not supported yet.

This guide has not been verified against a live EKS cluster and ECR registry in this repository's CI. The unit tests use local fakes for STS, ECR and the registry, and the signing code is checked against AWS's published SigV4 test vectors. Run the checklist at the end on your cluster before relying on it.

## How it works

1. EKS injects `AWS_ROLE_ARN` and `AWS_WEB_IDENTITY_TOKEN_FILE` into a pod whose service account is annotated with an IAM role.
2. When the registry host is an ECR host (`<account>.dkr.ecr.<region>.amazonaws.com`, also `ecr-fips` and the `.cn` suffix), mnemo-warmup reads the token file and calls STS `AssumeRoleWithWebIdentity` in the same region. The region comes from the registry host.
3. It signs an ECR `GetAuthorizationToken` call with the temporary keys, caches the resulting login for its roughly 12-hour lifetime, and refreshes it five minutes before expiry. A registry that rejects a login mid-run triggers one refresh.
4. No credential is ever written to a log or error message.

A `--registry-auth` file (or `$MNEMO_WARMUP_REGISTRY_AUTH`) with an entry for the registry still takes precedence. With neither a file entry nor a web identity, access is anonymous as before.

## Set up two roles

Use a pull-only role for the application's `fetch` and a push role for the trainer, so a compromised application cannot overwrite archives.

Trust policy for each role (replace the placeholders):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<account>:oidc-provider/<oidc-issuer-host-and-path>" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": { "StringEquals": {
      "<oidc-issuer-host-and-path>:aud": "sts.amazonaws.com",
      "<oidc-issuer-host-and-path>:sub": "system:serviceaccount:<namespace>:<service-account>"
    } }
  }]
}
```

Permissions for the **pull** role:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"],
      "Resource": "arn:aws:ecr:<region>:<account>:repository/<archive-repository>" }
  ]
}
```

The **push** role adds `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload` and `ecr:PutImage` on the same repository. ECR does not create repositories on push: create `<archive-repository>` first.

## Attach the roles

- **Trainer (Helm chart):** annotate the trainer's service account with the push role.

  ```yaml
  trainer:
    serviceAccount:
      annotations:
        eks.amazonaws.com/role-arn: arn:aws:iam::<account>:role/mnemo-warmup-push
  ```

- **Application (fetch):** the `fetch` init container runs in the application's pod, so it uses the application's service account. Annotate that service account with the pull role. Leave the store's `pullSecret` and `pushSecret` empty.

## Optional second role

To start from one identity and act as another (a role in a different account, for example), set `MNEMO_WARMUP_AWS_ROLE_ARN` on the container. mnemo-warmup then calls STS `AssumeRole` with the web identity's keys before asking ECR for a login. The pod's role needs `sts:AssumeRole` on the target role, and the target's trust policy must name the pod's role.

## Other settings

| Variable | Meaning |
|----------|---------|
| `AWS_ROLE_SESSION_NAME` | STS session name (default `mnemo-warmup`). |
| `AWS_ENDPOINT_URL_STS`, `AWS_ENDPOINT_URL_ECR` | Override the endpoints, for VPC endpoints or tests. Defaults are the regional `sts.<region>` and `api.ecr.<region>` hosts, with `.com.cn` in China. |

## What happens on failure

`fetch` treats every login problem (missing token file, denied permission, STS or ECR down) as "no archive": it prints the reason to stderr and the application starts without a cache, exit code 0 (`--strict` exits 1). Transient errors (connection failures, HTTP 429 and 5xx) are retried three times; permission errors are not.

## Verify on your cluster

1. `kubectl exec` into a pod using the annotated service account and confirm `AWS_ROLE_ARN` and `AWS_WEB_IDENTITY_TOKEN_FILE` are set.
2. Run the trainer once and check the archive appears in ECR under the tag `fp-<hex>`.
3. Start the application and check the `fetch` log for `Archive:` rather than a `fetch:` note.
4. Remove `ecr:PutImage` from the push role and confirm the trainer reports the denial and the application still starts.

Not yet confirmed outside local fakes: the STS JSON response format (we send `Accept: application/json`) and ECR's registry behaviour on token expiry.
