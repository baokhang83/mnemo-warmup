# What Happens When Nine Kubernetes Pods Fetch a JVM Cache at Once?

**A same-region Google Kubernetes Engine experiment measured one download and nine concurrent downloads of an 83 MiB JVM archive. Every fetch succeeded, but each pod's effective archive throughput was lower during the burst.**

JVM startup caches can reduce work the Java process does while starting. In Kubernetes, though, a new pod first needs the cache archive. If every replica downloads its own copy during scale-out, archive delivery becomes part of the rollout.

We measured that step on a cloud cluster with [mnemo-warmup](https://github.com/baokhang83/mnemo-warmup), which trains JVM archives and stores them in an OCI registry. The goal was narrow: compare one archive fetch with nine simultaneous fetches, record fetch-container CPU and throughput, and document the node and network setup.

## The setup

The test used one zonal GKE Standard node in `europe-west3-b`, with an `e2-standard-8` machine, and an Artifact Registry repository in the same region. A single node kept node scale-up out of the measurement. The application and tools images were pulled onto the node before testing, so their image downloads did not overlap the archive fetches.

The workload was a Spring Boot application using JDK 21 and an AppCDS archive. The archive was **87,191,552 bytes (83.15 MiB)**. Each pod downloaded its own copy into its own temporary volume; there was no shared archive cache.

For each of three matched rounds, we scaled the Deployment from zero to one pod, then from zero to nine pods. We also ran three additional nine-pod bursts while sampling the node's received-byte counter. The readiness probe used a one-second period. The app requested 100 millicores and 256 MiB of memory, with limits of one CPU and 512 MiB. The fetch container had no explicit CPU or memory limits.

## Results

The table shows medians from the three matched rounds. The extra nine-pod runs are described below.

| Measurement | One download | Nine simultaneous downloads |
|---|---:|---:|
| Successful fetches | 3 of 3 | 27 of 27 |
| Archive per pod | 83.15 MiB | 83.15 MiB |
| Archive bytes transferred per run | 83.15 MiB | 748.37 MiB |
| Node received bytes per run, median | 87.51 MiB | 760.38 MiB |
| Fetch-container CPU per pod, median | 0.414 s | 0.503 s |
| Store lookup, download and verification per pod, median | 856 ms | 985 ms |
| Stream, hash and write per pod, median | 273 ms | 430 ms |
| Effective archive throughput per pod, median | 2.56 Gbit/s | 1.62 Gbit/s |
| Scale request to all pods Ready, median | 13.38 s | 25.89 s |
| Fail-open fallbacks / reported HTTP 429s | 0 / 0 | 0 / 0 |

The nine-pod burst moved nine copies of the archive, or about **748 MiB** of payload. The median effective throughput for an individual fetch was about **37% lower** in the nine-pod rounds than in the single-fetch rounds. Fetch CPU rose from about 0.414 to 0.503 CPU-seconds per pod; across nine pods that is about 4.5 CPU-seconds of fetch-container work in total.

Three supplemental nine-pod runs produced the same basic outcome: all 27 fetches succeeded, with no fallback or reported HTTP 429. Across all six nine-pod runs, median per-fetch effective throughput was 1.65 Gbit/s and median fetch CPU was 0.476 seconds per pod.

## What “throughput” means here

The fetcher reports the archive's byte count and the time to stream the response body, hash it, and write it to disk. We calculated effective per-fetch throughput as archive bytes multiplied by eight, divided by that measured interval. It is useful for comparing these runs, but it is **not a network-only measurement**: hashing and storage writes are included.

We also recorded the node's received-byte counter before and after each run. It showed about 87.5 MiB for one fetch and 760.4 MiB for nine. Those values include other node traffic, not just archive bytes. Extra polling showed that GKE's kubelet counter refreshed in coarse jumps, so these records cannot establish a reliable subsecond aggregate bandwidth or peak rate.

The time from requesting a scale change until all pods were Ready was longer in the nine-pod runs. That includes scheduling, container startup, JVM and Spring Boot startup, and readiness-probe polling. It is not a measure of archive download time alone.

## What the results tell us

In this setup, Artifact Registry and one GKE node completed nine simultaneous downloads of an 83 MiB archive without a fetch failure or a logged HTTP 429. The effective throughput per pod was lower under concurrency, while the total traffic was close to nine archive copies.

That is evidence for this one same-region setup, not a general promise about registry performance. No 429s in the logs do not rule out bandwidth shaping. The test used one archive size, one region, one registry and one node. It did not include cold image pulls, autoscaling, or a shared archive cache. It also did not inject a failure, so it says nothing new about mnemo-warmup's fail-open behavior.

## What we should measure next

The next useful comparison is several archive sizes and, once available, per-pod downloads against a shared or node-local archive cache. A separate cold-node test should include application-image pulls and node scale-up. Those scenarios can tell us whether the observed transfer cost changes with archive size or with the way a rollout delivers data to pods.

For the full methodology and raw pod records, see the [GKE experiment report](https://github.com/baokhang83/mnemo-warmup/blob/bench/pod-to-ready-breakdown/benchmarks/results/20261114-archive-concurrency-gke-europe-west3/summary.md).
