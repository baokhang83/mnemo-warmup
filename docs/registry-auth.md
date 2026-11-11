# Registry authentication

The tool talks to the **archive** registry from inside pods: the `fetch` init container needs **pull** access, the trainer **pull and push** access. These credentials are separate from the ones your cluster uses to pull container images; image-pull credentials are used by the kubelet and are not visible to the containers.

Use separate pull and push credentials. A read-only credential should not be able to overwrite archives.

## How a login is chosen

For a registry, in this order:

1. **A Docker-config entry for the registry host**, from the mounted Secret (`--registry-auth`, or `$MNEMO_WARMUP_REGISTRY_AUTH`). It is used as is.
2. Otherwise, for an **Amazon ECR** host in a pod that has an EKS web identity, a login obtained from that identity ([below](#eks-and-ecr)).
3. Otherwise the access is anonymous.

If a login is missing or refused, `fetch` logs the reason and the pod starts without a cache; the trainer logs it and exits 0.

## Docker-config Secrets

Create `kubernetes.io/dockerconfigjson` Secrets in the workload's namespace **before** you deploy. A pod that mounts a Secret that does not exist cannot start.

```bash
kubectl -n my-namespace create secret docker-registry warmup-pull \
  --docker-server=registry.example.com --docker-username=... --docker-password=...
kubectl -n my-namespace create secret docker-registry warmup-push \
  --docker-server=registry.example.com --docker-username=... --docker-password=...
```

Reference them from the Deployment's pod template:

```yaml
warmup.mnemo.io/pull-secret: "warmup-pull"
warmup.mnemo.io/push-secret: "warmup-push"
```

Basic and Bearer-token registries are supported. Credential helpers are not.

## EKS and ECR

On EKS, give the application's service account a **pull** role and the trainer's service account a **push** role through IAM roles for service accounts (IRSA). The tool exchanges the pod's web identity for an ECR login, caches it until shortly before it expires and refreshes it, including mid-run. No password is stored anywhere. The full setup, with the trust and permission policies and a verification checklist, is in the [EKS and ECR guide](aws-workload-identity.md).

For the annotation path, the trainer's CronJob uses its own generated ServiceAccount. Annotate it with the push role after the first render (for example with a Helm post-renderer or a Kustomize patch), because the injector does not set service account annotations. The application's own service account carries the pull role.

Verified so far: the ECR path against local fakes and AWS's SigV4 test vectors. **Not yet verified on a live EKS and ECR.**

## Other registries

Docker-config Secrets work with registries that accept Basic or Bearer-token login. GKE, AKS and EKS Pod Identity are not implemented; the provider boundary they would plug into is described in [ADR 0008](adr/0008-registry-auth-providers.md).
