## Pod-to-Ready breakdown

**Application:** spring-heavy (69 JARs, 59 MiB; archive 87 MB); openjdk version "21.0.13" 2026-10-20 LTS; limits cpu=none memory=none.

**Machine:** Apple M5, 10 logical CPUs, 24 GiB, macOS 26.0. Docker gives the VM 10 CPUs and 7.8 GiB; kind node kindest/node:v1.36.1@sha256:3489c7674813ba5d8b1a9977baea8a6e553784dab7b84759d1014dbd78f7ebd5, 10 CPUs visible, docker limits {'nano_cpus': 0, 'cpu_quota': 0, 'cpu_shares': 0, 'cpuset_cpus': '', 'memory_bytes': 0}, cgroup cpu.max `max 100000`. Kubernetes v1.36.1 on arm64, 1 node.

**Registry:** in-cluster registry:2 over plain HTTP, request time of GET /v2/ from the node: median 0.6 ms, max 2.3 ms (30 samples); archive download from the node 3476 MB/s median (5 samples). This is a best case: the registry is one network hop away, not a remote one.

12 interleaved rounds per arm after one discarded warm-up round; commit 7862063 (uncommitted changes); run on 2026-11-11T07:40:06Z. Reproduce with `scripts/bench-cluster.sh --app spring-heavy --restarts 12`.

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
| fetch init container | n/a | 0.0 (0.0-1.0) | 1.0 (0.0-1.0) |
| app container created (initialized to started) | 0.0 (0.0-1.0) | 0.0 (0.0-1.0) | 0.0 (0.0-1.0) |
| app started to Ready | 2.0 (1.0-3.0) | 2.0 (2.0-3.0) | 2.0 (2.0-2.0) |
| **startTime to Ready** | 3.0 (2.0-3.0) | 4.0 (3.0-5.0) | 4.0 (3.0-5.0) |
| created to Ready | 3.0 (2.0-3.0) | 4.0 (3.0-5.0) | 4.0 (3.0-5.0) |

### Where the tools spend their time (milliseconds)

| step | none | nocache | cached |
|---|---|---|---|
| fetch: whole helper process (JVM start included) | n/a | 578 (573-614) | 790 (756-810) |
| fetch: JDK detection | n/a | 16 (14-27) | 16 (14-18) |
| fetch: extract and hash the classpath | n/a | 58 (54-62) | 58 (55-63) |
| fetch: store lookup, download and digest check | n/a | 77 (72-88) | 279 (266-294) |
| fetch: tool JVM uptime at the end | n/a | 228 (220-271) | 431 (412-457) |
| run: whole helper process (JVM start included) | n/a | 89 (84-158) | 200 (193-211) |
| run: read manifest | n/a | n/a | 12 (12-13) |
| run: SHA-256 of the archive | n/a | n/a | 51 (49-55) |
| run: JDK probe | n/a | n/a | 16 (13-18) |
| run: hash the classpath | n/a | n/a | 34 (34-38) |
| run: tool JVM uptime at the end | n/a | 80 (77-133) | 192 (188-202) |
| application: JVM-reported start (`Started ... in`) | 1318 (1287-1740) | 1339 (1280-2290) | 696 (663-806) |

### Difference in startTime-to-Ready, per round (seconds)

Each round runs the three arms back to back, so the paired difference cancels slow drift. Median and min-max over rounds.

| comparison | difference |
|---|---|
| nocache - none (cost of the machinery, no benefit) | 1.0 (1.0-3.0) |
| cached - none (what a real pod gains or loses) | 1.5 (1.0-2.0) |
| cached - nocache (what the archive itself buys) | 0.0 (-1.0-1.0) |

JVM-reported application start: 1318 ms without, 696 ms with the archive (+622 ms).
The cached arm's helpers (fetch + run) took about 991 ms of wall time before the application started.
