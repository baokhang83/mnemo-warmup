## Pod-to-Ready breakdown

**Application:** example (31 JARs, 20 MiB; archive 32 MB); openjdk version "21.0.13" 2026-10-20 LTS; limits cpu=1 memory=1Gi; tools delivery=image-volume.

**Machine:** Apple M5, 10 logical CPUs, 24 GiB, macOS 26.0. Docker gives the VM 10 CPUs and 7.8 GiB; kind node kindest/node:v1.35.8, 10 CPUs visible, docker limits {'nano_cpus': 0, 'cpu_quota': 0, 'cpu_shares': 0, 'cpuset_cpus': '', 'memory_bytes': 0}, cgroup cpu.max `max 100000`. Kubernetes v1.35.8 on arm64, 1 node.

**Registry:** in-cluster registry:2 over plain HTTP, request time of GET /v2/ from the node: median 0.6 ms, max 2.4 ms (30 samples); archive download from the node 3098 MB/s median (5 samples). This is a best case: the registry is one network hop away, not a remote one.

12 interleaved rounds per arm after one discarded warm-up round; commit 0b0fecc (uncommitted changes); run on 2026-11-13T09:58:58Z. Reproduce with `scripts/bench-cluster.sh --app example --restarts 12 --app-cpu 1 --app-memory 1Gi --tools-delivery image-volume`.

### Validity

| arm | restarts | archive used by the JVM | fetch found an archive |
|---|---|---|---|
| none (no mnemo-warmup) | 12 | 0 | 0 |
| nocache (mnemo-warmup on, no archive in the store) | 12 | 0 | 0 |
| cached (mnemo-warmup on, archive trained) | 12 | 12 | 12 |

### Coarse phases (seconds; median and min-max; pod timestamps have 1 s resolution)

| phase | none | nocache | cached |
|---|---|---|---|
| scheduling (created to startTime) | 0.0 (0.0-0.0) | 0.0 (0.0-1.0) | 0.0 (0.0-1.0) |
| install init container | n/a | n/a | n/a |
| fetch init container | n/a | n/a | n/a |
| app container created (initialized to started) | 0.5 (0.0-1.0) | 0.0 (0.0-1.0) | 0.0 (0.0-1.0) |
| app started to Ready | 2.0 (1.0-3.0) | 3.0 (2.0-3.0) | 2.0 (1.0-2.0) |
| **startTime to Ready** | 2.5 (2.0-3.0) | 3.0 (3.0-4.0) | 2.0 (1.0-3.0) |
| created to Ready | 2.5 (2.0-3.0) | 3.0 (3.0-4.0) | 2.0 (1.0-3.0) |

### Where the tools spend their time (milliseconds)

| step | none | nocache | cached |
|---|---|---|---|
| fetch: whole helper process (JVM start included) | n/a | 745 (711-834) | n/a |
| fetch: JDK detection | n/a | 22 (15-70) | 0 (0-0) |
| fetch: extract and hash the classpath | n/a | 86 (40-100) | 13 (8-18) |
| fetch: store lookup, download and digest check | n/a | 178 (125-193) | 25 (23-40) |
| fetch: tool JVM uptime at the end | n/a | 380 (355-478) | 0 (0-0) |
| run: whole helper process (JVM start included) | n/a | 0 (0-0) | 21 (18-32) |
| run: read manifest | n/a | n/a | 0 (0-0) |
| run: SHA-256 of the archive | n/a | n/a | n/a |
| run: JDK probe | n/a | n/a | n/a |
| run: hash the classpath | n/a | n/a | 20 (18-32) |
| run: tool JVM uptime at the end | n/a | 0 (0-0) | 0 (0-0) |
| application: self-reported start (Spring `Started ... in`; Jenkins and SonarQube: first log line to `fully up and running` / `SonarQube is operational`) | 1203 (1161-1297) | 1196 (1132-1286) | 808 (788-897) |

### Difference in startTime-to-Ready, per round (seconds)

Each round runs the three arms back to back, so the paired difference cancels slow drift. Median and min-max over rounds.

| comparison | difference |
|---|---|
| nocache - none (cost of the machinery, no benefit) | 0.5 (0.0-2.0) |
| cached - none (what a real pod gains or loses) | -1.0 (-1.0-1.0) |
| cached - nocache (what the archive itself buys) | -1.0 (-2.0-0.0) |

JVM-reported application start: 1203 ms without, 808 ms with the archive (+395 ms).
