# mnemo-warmup

Java applications on Kubernetes repeat class-loading work on each start. mnemo-warmup trains an AppCDS or JVM AOT cache for a workload and stores it in an OCI registry. A fingerprint identifies the JDK, application and launch settings that the cache must match.

The workload receives the tools through init containers or, on a verified cluster, an image volume. A fetch step retrieves and checks the archive before the application starts. With an image volume, the application launcher fetches inline unless a pull Secret requires a separate fetch init container. [[image-volume-tools-delivery]]

The archive is an optimisation. A missing, invalid or unavailable archive results in a normal JVM start. The trainer runs separately from the application; a cache problem must not prevent the application from starting. [[§1]] [[§2]] [[§3]]
