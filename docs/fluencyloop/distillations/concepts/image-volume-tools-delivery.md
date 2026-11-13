# Image-volume tools delivery

A Kubernetes image volume mounts the tools image into a pod. The launcher can fetch an archive and start the JVM without waiting for install and fetch init containers.

A pull Secret changes the fetch path. A separate fetch init container mounts the Secret and writes the cache. The application mounts the cache read-only and does not mount the Secret. The trainer also uses the tools image volume. [[warmup-cli/src/main/java/io/github/baokhang83/mnemo/warmup/cli/OfflineInjector.java]]

Image volumes require support from the Kubernetes feature gate and the runtime on each eligible node. Renderers cannot confirm those conditions offline. Init-container delivery remains the default and fallback. [[docs/configuration.md]]
