# Experimental node cache chart

This chart installs the optional `mnemo-nodecache` service as a DaemonSet and a ClusterIP Service. It requires Kubernetes 1.26 or newer. The Service uses `internalTrafficPolicy: Local` so a client pod reaches an endpoint on its own node; a node without an endpoint has no cache endpoint. Verify that the cluster networking implementation honors this policy.

**Development status:** the native fetcher can try the service and fall back to the registry, but this chart and integration have not yet been verified on a cluster. Registry redirects and automatic registry-token exchange are not supported. The Java fallback path does not use the node cache.

The DaemonSet uses a pod-local `emptyDir`, not a host directory. Restarting or replacing the cache pod loses that node's cache. It runs as a non-root user with a read-only root filesystem. The ServiceAccount can create Kubernetes TokenReviews; it does not receive permissions to read Kubernetes Secrets through the API. The kubelet mounts the configuration Secret into the cache pod.

## Configuration

Create a Secret in the chart namespace with `config.json`, a TLS server certificate and key, and any repository credential and CA files referenced by `config.json`. The certificate must cover the Service DNS name used by clients, for example `<release>-nodecache.<namespace>.svc`. Clients will need the issuing CA. Keep the source credential read-only and restricted to its repository.

Example `config.json` (replace the registry, repository, subject, audience and file names for the installation):

```json
{
  "listenAddress": ":8443",
  "tlsCertificateFile": "/etc/mnemo-nodecache/tls.crt",
  "tlsKeyFile": "/etc/mnemo-nodecache/tls.key",
  "kubernetesAPI": "https://kubernetes.default.svc",
  "kubernetesCAFile": "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
  "reviewerTokenFile": "/var/run/secrets/kubernetes.io/serviceaccount/token",
  "tokenAudience": "mnemo-nodecache",
  "registryCAFile": "/etc/mnemo-nodecache/registry-ca.pem",
  "cacheDirectory": "/var/cache/mnemo/blobs",
  "maxBlobBytes": 536870912,
  "maxTotalBytes": 1610612736,
  "requestTimeoutSeconds": 120,
  "repositories": [
    {
      "registry": "registry.example.com",
      "repository": "team/archive",
      "credentialFile": "/etc/mnemo-nodecache/source.auth",
      "subjects": ["system:serviceaccount:apps:myapp"]
    }
  ]
}
```

The source credential file contains a single `Basic` or `Bearer` HTTP authorization value. An empty `credentialFile` selects anonymous source access. The cache rereads the source credential on each miss; an external process must rotate expiring credentials. The tools image may not contain the registry's CA bundle, so provide `registryCAFile` when required. The Kubernetes CA and reviewer token are supplied by the cache pod's ServiceAccount mount.

Create the Secret from files without putting credential values into shell history. Then render with an explicit image tag containing the cache executable:

```sh
kubectl -n cache-system create secret generic nodecache-config \
  --from-file=config.json=./config.json \
  --from-file=tls.crt=./tls.crt --from-file=tls.key=./tls.key \
  --from-file=registry-ca.pem=./registry-ca.pem --from-file=source.auth=./source.auth
helm template cache deploy/helm/mnemo-nodecache --namespace cache-system \
  --set image.tag=YOUR_TEST_TAG --set configurationSecret=nodecache-config
```

The configured listen port must be 8443 to match this chart. The cache reads configuration and TLS certificates at startup; restart the DaemonSet after changing them. Source credential file updates are read on later misses. Set `maxTotalBytes` below `cacheVolumeSize` and the pod's ephemeral-storage limit to allow room for filesystem metadata and other pod storage. The service evicts least-recently-used entries only when no request is reading them. Its in-memory access order is reset to file modification order after a process restart. The store assumes one process owns its private directory; do not mount it into application pods or share it between cache processes.

Every request needs a projected service-account token with the configured audience and an explicit subject/repository grant. It also needs proof of current registry read access for the requested blob. The cache validates that proof against the registry before serving a hit or miss. The current registry check and download accept direct responses only; they reject redirects. The native client falls back to a direct registry download when the cache request fails.

To opt a Deployment in through the injector, create a ConfigMap named `nodecache-ca` in its namespace with the service CA under `ca.crt`, then add these pod-template annotations using the actual Service URL and configured audience:

```yaml
warmup.mnemo.io/node-cache-url: "https://cache-nodecache.cache-system.svc:8443"
warmup.mnemo.io/node-cache-ca-configmap: "nodecache-ca"
warmup.mnemo.io/node-cache-audience: "mnemo-nodecache"
```

The fetch init container receives the projected token and CA; the application and trainer do not. The fetcher sends its registry authorization to the trusted cache service over TLS; a Basic authorization value can contain long-lived credentials. The CA ConfigMap is optional at the Kubernetes volume level so a missing CA file causes direct fallback. If Kubernetes cannot project the service-account token, the pod can remain blocked before the fetcher starts.
