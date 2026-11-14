# GKE workload-tier benchmark: repaired cells

One trial per cell; values are descriptive. A fixed one-node cluster was used. Standard and Larger HPA repairs used 256 load workers with a two-CPU load-generator limit; the original cells used 128 workers with a 500m limit. Single-pod runs used 128 workers.

The original HPA timings use watch receipt times and may include watch buffering; repaired HPA timings use direct polling. Original pod watches lack a recorded trial-start boundary; zero durations are excluded but other stale observations cannot be ruled out. These limitations prevent a precise comparison of HPA reaction across original and repaired runs.

App Ready and fetch/hash are medians of observed per-pod durations in seconds. HPA reaction is load start to first scale request; HPA desired 9 is load start to requesting nine pods; HPA Ready is load start to nine Ready pods. RPS is the mean of up to the final 20 one-second samples. Validity covers startup, cache, scale target and watch checks; request failures are listed independently. RX/TX are workload-namespace traffic deltas, not node-wide network traffic.

| Tier | Topology | Arm | Valid | Pods | App Ready s | App observations | Fetch/hash s | HPA reaction s | HPA desired 9 s | HPA Ready s | Hits/misses | RPS | Failures | RX MiB | TX MiB |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| minimal | single | uncached | yes | 1 | 4.5 | 1 | — | — | — | — | 0/0 | 1076.6 | 0 | 8.1 | 9.0 |
| minimal | single | direct | yes | 1 | 2.1 | 1 | 0.2 | — | — | — | 0/0 | 1125.3 | 10 | 58.8 | 3.6 |
| minimal | single | cold_node_cache | yes | 1 | 2.1 | 1 | 0.1 | — | — | — | 0/1 | 963.4 | 10 | 110.5 | 55.9 |
| minimal | single | warm_node_cache | yes | 1 | 2.0 | 1 | 0.1 | — | — | — | 1/0 | 826.8 | 10 | 59.1 | 59.0 |
| minimal | hpa_1_to_9 | uncached | yes | 9 | 5.4 | 9 | — | 4.7 | 4.7 | 110.9 | 0/0 | 2805.8 | 11 | 202.5 | 202.4 |
| minimal | hpa_1_to_9 | direct | yes | 9 | 3.0 | 9 | 0.2 | 19.0 | 47.3 | 55.0 | 0/0 | 2849.3 | 10 | 607.2 | 116.2 |
| minimal | hpa_1_to_9 | cold_node_cache | yes | 9 | 3.1 | 9 | 0.3 | 21.0 | 51.1 | 60.4 | 8/1 | 2760.8 | 12 | 675.1 | 620.6 |
| minimal | hpa_1_to_9 | warm_node_cache | yes | 9 | 3.0 | 9 | 0.3 | 18.2 | 46.5 | 54.2 | 9/0 | 2874.2 | 10 | 608.5 | 608.6 |
| standard | single | uncached | yes | 1 | 7.4 | 1 | — | — | — | — | 0/0 | 460 | 9 | 5.2 | 4.6 |
| standard | single | direct | yes | 1 | 3.1 | 1 | 0.4 | — | — | — | 0/0 | 520 | 10 | 110.3 | 4.8 |
| standard | single | cold_node_cache | yes | 1 | 4.2 | 1 | 0.3 | — | — | — | 0/1 | 791.3 | 6 | 216.9 | 111.0 |
| standard | single | warm_node_cache | yes | 1 | 4.0 | 1 | 0.3 | — | — | — | 1/0 | 412.9 | 9 | 108.3 | 107.1 |
| standard | hpa_1_to_9 | uncached | yes | 9 | 11.6 | 9 | — | 112.7 | 157.7 | 170.7 | 0/0 | 8352.2 | 8 | 407.6 | 410.3 |
| standard | hpa_1_to_9 | direct | yes | 9 | 5.9 | 9 | 0.5 | 2.9 | 2.9 | 125.4 | 0/0 | 9162.8 | 8 | 1404.9 | 464.5 |
| standard | hpa_1_to_9 | cold_node_cache | yes | 9 | 5.1 | 9 | 0.6 | 217.7 | 259.6 | 274.9 | 8/1 | 9988.9 | 7 | 2010.0 | 1903.5 |
| standard | hpa_1_to_9 | warm_node_cache | yes | 9 | 5.1 | 9 | 0.5 | 11.4 | 11.4 | 30.8 | 9/0 | 3912.2 | 8 | 1123.9 | 1126.7 |
| larger | single | uncached | yes | 1 | 8.7 | 1 | — | — | — | — | 0/0 | 526.4 | 7 | 4.6 | 5.2 |
| larger | single | direct | yes | 1 | 3.9 | 1 | 0.5 | — | — | — | 0/0 | 444.9 | 9 | 121.3 | 2.9 |
| larger | single | cold_node_cache | yes | 1 | 5.0 | 1 | 0.3 | — | — | — | 0/1 | 757.6 | 0 | 243.2 | 126.9 |
| larger | single | warm_node_cache | yes | 1 | 4.1 | 1 | 0.3 | — | — | — | 1/0 | 608.5 | 7 | 121.6 | 120.4 |
| larger | hpa_1_to_9 | uncached | yes | 9 | 15.4 | 9 | — | 17.9 | 61.6 | 80.4 | 0/0 | 7240.9 | 6 | 264.1 | 260.2 |
| larger | hpa_1_to_9 | direct | yes | 9 | 9.0 | 9 | 0.8 | 16.4 | 46.7 | 65.2 | 0/0 | 4714.2 | 9 | 1229.7 | 182.4 |
| larger | hpa_1_to_9 | cold_node_cache | yes | 9 | 7.9 | 9 | 1.5 | 1.2 | 1.2 | 69.1 | 8/1 | 8088.5 | 7 | 1496.0 | 1378.4 |
| larger | hpa_1_to_9 | warm_node_cache | yes | 9 | 8.0 | 9 | 0.8 | 2.8 | 2.8 | 84.7 | 9/0 | 7508.2 | 10 | 1348.3 | 1347.7 |
| thingsboard | single | uncached | yes | 1 | 30.8 | 1 | — | — | — | — | 0/0 | 148.1 | 0 | 6.7 | 1.9 |
| thingsboard | single | direct | yes | 1 | 21.4 | 1 | 0.7 | — | — | — | 0/0 | 83 | 0 | 236.9 | 7.0 |
| thingsboard | single | cold_node_cache | yes | 1 | 21.4 | 1 | 0.6 | — | — | — | 0/1 | 84.4 | 0 | 455.9 | 230.9 |
| thingsboard | single | warm_node_cache | yes | 1 | 21.2 | 1 | 0.5 | — | — | — | 1/0 | 83.7 | 0 | 242.9 | 238.9 |
| thingsboard | hpa_1_to_9 | uncached | yes | 9 | 55.3 | 9 | — | 23.0 | 104.8 | 173.0 | 0/0 | 1159.4 | 0 | 664.6 | 623.4 |
| thingsboard | hpa_1_to_9 | direct | yes | 9 | 51.1 | 9 | 0.9 | 44.3 | 104.3 | 171.6 | 0/0 | 709.4 | 0 | 2458.1 | 388.2 |
| thingsboard | hpa_1_to_9 | cold_node_cache | yes | 9 | 44.0 | 9 | 2.5 | 37.4 | 100.7 | 163.9 | 8/1 | 749.5 | 0 | 2704.8 | 2426.8 |
| thingsboard | hpa_1_to_9 | warm_node_cache | yes | 9 | 43.3 | 9 | 3.0 | 116.3 | 175.0 | 238.2 | 9/0 | 959.9 | 0 | 2598.6 | 2535.2 |

The original minimal direct HPA and cold-cache single-pod cells each recorded ten connection-refused errors at load start, before sustained successful traffic. The direct HPA errors preceded the first observed scale action. The logs do not identify the rejecting network component; the load generator checks for connection refusal before DNS errors, so a wrapped DNS refusal could be counted in that class.

The original Larger direct-fetch and warm-cache primer failures included registry HTTP 401 responses; repaired cells refresh credentials before each trial.

ThingsBoard warm-cache HPA's original watch-reported 238.2 seconds includes an unusually late first watch observation. Available logs do not establish whether the delay arose in HPA, watch delivery, CPU, or the database.

## Archive sizes

| Tier | Archive bytes | SHA-256 |
|---|---:|---|
| minimal | 56942592 | 39ae9594ab092fe6875c7823ac80c02226169f782f35c61d3045574206c8f813 |
| standard | 108924928 | 128fbe71d0fe5b89a5465a5cf2e98a80b4c3039624929f74339ede11d290b673 |
| larger | 121602048 | 47650c2370df5038a27e26c275942a7b8d248243610b58287f02e8f0e6e1930b |
| thingsboard | 234381312 | ee7d9de0dd695256174a95f993055564be351e113f2e92340b4d85ca607707ff |

## Request failure classes

| Tier | Topology | Arm | Class | Count |
|---|---|---|---|---:|
| minimal | single | direct | connection_refused | 10 |
| minimal | single | cold_node_cache | connection_refused | 10 |
| minimal | single | warm_node_cache | connection_refused | 10 |
| minimal | hpa_1_to_9 | uncached | connection_refused | 11 |
| minimal | hpa_1_to_9 | direct | connection_refused | 10 |
| minimal | hpa_1_to_9 | cold_node_cache | connection_refused | 12 |
| minimal | hpa_1_to_9 | warm_node_cache | connection_refused | 10 |
| standard | single | uncached | connection_refused | 9 |
| standard | single | direct | connection_refused | 10 |
| standard | single | cold_node_cache | connection_refused | 6 |
| standard | single | warm_node_cache | connection_refused | 9 |
| standard | hpa_1_to_9 | uncached | connection_refused | 8 |
| standard | hpa_1_to_9 | direct | connection_refused | 8 |
| standard | hpa_1_to_9 | cold_node_cache | connection_refused | 7 |
| standard | hpa_1_to_9 | warm_node_cache | connection_refused | 8 |
| larger | single | uncached | connection_refused | 7 |
| larger | single | direct | connection_refused | 9 |
| larger | single | warm_node_cache | connection_refused | 7 |
| larger | hpa_1_to_9 | uncached | connection_refused | 6 |
| larger | hpa_1_to_9 | direct | connection_refused | 9 |
| larger | hpa_1_to_9 | cold_node_cache | connection_refused | 7 |
| larger | hpa_1_to_9 | warm_node_cache | connection_refused | 10 |
