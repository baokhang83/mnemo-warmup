# Optional node-local archive cache

Previously, every new pod fetched its own archive from the registry. An opted-in workload can now use a node-local service to share verified archive blobs between pods on the same node. Concurrent requests for the same repository-scoped digest share a fill. The bounded store evicts inactive entries when space is needed. [[launcher/nodecache/store.go]]

Each request requires a reviewed Kubernetes service-account token, an explicit repository grant and proof of current registry access. The native fetcher verifies the returned bytes and falls back to the registry when the service fails. The Java fallback does not use the service. A failure to mount the projected token can stop a pod before the fetcher runs. [[launcher/nodecache/handler.go]] [[launcher/nodecache_client.go]]

The service and chart passed a single-node functional check. The full workload path and the effect on cloud scale-out remain to be measured.
