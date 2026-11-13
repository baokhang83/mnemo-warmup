# Preview image cache warning

Image defaults live in an OCI image config, outside the rendered Kubernetes manifest. The preview reads the image manifest and its bounded runtime config blob to look for known JVM cache flags and supported framework markers. It does not fetch image layers.

The inspection is best effort. Private or unreachable registries leave cache presence unknown and do not block preview. The warning is evidence of a possible cache, not proof that the running process uses it: Kubernetes command, argument, and environment settings can override image defaults. It also does not determine whether the image cache and cluster-trained archive can run together. See [operations](../../../operations.md#images-with-a-build-time-cache) for the known JDK 25 compatibility limit.
