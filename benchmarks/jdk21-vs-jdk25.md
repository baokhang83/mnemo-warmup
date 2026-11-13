# JDK 21 AppCDS and JDK 25 AOT

On the Spring-heavy example, the JDK 25 AOT archive reduced the application's reported startup time more than JDK 21 AppCDS did. With a 1 CPU and 1 GiB application limit, the median saving against the same JDK without mnemo-warmup was 1,763 ms for AOT and 1,242 ms for AppCDS. Both cached deployments had a 5 s median pod start-to-Ready; the startup saving did not reduce that median with this delivery mode and local registry.

## Results

Each row is a separate run of 12 measured rounds per arm, after a discarded warm-up. The three arms within each run were interleaved: no mnemo-warmup, mnemo-warmup without a stored archive, and mnemo-warmup with a trained archive. Values below are medians. Times for the application come from its Spring startup log. Pod times use Kubernetes whole-second timestamps.

| Runtime and application limits | Application start, no tool / cached | Pod start-to-Ready, no tool / cached | Archive on disk and downloaded | Registry download median | Trainer container duration |
|---|---:|---:|---:|---:|---:|
| [JDK 21 AppCDS, no limits](results/20261113-study71-jdk21-unlimited/summary.md) | 1,328 / 693 ms | 3.0 / 3.0 s | 87,359,488 B | 24.4 ms | 4 s |
| [JDK 25 AOT, no limits](results/20261113-study71-jdk25-unlimited/summary.md) | 1,352 / 523 ms | 2.5 / 3.0 s | 109,445,120 B | 29.9 ms | 5 s |
| [JDK 21 AppCDS, 1 CPU / 1 GiB](results/20261113-study71-jdk21-limited/summary.md) | 3,142 / 1,901 ms | 5.0 / 5.0 s | 87,359,488 B | 24.1 ms | 4 s |
| [JDK 25 AOT, 1 CPU / 1 GiB](results/20261113-study71-jdk25-limited/summary.md) | 3,234 / 1,471 ms | 5.0 / 5.0 s | 109,379,584 B | 29.7 ms | 6 s |

Every cached run reported a fetch and archive use in all 12 measured pods. The download figure is the median of five direct blob downloads from the kind node to a local registry; `registry.txt` contains the byte counts and times. The archive blob is stored and downloaded without compression by this registry. Trainer duration is one observation per configuration, from the trainer container's start and finish timestamps. It includes work besides archive creation and upload. The generated trainer has no CPU or memory limits, including in the rows where the application is limited.

## Method and limits

The same built Spring Boot application (70 JARs, 59 MiB) ran on Temurin 21.0.13 and 25.0.5 on an Apple M5, arm64 kind cluster and Docker Desktop. `auto` selected AppCDS on JDK 21 and AOT on JDK 25. Both used init-container delivery and the local registry. The application had either no resource limits or requests and limits of 1 CPU and 1 GiB. The generated fetch init container and trainer had no resource limits. The raw pod objects, logs, environment, trainer timestamps and registry samples are in each linked results directory.

JDK versions and cache mechanisms change together, so this is not an isolated test of cache technology on one JDK. The four runs were sequential, not interleaved across JDKs. One limited JDK 21 no-cache start took 21.3 s; it remains in the raw data and the reported range. Kubernetes timestamps and the readiness probe have approximately one-second resolution. The local registry delivered blobs in roughly 24–30 ms; these measurements cannot predict remote registry costs or concurrent scale-out. Only one application, machine, architecture and archive per configuration were measured. The result supports further evaluation of `auto` for this workload, not a general claim that AOT always reduces pod readiness time.
