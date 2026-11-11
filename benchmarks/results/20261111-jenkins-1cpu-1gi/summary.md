## Pod-to-Ready breakdown

**Application:** jenkins (0 JARs, 92 MiB; archive 47 MB); openjdk version "21.0.13" 2026-10-20 LTS; limits cpu=1 memory=1Gi.

**Machine:** Apple M5, 10 logical CPUs, 24 GiB, macOS 26.0. Docker gives the VM 10 CPUs and 7.8 GiB; kind node kindest/node:v1.36.1@sha256:3489c7674813ba5d8b1a9977baea8a6e553784dab7b84759d1014dbd78f7ebd5, 10 CPUs visible, docker limits {'nano_cpus': 0, 'cpu_quota': 0, 'cpu_shares': 0, 'cpuset_cpus': '', 'memory_bytes': 0}, cgroup cpu.max `max 100000`. Kubernetes v1.36.1 on arm64, 1 node.

**Registry:** in-cluster registry:2 over plain HTTP, request time of GET /v2/ from the node: median 0.6 ms, max 2.1 ms (30 samples); archive download from the node 2836 MB/s median (5 samples). This is a best case: the registry is one network hop away, not a remote one.

12 interleaved rounds per arm after one discarded warm-up round; commit 4fc46c5 (uncommitted changes); run on 2026-11-11T08:04:56Z. Reproduce with `scripts/bench-cluster.sh --app jenkins --restarts 12 --app-cpu 1 --app-memory 1Gi`.

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
| app started to Ready | 4.0 (3.0-4.0) | 3.0 (3.0-4.0) | 3.0 (3.0-4.0) |
| **startTime to Ready** | 4.0 (3.0-5.0) | 5.0 (5.0-6.0) | 5.0 (5.0-6.0) |
| created to Ready | 4.0 (3.0-5.0) | 5.0 (5.0-6.0) | 5.0 (5.0-6.0) |

### Where the tools spend their time (milliseconds)

| step | none | nocache | cached |
|---|---|---|---|
| fetch: whole helper process (JVM start included) | n/a | 604 (585-646) | 724 (711-758) |
| fetch: JDK detection | n/a | 16 (15-18) | 16 (15-18) |
| fetch: extract and hash the classpath | n/a | 72 (69-91) | 71 (69-83) |
| fetch: store lookup, download and digest check | n/a | 76 (73-85) | 205 (192-230) |
| fetch: tool JVM uptime at the end | n/a | 242 (237-281) | 370 (354-400) |
| run: whole helper process (JVM start included) | n/a | 115 (86-132) | 312 (288-349) |
| run: read manifest | n/a | n/a | 10 (9-57) |
| run: SHA-256 of the archive | n/a | n/a | 85 (36-101) |
| run: JDK probe | n/a | n/a | 22 (19-67) |
| run: hash the classpath | n/a | n/a | 90 (46-101) |
| run: tool JVM uptime at the end | n/a | 108 (78-123) | 306 (282-343) |
| application: self-reported start (Spring `Started ... in`; Jenkins first log line to `fully up and running`) | 2322 (2222-2422) | 2228 (2198-2385) | 2078 (1983-2105) |

### Difference in startTime-to-Ready, per round (seconds)

Each round runs the three arms back to back, so the paired difference cancels slow drift. Median and min-max over rounds.

| comparison | difference |
|---|---|
| nocache - none (cost of the machinery, no benefit) | 1.0 (1.0-3.0) |
| cached - none (what a real pod gains or loses) | 1.0 (1.0-3.0) |
| cached - nocache (what the archive itself buys) | 0.0 (0.0-0.0) |

JVM-reported application start: 2322 ms without, 2078 ms with the archive (+244 ms).
The cached arm's helpers (fetch + run) took about 1036 ms of wall time before the application started.
