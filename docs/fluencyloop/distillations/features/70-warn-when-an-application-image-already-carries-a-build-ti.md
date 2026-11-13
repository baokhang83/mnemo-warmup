# Preview warnings for image caches

Before this change, `inject --preview` could explain the Kubernetes manifest transformation but could not see JVM cache flags stored in an application image. It now reads the OCI manifest and runtime config for opted-in workloads, then reports known cache flags or supported buildpack cache markers. It never downloads layers or prints launch and environment values. A registry failure leaves preview usable and reports that cache presence is unknown; normal injection stays offline.

The warning identifies a possible overlap. It does not resolve which cache the running JVM will use. Kubernetes can replace image command, arguments, and environment defaults, and mixed cache types can fail at JVM startup. On OpenJDK 25.0.1, an AOT cache supplied through `JAVA_TOOL_OPTIONS` conflicted with a command-line AppCDS archive. See [operations](../../../operations.md#images-with-a-build-time-cache) for the tested limit.
