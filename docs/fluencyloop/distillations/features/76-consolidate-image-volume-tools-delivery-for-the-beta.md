# Image-volume delivery for the beta

Image-volume delivery now supports archive pull Secrets. When a Secret is configured, a fetch init container mounts it and fills the shared cache. The application mounts the cache without mounting the Secret. Without a pull Secret, the launcher continues to fetch inside the application container. [[image-volume-tools-delivery]]

The default remains init-container delivery. The preview and chart notes ask operators to verify the feature gate and runtime on eligible nodes before selecting an image volume. A small Spring Boot workload adds a measured data point; its cached and plain pod-readiness ranges overlap. [[benchmarks/results/20261113-example-1cpu-1gi-imagevolume/summary.md]]
