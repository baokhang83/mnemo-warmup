# One-to-ten replica bursts

Each burst starts with one Ready pod and adds nine. Times are median (min–max); Kubernetes pod timestamps have whole-second resolution. Scale-to-Ready uses the client timestamp immediately before the scale request.

| Arm | Bursts | New pods | Archive used | Uncached fallback | HTTP 429 reported | Pod creation to Ready (s) | Scale to last new pod Ready (s) | Archive transfer, hash and write (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| none | 3 | 27 | 0 | 0 | 0 | 9.0 (8.0–10.0) | 8.7 (8.1–9.4) | n/a |
| cached | 3 | 27 | 27 | 0 | 0 | 301.0 (259.0–328.0) | 309.5 (293.6–327.3) | 293324.0 (249435.0–320244.0) |
| fault | 3 | 27 | 0 | 27 | 0 | 10.0 (10.0–10.0) | 9.7 (9.3–9.9) | n/a |

## Pod phases

| Arm | Creation to PodScheduled (s) | First init start to Initialized (s) | Initialized to app container start (s) | App container start to Ready (s) |
|---|---:|---:|---:|---:|
| none | 0.0 (0.0–0.0) | n/a | 1.0 (0.0–1.0) | 9.0 (8.0–9.0) |
| cached | 0.0 (0.0–0.0) | 297.0 (256.0–324.0) | 0.0 (0.0–0.0) | 3.0 (3.0–4.0) |
| fault | 0.0 (0.0–0.0) | 2.0 (1.0–2.0) | 0.0 (0.0–1.0) | 8.0 (7.0–8.0) |

The init interval begins when the first init container starts, so it excludes time before that start, including scheduling and any image pull. The app container running timestamp does not mark JVM or launcher completion; that work continues inside the last interval. The app has no init containers in the no-tool arm, so its init interval is unavailable. Probe initial delay and period are recorded per pod in `pods.csv`.


The fault arm points to a refused local connection and exercises uncached startup; it is not a slow-remote-registry simulation. `pods.csv` contains per-pod timings, archive flags, reported 429s, app-container cgroup CPU counters, and archive sizes when available. Transfer time includes streaming, hashing, and writing the archive, and excludes request setup and response headers. CPU counters are read after readiness and do not measure the terminated fetch init container. A missing timing or counter remains empty.

A zero 429 count means only that captured fetch logs did not report HTTP 429; it does not prove the registry applied no rate or bandwidth limits. Unready bursts are saved as failure records and do not appear in this table.
