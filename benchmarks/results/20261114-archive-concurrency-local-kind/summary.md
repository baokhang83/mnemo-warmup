# Single versus concurrent archive downloads

## Setup

Three rounds on a single-node kind cluster in Docker Desktop on an Apple M5 laptop, using GHCR and the same JDK 21 AppCDS archive in every fetch (87,359,488 bytes; 83.3 MiB). The application and tools images were preloaded. Each round fetched once with a single pod, then scaled from one to ten replicas so nine pods fetched concurrently. The fetch init container had no CPU limit. The node received-byte counter was sampled around each fetch window.

The run used the working tree at base commit `d580ab6` with the fetch CPU instrumentation and single-versus-concurrent harness changes in this result set; `environment.json` records the base commit and dirty-tree flag.

A separate `networkQuality -s` check after the runs measured 65.176 Mbps down and 15.950 Mbps up. It was not running during the measurements.

## Single fetch

| Round | Archive transfer, hash and write | Fetch container CPU | Node bytes received | Measurement window |
|---:|---:|---:|---:|---:|
| 1 | 9.434 s | 0.738 s | 83.8 MiB | 16.1 s |
| 2 | 9.427 s | 0.706 s | 83.9 MiB | 15.8 s |
| 3 | 8.788 s | 0.660 s | 83.8 MiB | 15.2 s |

## Nine concurrent fetches

| Round | Per-pod transfer median | Per-pod fetch CPU median | Node bytes received | Measurement window | Aggregate receive rate |
|---:|---:|---:|---:|---:|---:|
| 1 | 106.3 s | 2.400 s | 765.1 MiB | 117.1 s | 54.8 Mbps |
| 2 | 90.0 s | 2.228 s | 764.0 MiB | 100.1 s | 64.1 Mbps |
| 3 | 92.0 s | 2.343 s | 763.5 MiB | 103.2 s | 62.1 Mbps |

The node received about nine archive copies per burst. Aggregate receive rates approached the separately measured 65 Mbps downlink, while fetch CPU use was only about 2.2–2.4 CPU-seconds per pod. This points to shared network bandwidth as the main limit in these local runs. The three rounds are consistent with each other, but this is one laptop, one network, one registry and one archive size; it does not establish cloud performance. Window-level node bytes include Kubernetes traffic and time for scheduling/readiness around the fetches, so rates are approximate.

## Kubernetes burst details

See [burst-summary.md](burst-summary.md), [pods.csv](pods.csv), [bursts.csv](bursts.csv), and each `raw/cached-N/` directory. Raw fetch logs include the fetch-container cgroup CPU measurement.

## Still to do for issue #92

- Repeat from a cloud cluster in the same region as its registry, documenting node and network settings and image pull/node provisioning behavior. This machine has only a local kind context and no AWS CLI/profile, so that run needs access to a cloud cluster.
- Compare repeated per-pod downloads with a shared or node-local archive cache after such a cache is implemented.
