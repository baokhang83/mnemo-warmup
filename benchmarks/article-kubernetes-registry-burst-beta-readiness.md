# JVM Cache Downloads During Kubernetes Scale-Out: What Our Local and GKE Experiments Found

A JVM cache can shorten application startup, but Kubernetes pods need to receive the archive before they can use it. When nine new replicas start together, each download competes for network and compute resources. Before the mnemo-warmup beta, we wanted to understand that delivery cost and what happens when a registry is slow or unavailable.

We ran two sets of experiments: remote registry bursts from a local kind cluster, followed by one-versus-nine downloads on GKE with a registry in the same region. The results show why the delivery environment matters. In a representative local GHCR run, the median init phase was 206 seconds. In the GKE nine-pod runs, it was 7 seconds. The setups differ in several ways, so these figures do not isolate the cause of the difference.

## What we measured

[mnemo-warmup](https://github.com/baokhang83/mnemo-warmup) trains a JVM archive and stores it in an OCI registry. A new pod fetches and verifies a compatible archive before launching the application. Each pod in these experiments downloaded its own copy into a temporary volume.

The local study scaled a Spring Boot Deployment from one Ready replica to ten. We compared pods without mnemo-warmup, pods fetching an archive, and pods pointed at a refused local connection. We ran three bursts for each combination of GHCR or Docker Hub, JDK 21 or JDK 25, and one- or five-second readiness probe periods. The JDK 21 AppCDS archive was about 83.3 MiB; the JDK 25 AOT archive was about 104.3 MiB. JDK version and cache mechanism changed with archive size, so this is not a controlled test of archive size alone.

A follow-up compared one GHCR download with nine concurrent downloads of the same 83.3 MiB archive. The cloud experiment repeated one versus nine downloads using an 83.15 MiB JDK 21 archive on one GKE `e2-standard-8` node in `europe-west3-b`, with Artifact Registry in the same region. It used three matched rounds and three additional nine-pod bursts. Unlike the local 1-to-10 study, each GKE measurement started from zero pods and scaled to one or nine.

Application and tools images were already present on the node in both environments. The local tests used a single kind node and a shared internet connection. The cloud tests used one GKE node, a default VPC and subnet, IPv4 and Premium network tier. Neither setup measures cold image pulls or new node provisioning.

## The phases from pod creation to Ready

Kubernetes records the following milestones for each pod:

| Phase | Start → end | What the interval covers |
|---|---|---|
| Scheduling | Pod creation → `PodScheduled` | API server and scheduler delay. |
| Init and archive fetch | First init container starts → `Initialized` | Tools installation, archive fetch and verification. It excludes time before the first init container starts. |
| Container start | `Initialized` → app container starts | Runtime setup until the application container is running. The launcher may still be running afterward. |
| App boot and probe gate | App container starts → `Ready` | Launcher and JVM startup, Spring initialization and readiness probe polling. |

The table below shows median per-pod phase durations. The local row is one representative configuration: GHCR, JDK 21, one-second probe, three bursts and 27 new cached pods. The cloud rows are the primary three matched rounds. The measurements come from pod status objects and have whole-second resolution; phase medians are calculated separately and need not add up to a median total.

| Phase, median | Local GHCR: nine new pods | GKE: one pod | GKE: nine pods |
|---|---:|---:|---:|
| Creation → PodScheduled | 0 s | 0 s | 0 s |
| First init start → Initialized | 206 s | 2 s | 7 s |
| Initialized → app container start | 0 s | 1 s | 0 s |
| App container start → Ready | 5 s | 9 s | 16 s |

The app container's running timestamp is not the end of launcher execution. Launcher and JVM work can continue during the last interval. The GKE experiment did not include an uncached control, so its Ready times do not establish whether mnemo-warmup made pods ready faster.

## Local remote-registry bursts

Across the eight local registry, JDK and probe configurations, pods without mnemo-warmup reached Ready in 8–12 seconds median. For successful archive fetches, the median time to stream, hash and write the archive ranged from 190 to 301 seconds. These figures come from separate runs and are not a ranking of GHCR against Docker Hub.

The readiness probe period was visible in the final phase: median app-container-start-to-Ready was 3–5 seconds with a one-second period and 6 seconds with a five-second period. Both used zero initial delay. A five-second period is an example setting, not the Kubernetes default.

In two cached bursts, some downloads stalled and the pods started without an archive: nine pods in one GHCR burst and eight in one Docker Hub burst. Fetch logs reported a 120-second no-data timeout for most affected pods, and all affected applications became Ready. The separate refused-connection arm also started uncached. A refused connection and a stalled transfer have different effects on how long a pod waits before fallback.

No fetch log reported HTTP 429. That does not rule out bandwidth shaping or throttling. We also checked the ECR token-auth code path with Moto and a fake registry challenge; that local check does not validate live ECR, AWS identity, IAM or registry performance.

## One download versus nine

The local GHCR follow-up measured the same archive in each run. A single fetch spent about 9 seconds streaming, hashing and writing the archive. With nine concurrent fetches, the median per-pod transfer was about 91 seconds across the measured pods, and the node received roughly 764 MiB per burst. The node's approximate aggregate receive rate approached a separate 65 Mbps connection check taken after the runs. That is consistent with a shared network bottleneck in this setup; the connection check was not taken during the experiment.

On GKE, all 3 single and 54 concurrent fetches succeeded. For the three matched rounds:

| Measurement, median | One fetch | Nine concurrent fetches |
|---|---:|---:|
| Archive payload per run | 83.15 MiB | 748.37 MiB |
| Node received bytes per run | 87.51 MiB | 760.38 MiB |
| Fetch CPU per pod | 0.414 s | 0.503 s |
| Store lookup, download and verification per pod | 856 ms | 985 ms |
| Stream, hash and write per pod | 273 ms | 430 ms |
| Effective archive throughput per pod | 2.56 Gbit/s | 1.62 Gbit/s |
| Scale request → all pods Ready | 13.38 s | 25.89 s |

The nine GKE pods downloaded nine archive copies. Median effective throughput per pod fell by about 37% during the burst. This rate divides archive bytes by the time spent streaming, hashing and writing, so it is not network-only bandwidth. Node received-byte deltas include other traffic. The kubelet counter refreshed too coarsely to establish reliable subsecond aggregate or peak bandwidth.

## What this means for beta readiness

The studies establish that archive delivery can dominate startup on a constrained shared connection, and that nine same-region GKE fetches succeeded in the measured runs. They also show that a fallback can keep applications running after a refused connection or a stalled local-study transfer. These are useful observations for beta evaluation, not proof that every registry, network or rollout will behave the same way.

The remaining gaps matter: the GKE runs used one archive size, one node and one registry; they did not inject a failure, compare cached and uncached readiness, pull cold images or scale new nodes. Neither study evaluated a shared or node-local archive cache. Zero logged 429s do not prove that bandwidth was never shaped, and the local Moto check is not a live ECR test.

Before relying on a cache for a workload, measure archive transfer, fetch CPU, application startup and pod readiness under representative resource limits and registry conditions. Compare with an uncached control and test both an unavailable registry and a stalled transfer. A future study should vary archive sizes, include cold nodes and real cloud identity paths, and compare per-pod downloads with a shared or node-local cache once one exists.

We also need to study larger Spring Boot workloads that produce larger archives. Across several workloads and archive sizes, we can compare application startup and pod readiness with and without mnemo-warmup, alongside archive fetch time and CPU cost. That would help us understand how the startup gain and delivery cost change as archives grow, and where downloading an archive stops paying for itself. Workload complexity can affect both archive size and startup time, so the study would need to account for those differences before attributing a result to size alone.

The [remote registry study and raw records](https://github.com/baokhang83/mnemo-warmup/blob/bench/pod-to-ready-breakdown/benchmarks/remote-registry-bursts.md), [local one-versus-nine follow-up](https://github.com/baokhang83/mnemo-warmup/blob/bench/pod-to-ready-breakdown/benchmarks/results/20261114-archive-concurrency-local-kind/summary.md), and [GKE experiment report](https://github.com/baokhang83/mnemo-warmup/blob/bench/pod-to-ready-breakdown/benchmarks/results/20261114-archive-concurrency-gke-europe-west3/summary.md) provide the full methodology and records.
