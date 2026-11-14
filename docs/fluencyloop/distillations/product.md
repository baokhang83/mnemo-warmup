# mnemo-warmup

Java applications on Kubernetes repeat class-loading work on each start. mnemo-warmup trains an AppCDS or JVM AOT cache for a workload and stores it in an OCI registry. A fingerprint identifies the JDK, application and launch settings that the cache must match.

The workload receives the tools through init containers or, on a verified cluster, an image volume. A fetch step retrieves and checks the archive before the application starts. With an image volume, the application launcher fetches inline unless a pull Secret or node-local archive cache requires a separate fetch init container. An optional node-local service can share verified archive blobs between pods on the same node; the fetcher falls back to the registry when the service is unavailable. [[image-volume-tools-delivery]]

The archive is an optimisation. A missing, invalid or unavailable archive results in a normal JVM start. The trainer runs separately from the application. The fetcher falls back on cache errors; Kubernetes can still prevent a pod from starting if it cannot mount the projected cache token. [[§1]] [[§2]] [[§3]]
