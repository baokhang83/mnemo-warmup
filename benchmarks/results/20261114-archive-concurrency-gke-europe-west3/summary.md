# One versus nine archive downloads on GKE

This run repeated the single-fetch versus nine-concurrent-fetch comparison on a cloud Kubernetes node, with the archive hosted by Artifact Registry in the same Google Cloud region. It isolates archive delivery: the application and tools images were pulled before measurements, and one zonal node was kept for every run.

## Result

Medians from three matched rounds:

| Measurement | One fetch | Nine concurrent fetches |
|---|---:|---:|
| Successful fetches | 3/3 | 27/27 |
| Archive downloaded per pod | 83.15 MiB | 83.15 MiB |
| Archive bytes per run | 83.15 MiB | 748.37 MiB |
| Node received bytes per run, median | 87.51 MiB | 760.38 MiB |
| Fetch container CPU per pod, median | 0.414 s | 0.503 s |
| Store lookup, download and verification per pod, median | 856 ms | 985 ms |
| Stream, hash and write per pod, median | 273 ms | 430 ms |
| Effective archive throughput per pod, median | 2.56 Gbit/s | 1.62 Gbit/s |
| Scale request to all pods Ready, median | 13.38 s | 25.89 s |
| Fail-open fallbacks / reported HTTP 429s | 0 / 0 | 0 / 0 |

The nine-fetch runs transferred nine independent copies of the same 83.15 MiB archive. The median node received-byte deltas were 87.51 MiB for one fetch and 760.38 MiB for nine. These counters include other node traffic and have coarse refresh behavior, so the differences from archive payload are not attributed entirely to protocol overhead. Each pod used its own archive volume; this does not measure shared archive caching.

The per-pod effective throughput is `archive bytes × 8 / archive-transfer-hash-write time`. That timing includes streaming the response body, hashing, and writing the file, so it is not a network-only rate. The median per-fetch rate was about 37% lower during the nine-pod burst. The total node receive counter confirms the transferred volume, but GKE's kubelet summary counter updated in coarse jumps during extra polling, so it cannot support a reliable subsecond aggregate bandwidth or peak rate. The extra counter samples are retained under `raw/concurrent9-telemetry-*`.

Three supplemental nine-pod bursts also succeeded (27/27 fetches). Across all six nine-pod runs, median per-fetch effective throughput was 1.65 Gbit/s, fetch CPU was 0.476 s per pod, store lookup/download/verification was 978 ms per pod, and node received bytes were 760.59 MiB per burst. These repeats support the primary result; the first three remain the matched comparison against the three single-fetch rounds.

The nine-pod fetch used a median 0.503 CPU-seconds per container, versus 0.414 for a single fetch. Total fetch CPU was about 4.5 CPU-seconds across the nine containers. Fetches all succeeded, with no reported 429s or fail-open events. This says the tested same-region Artifact Registry handled these bursts; it does not prove that larger archives, another registry, or a busier cluster will avoid throttling.

Scale-to-Ready took longer in the nine-pod runs. That interval includes scheduling, JVM startup, Spring context startup, and readiness probe polling as well as archive fetch. It is not a fetch-only duration. The readiness probe used a one-second period to limit polling quantization.

## Method

The cluster had one GKE Standard node in `europe-west3-b`, using an `e2-standard-8` machine. The registry was in `europe-west3`. Each measurement began with zero application pods, then scaled the same Deployment to one or nine replicas. Scaling to zero between runs removed each pod's local archive volume. Three rounds used the sequence single, then nine concurrent. Three more nine-pod runs captured extra node network-counter samples.

The fetch container's native timing log records archive size, transfer/hash/write duration, and cgroup CPU usage. The node's kubelet `stats/summary` `rxBytes` counter was sampled before and after each run. The application and tools images were already present on the node during every measurement. The application requested 100m CPU and 256 MiB memory, with limits of 1 CPU and 512 MiB; the fetch container had no explicit resource requests or limits.

## Limits and next experiment

This uses one archive size, one cloud region, one node, and one registry provider. It does not measure cold application-image pulls or node autoscaling. The counter sampling did not resolve the short transfer burst precisely, so aggregate peak bandwidth remains unknown. Zero HTTP 429 responses are not evidence that no bandwidth shaping occurred. No failure was injected, so fail-open behavior is not evaluated here. The shared or node-local archive cache comparison must wait until that cache design exists.

Next, repeat with several archive sizes and another registry location/provider, and then compare per-pod downloads against a shared or node-local archive cache. A separate cold-node run should measure image pulls and node scale-up.

## Records

- `environment.json` records the node, network, registry, image digests, workload settings, and cleanup.
- `runs.csv` contains one row per measured run.
- `fetches.csv` contains derived measurements for each pod.
- `raw/` contains pod JSON, fetch logs, node receive-byte samples, and client timestamps. App logs are omitted; the benchmark application logs a generated development password.

The project identifier in the raw records and image references is replaced with `PROJECT_ID`; all other saved measurement values are unchanged. See [results from the earlier local kind experiment](../20261114-archive-concurrency-local-kind/summary.md) for the corresponding local comparison.
