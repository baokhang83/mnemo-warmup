# Remote registry burst study

This study measures one Deployment scaling from one Ready replica to ten against a remote OCI archive registry. It uses GHCR and Docker Hub as separate experiments. The scale command reproduces the replica change, not HPA decision latency. The application image and tools image are preloaded into kind so their image pulls do not obscure archive fetches.

## Method

`scripts/bench-burst.sh` builds the same Spring-heavy workload used in the JDK comparison, starts an isolated kind cluster, trains an archive, and runs repeated bursts. Every round changes the order of three arms:

- `none`: no mnemo-warmup;
- `cached`: fetch and use the archive from the selected store;
- `fault`: use an intentionally unreachable local endpoint and check whether pods start uncached.

Each burst starts with one Ready pod and adds nine. The harness captures pod objects, fetch and application logs, per-pod application cgroup CPU counters, archive sizes, client scale-request times and per-burst rollout logs. `pods.csv` and `bursts.csv` summarize those records. App containers request 100 millicores and 256 MiB and are limited to 1 CPU and 512 MiB. The generated fetch init container and trainer have no resource limits. The app-container CPU counters are read after readiness, so they do not measure fetch-container CPU or isolate throttling during startup.

For each pod, the report separates creation to `PodScheduled`, first init-container start to `Initialized`, `Initialized` to app-container start, and app-container start to `Ready`. The init interval excludes time before the first init container starts. A container running timestamp does not mean that the launcher has finished; launcher timings come from its logs. Kubernetes timestamps have whole-second resolution. The harness records the actual readiness probe values from each pod. `--probe-period 1` uses a one-second period; repeat with `--probe-period 5` to see how a five-second polling interval affects readiness observations. The initial delay is zero in both cases. Five seconds is an example configuration, not the Kubernetes default.

With `MNEMO_WARMUP_TIMING=1`, native fetch reports archive bytes and the time spent streaming the response body, hashing it and writing it to disk. That is not network-only time: it includes hashing, storage writes and close, and excludes the request and response headers. The existing total store lookup/download/verification time is also captured. Java fallback does not currently emit this separate transfer timing.

Run JDK 21 and JDK 25 separately to obtain different AppCDS and AOT archives for the same workload. This changes the JVM and cache mechanism as well as archive size, so the results do not isolate archive size as the cause of a difference. Record actual archive bytes for each run. Bursts rotate arm order within a run; runs for different registries and JDKs are sequential.

## Registry credentials and runs

Use dedicated benchmark repositories and an account that can push and pull. The script uploads an archive to the supplied repository. It does not delete remote artifacts. Keep credentials out of the repository and result directories. The in-cluster client needs a Docker config containing actual credentials; a config that only names a Docker Desktop credential helper is not sufficient. After logging in with Docker, export one login into a new temporary file with mode 0600:

```sh
python3 scripts/registry_auth_from_docker.py --registry ghcr.io --out /tmp/mnemo-ghcr-auth.json
python3 scripts/registry_auth_from_docker.py --registry registry-1.docker.io --out /tmp/mnemo-hub-auth.json
```

Run from a clean, recorded source revision, using a dedicated repository that the account can write. The host and repository are examples:

```sh
scripts/bench-burst.sh --store oci://ghcr.io/OWNER/REPOSITORY --auth-file /tmp/mnemo-ghcr-auth.json --jdk 21 --rounds 3 --out /tmp/burst-ghcr-21
scripts/bench-burst.sh --store oci://registry-1.docker.io/OWNER/REPOSITORY --auth-file /tmp/mnemo-hub-auth.json --jdk 21 --rounds 3 --out /tmp/burst-hub-21
```

Repeat with `--jdk 25`, and repeat both JDKs with `--probe-period 5`, using distinct output directories. Remove the temporary auth files after use. A Docker Hub repository may need to be created in advance, and GHCR credentials need permission to publish packages. Run `scripts/bench-burst.sh --local-registry --rounds 1 --out /tmp/burst-smoke` only as a local harness check. `KEEP=1` retains the test cluster for debugging; otherwise the script removes its own cluster and temporary images. Before collecting results, review logs and pod objects for sensitive information.

The fault arm tests a refused local connection. For a separate controlled slow-link experiment, run `scripts/bench-burst.sh --local-registry --slow-local --rounds 3 --out /tmp/burst-slow`: an isolated anonymous local TCP proxy adds 500 ms per connection and caps each server-to-client connection at 8 MiB/s. This is a synthetic local test, not a measurement of either remote provider. It does not emulate prolonged download stalls, TLS problems or actual rate limiting. A 429 count is derived from fetch logs, and zero reported 429s cannot prove that the remote service did not throttle throughput. The script retains rollout failure records, but failed bursts must be investigated and reported rather than silently excluded.

## Cache boundaries

The injected application gets a pod-local `emptyDir` for the downloaded archive. Each new pod performs its own archive fetch; the Kubernetes container image layer cache does not automatically share this downloaded OCI archive among pods. The tools image itself may be delivered by an init container or image volume, but that is a separate cache and delivery path. Compare per-pod archive byte counts and transfer records before claiming any shared or node-level archive caching. A shared-cache design needs its own implementation and benchmark.

## Local ECR token check with Moto

With Python 3, Maven and `moto[server]==5.1.22` installed in a virtual environment, run `scripts/test-moto-ecr.sh`. It starts Moto locally and runs an opt-in integration test. The test obtains a token through the project's ECR authorization provider and uses it to answer a local fake registry's Basic challenge.

Moto 5.1.22 returns a simulated token with an expiry in 2015. It does not validate AWS signatures or IAM authorization, and this test supplies fake AWS credentials directly rather than exercising EKS web identity. Moto therefore cannot verify real ECR token expiration, EKS identity, registry transfer, rate limits or AWS performance. Existing AWS auth unit tests cover the project's web-identity wiring separately.

## Evidence status

The eight remote configurations completed on 2026-11-13: GHCR and Docker Hub, JDK 21 and JDK 25, and readiness periods of one and five seconds. Each configuration has three rounds, or 27 scale-out pods per arm. Each run directory below includes pod-level CSV, burst-level CSV, environment metadata, pod JSON, and container logs.

| Registry | JDK | Archive size | Probe period | Cached fetches | Uncached fallbacks | Cached pod creation to Ready, median | Successful transfer, hash and write, median |
|---|---:|---:|---:|---:|---:|---:|---:|
| GHCR | 21 | 83.3 MiB | 1 s | 27/27 | 0/27 | 212 s | 203.5 s |
| Docker Hub | 21 | 83.3 MiB | 1 s | 27/27 | 0/27 | 199 s | 191.4 s |
| GHCR | 25 | 104.3 MiB | 1 s | 27/27 | 0/27 | 301 s | 293.3 s |
| Docker Hub | 25 | 104.4 MiB | 1 s | 27/27 | 0/27 | 289 s | 281.8 s |
| GHCR | 21 | 83.3 MiB | 5 s | 18/27 | 9/27 | 229 s | 235.1 s |
| Docker Hub | 21 | 83.3 MiB | 5 s | 27/27 | 0/27 | 199 s | 190.4 s |
| GHCR | 25 | 104.3 MiB | 5 s | 27/27 | 0/27 | 264 s | 254.6 s |
| Docker Hub | 25 | 104.4 MiB | 5 s | 19/27 | 8/27 | 338 s | 301.0 s |

Every cached pod that fetched an archive reported a hit; the others fell back and started without it. Both fallback events were bursts where all nine concurrent pods hit the fetcher's 120-second no-data timeout: round 1 on GHCR/JDK 21/5 s, and round 3 on Docker Hub/JDK 25/5 s. Their application pods still became Ready. The other six provider/JDK/probe configurations had no fallback. No fetch log reported HTTP 429. Zero reported 429s do not rule out bandwidth shaping or other throttling.

The no-tool arms became Ready in 8–12 seconds median and the refused-connection fallback arms in 9–14 seconds median, depending on configuration. Scheduling was effectively immediate in this one-node kind cluster. The readiness period was visible after the app container started: median app-container-start-to-Ready was 3–5 seconds at a one-second period and 6 seconds at a five-second period. That phase is small next to the archive transfer. Coarse Kubernetes timestamps have whole-second resolution.

These are measurements from a single Apple M5 laptop using Docker Desktop and a one-node kind cluster, not production Kubernetes. All pods fetched their own archive from a pod-local `emptyDir`; there is no shared archive layer cache in this setup. JDK 21 uses AppCDS and JDK 25 uses AOT, so JVM, archive mechanism, and archive size all change together. The study cannot attribute the difference to size alone. Provider and run comparisons are also subject to internet and time variation. Each environment file records source commit `0abf42c`; the worktree was marked dirty only because an unrelated, pre-existing article file was untracked.

| Run | Raw results |
|---|---|
| GHCR, JDK 21, 1 s | [summary](results/20261113-remote-registry-ghcr-jdk21-probe1/summary.md) · [pods.csv](results/20261113-remote-registry-ghcr-jdk21-probe1/pods.csv) |
| Docker Hub, JDK 21, 1 s | [summary](results/20261113-remote-registry-dockerhub-jdk21-probe1/summary.md) · [pods.csv](results/20261113-remote-registry-dockerhub-jdk21-probe1/pods.csv) |
| GHCR, JDK 25, 1 s | [summary](results/20261113-remote-registry-ghcr-jdk25-probe1/summary.md) · [pods.csv](results/20261113-remote-registry-ghcr-jdk25-probe1/pods.csv) |
| Docker Hub, JDK 25, 1 s | [summary](results/20261113-remote-registry-dockerhub-jdk25-probe1/summary.md) · [pods.csv](results/20261113-remote-registry-dockerhub-jdk25-probe1/pods.csv) |
| GHCR, JDK 21, 5 s | [summary](results/20261113-remote-registry-ghcr-jdk21-probe5/summary.md) · [pods.csv](results/20261113-remote-registry-ghcr-jdk21-probe5/pods.csv) |
| Docker Hub, JDK 21, 5 s | [summary](results/20261113-remote-registry-dockerhub-jdk21-probe5/summary.md) · [pods.csv](results/20261113-remote-registry-dockerhub-jdk21-probe5/pods.csv) |
| GHCR, JDK 25, 5 s | [summary](results/20261113-remote-registry-ghcr-jdk25-probe5/summary.md) · [pods.csv](results/20261113-remote-registry-ghcr-jdk25-probe5/pods.csv) |
| Docker Hub, JDK 25, 5 s | [summary](results/20261113-remote-registry-dockerhub-jdk25-probe5/summary.md) · [pods.csv](results/20261113-remote-registry-dockerhub-jdk25-probe5/pods.csv) |

One-round local smoke runs with one- and five-second probes, including the synthetic slow proxy, also completed. These verify harness paths only; they are not included as remote-registry performance evidence. The Moto 5.1.22 ECR authorization-token test passed against a local fake registry challenge. Moto does not verify live ECR, AWS identity, IAM, token expiration, throttling, or registry performance.
