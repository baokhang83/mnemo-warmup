# Node-local archive cache

A node-local archive cache is an optional service that lets pods on one Kubernetes node reuse an OCI archive blob. It stores bytes by registry, repository and digest, checks their integrity, coordinates concurrent fills and evicts inactive entries under a configured limit. [[launcher/nodecache/store.go]]

The service reviews each workload identity, checks an operator grant and verifies current registry read access before returning a blob. The native fetcher checks the returned bytes again. If the service cannot provide them, the fetcher tries the registry directly. [[launcher/nodecache/handler.go]] [[launcher/nodecache_client.go]]
