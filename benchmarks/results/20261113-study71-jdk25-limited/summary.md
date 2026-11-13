## Pod-to-Ready breakdown

**Application:** spring-heavy (70 JARs, 59 MiB; archive 109 MB); openjdk version "25.0.5" 2026-10-20 LTS; limits cpu=1 memory=1Gi; tools delivery=init-container.

**Machine:** Apple M5, 10 logical CPUs, 24 GiB, macOS 26.0. Docker gives the VM 10 CPUs and 7.8 GiB; kind node kindest/node:v1.36.1@sha256:3489c7674813ba5d8b1a9977baea8a6e553784dab7b84759d1014dbd78f7ebd5, 10 CPUs visible, docker limits {'nano_cpus': 0, 'cpu_quota': 0, 'cpu_shares': 0, 'cpuset_cpus': '', 'memory_bytes': 0}, cgroup cpu.max `max 100000`. Kubernetes v1.36.1 on arm64, 1 node.

**Registry:** in-cluster registry:2 over plain HTTP, request time of GET /v2/ from the node: median 0.6 ms, max 2.6 ms (30 samples); archive download from the node 3685 MB/s median (5 samples). This is a best case: the registry is one network hop away, not a remote one.

12 interleaved rounds per arm after one discarded warm-up round; commit 631adf4; run on 2026-11-13T16:16:33Z. Reproduce with `scripts/bench-cluster.sh --app spring-heavy --jdk 25 --restarts 12 --app-cpu 1 --app-memory 1Gi --tools-delivery init-container`.

### Validity

| arm | restarts | archive used by the JVM | fetch found an archive |
|---|---|---|---|
| none (no mnemo-warmup) | 12 | 0 | 0 |
| nocache (mnemo-warmup on, no archive in the store) | 12 | 0 | 0 |
| cached (mnemo-warmup on, archive trained) | 12 | 12 | 12 |

### Coarse phases (seconds; median and min-max; pod timestamps have 1 s resolution)

| phase | none | nocache | cached |
|---|---|---|---|
| scheduling (created to startTime) | 0.0 (0.0-0.0) | 0.0 (0.0-0.0) | 0.0 (0.0-0.0) |
| install init container | n/a | 0.0 (0.0-0.0) | 0.0 (0.0-0.0) |
| fetch init container | n/a | 0.5 (0.0-1.0) | 0.0 (0.0-1.0) |
| app container created (initialized to started) | 0.0 (0.0-1.0) | 0.0 (0.0-1.0) | 0.0 (0.0-1.0) |
| app started to Ready | 4.0 (4.0-5.0) | 4.0 (4.0-5.0) | 3.0 (2.0-3.0) |
| **startTime to Ready** | 5.0 (4.0-5.0) | 6.0 (6.0-7.0) | 5.0 (4.0-6.0) |
| created to Ready | 5.0 (4.0-5.0) | 6.0 (6.0-7.0) | 5.0 (4.0-6.0) |

### Where the tools spend their time (milliseconds)

| step | none | nocache | cached |
|---|---|---|---|
| fetch: whole helper process (JVM start included) | n/a | 588 (581-595) | n/a |
| fetch: JDK detection | n/a | 17 (16-19) | 0 (0-0) |
| fetch: extract and hash the classpath | n/a | 103 (102-105) | 32 (28-50) |
| fetch: store lookup, download and digest check | n/a | 72 (71-76) | 66 (63-70) |
| fetch: tool JVM uptime at the end | n/a | 272 (268-276) | 0 (0-0) |
| run: whole helper process (JVM start included) | n/a | 0 (0-0) | 68 (61-111) |
| run: read manifest | n/a | n/a | 0 (0-0) |
| run: SHA-256 of the archive | n/a | n/a | n/a |
| run: JDK probe | n/a | n/a | n/a |
| run: hash the classpath | n/a | n/a | 68 (61-111) |
| run: tool JVM uptime at the end | n/a | 0 (0-0) | 0 (0-0) |
| application: self-reported start (Spring `Started ... in`; Jenkins and SonarQube: first log line to `fully up and running` / `SonarQube is operational`) | 3234 (3192-3374) | 3268 (3200-3296) | 1471 (1408-1498) |

### Difference in startTime-to-Ready, per round (seconds)

Each round runs the three arms back to back, so the paired difference cancels slow drift. Median and min-max over rounds.

| comparison | difference |
|---|---|
| nocache - none (cost of the machinery, no benefit) | 1.0 (1.0-3.0) |
| cached - none (what a real pod gains or loses) | 0.0 (-1.0-1.0) |
| cached - nocache (what the archive itself buys) | -1.0 (-3.0-0.0) |

JVM-reported application start: 3234 ms without, 1471 ms with the archive (+1763 ms).
