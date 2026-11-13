# Measured JVM archive compatibility

Before this change, the fingerprint treated `SoftMaxHeapSize` as an archive compatibility boundary without measurements from a real application. Now the Java and Go launch paths omit that soft heap target from the fingerprint. They retain maximum and initial heap, collector, RAM-percentage, and pointer-layout settings as conservative inputs.

The reproducible study trained and ran an extracted Spring Boot application on Temurin JDK 21 and 25. It checked whether the application class actually loaded from the archive, including reverse-direction flag changes and cgroup limits. Small heap changes, soft heap target changes, and tested cgroup changes reused the archive. Large maximum heaps, collector changes that altered layout, and compressed-reference changes could cause a clean fallback. A cache miss continues to start the application without an archive.

The study covers one application, two JDK releases, ARM64, and one launch per case. It does not establish a universal JVM compatibility matrix. Automatic profile selection for a heap size derived from the cgroup limit remains unsupported; explicit profiles can be trained and selected, and a missing match falls back to an uncached launch.
