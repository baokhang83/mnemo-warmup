# Benchmarks

Measurements of what mnemo-warmup does to **pod start-to-Ready**, with the method, the raw data and the machine specification, so that anyone can rerun the study or check a number.

The question is not "does the JVM start faster with an archive" (it does). It is "does a *pod* become Ready sooner once you add the init containers, the archive download, the digest checks and the helper JVMs that the cache needs", and where the time goes.

The separate [archive compatibility study](../docs/archive-compatibility.md) tests JVM flag and container-limit changes; it is not a startup timing benchmark.

## Method

`scripts/bench-cluster.sh` creates a throw-away [kind](https://kind.sigs.k8s.io/) cluster and an archive registry (`registry:2` on the kind network), builds the tools image from this checkout and the application image, and deploys **three arms** of the same chart (`examples/annotated-app`), all running the same application image:

| arm | what runs |
|---|---|
| `none` | the plain Deployment, no mnemo-warmup |
| `nocache` | mnemo-warmup on (delivery mode, launcher, helpers), but the archive repository is empty: the cost of the machinery with no benefit |
| `cached` | mnemo-warmup on and an archive trained for this image: what a real pod does |

Each **measurement** scales one Deployment from 0 to 1 (a brand-new pod), waits for Ready, records the pod's own timestamps and the tools' timing lines, then scales back to 0. The arms are **interleaved** (round 1: none, nocache, cached; round 2: ...), so slow drift of the machine affects all arms equally. One warm-up round per arm is discarded. The default is 12 measured rounds.

**Cold means:** a new pod on a node that already holds the image. Image pull time is **not** measured, because the same image is pulled in every arm and the cache does not change it. A pod on a node that must pull the image first is slower by that pull, in every arm.

**Where the numbers come from**

- *Coarse phases* come from the pod object (`status.startTime`, init and app container `startedAt` and `finishedAt`, the `Initialized` and `Ready` conditions). The API stores **whole seconds**, so a single coarse number can be off by up to a second; medians over many rounds are what to read. The readiness probe runs every second, which adds up to one second of quantisation to `Ready` as well.
- *Fine phases* (milliseconds) come from the tools themselves with `MNEMO_WARMUP_TIMING=1`: `fetch` reports JDK detection, extracting and hashing the classpath, and the store lookup, download and digest check; `run` reports reading the manifest, the SHA-256 of the archive, the JDK probe and hashing the classpath; the launcher reports the wall time of each helper process, which includes starting that helper's JVM. The application's own start-up is Spring's `Started ... in N seconds`.
- Every restart records whether the JVM actually used the archive, so a run where an arm did not behave as intended is visible (the *Validity* table in each summary).

## What is in this directory

```
benchmarks/
  README.md                this file
  spring-heavy/            the benchmark application: Spring Boot with web, JPA/Hibernate, security,
                           validation, actuator and caching (69 JARs), built as an extracted image
  results/<timestamp>-<app>[-<conditions>]/
    summary.md             the tables
    results.csv            one row per measured restart, every phase and timing
    environment.json       machine, Docker, kind node, Kubernetes, tool versions, git commit, exact command
    registry.txt           registry latency and archive download speed as seen from the node
    raw/<arm>-<round>.json         the pod object of that restart (as Kubernetes returned it)
    raw/<arm>-<round>.fetch.log    the fetch init container's log (timing line included)
    raw/<arm>-<round>.app.log      the application container's log (launcher and run timing, Spring start line)
```

## Reproduce it

Needs docker, kind, kubectl, Helm 3, Java 21 and Maven (to build the CLI), python3 and network access.

```bash
scripts/bench-cluster.sh                                  # 12 rounds, no pod limits
scripts/bench-cluster.sh --app-cpu 1 --app-memory 1Gi     # requests = limits on the application container
scripts/bench-cluster.sh --app example                    # the small example app instead
scripts/bench-cluster.sh --app spring-heavy --jdk 25       # use JDK 25 for the Spring workload
scripts/bench-cluster.sh --restarts 30 --out /tmp/mine    # more rounds, other output directory
KEEP=1 scripts/bench-cluster.sh                           # keep the cluster for poking around
```

To re-derive the tables from a stored run, with no cluster: `python3 scripts/bench_summarize.py benchmarks/results/<run>`. To check a number, open `results.csv` or the `raw/` pod JSON: every figure in `summary.md` is computed from them.

## Cluster specification

Recorded by `scripts/bench_env.py` in `environment.json` for every run: host CPU model, logical CPUs and memory; the CPUs and memory Docker gives its VM; the kind node image and its Docker and cgroup limits (`nano_cpus`, `cpu_quota`, `cpuset_cpus`, `memory_bytes`, `cpu.max`; 0 or `max` means unlimited); Kubernetes and containerd versions, node capacity and allocatable; the CPU and memory limits of the application container (`none` by default); tool versions; the git commit, and whether the tree had uncommitted changes; and the exact command. The pod QoS class and resources are in each `raw/*.json`.

Things to know when comparing with another environment:

- **Registry latency is a best case.** The registry is a container one network hop from the node; `registry.txt` records the request time and the archive download speed from the node. A registry in another zone, or ECR, adds its own round trips and a slower download, which falls entirely on the `cached` arm. This study does not measure that; the numbers here are a lower bound on the cache's cost.
- **No pod limits by default.** The JVM then sees all the CPUs Docker has. With `--app-cpu 1` it sees one, as in many production clusters, and starts much more slowly; that changes the benefit and, because the helper JVMs run in the *application* container's limits only if you set them there, not the cost. The injector does not copy resource limits to the generated `fetch` init container, so that container is unlimited. Inline image-volume fetch runs under the application container limit.
- **kind on Docker Desktop is a virtual machine on a laptop.** Timings are noisy and not representative of a production node. The point is the *shape* (which phases exist and how large they are relative to each other) and the paired comparison, not the absolute seconds.

## Limits of the study

- Four application profiles: a small Spring Boot example, a heavier Spring Boot application, Jenkins LTS and SonarQube. Other frameworks and sizes are not covered (planned for the 0.3.0 beta).
- A local registry; no network cost, no authentication round trips (ECR's token exchange adds some).
- Image pulls excluded by design (see above).
- Single node, one pod at a time: no contention between replicas starting together, which is the autoscaling case where the cache could matter more.
- Arms differ only by mnemo-warmup; the application image is identical.

## Results

Runs are committed with their full `summary.md` (the end-to-end breakdown), `results.csv`, `environment.json`, `registry.txt` and the raw pod objects and logs. Every run: 12 measured rounds per arm, Apple M5 laptop, kind on Docker Desktop. Medians; pod timestamps have 1 s resolution.

| Run | Application | Limits | Tools delivery |
|---|---|---|---|
| [`20261111-spring-heavy-no-limits-clean`](results/20261111-spring-heavy-no-limits-clean/summary.md) | spring-heavy, JDK 21 | none | init containers (Java helpers) |
| [`20261111-spring-heavy-1cpu-1gi`](results/20261111-spring-heavy-1cpu-1gi/summary.md) | spring-heavy, JDK 21 | 1 CPU, 1 GiB | init containers (Java helpers) |
| [`20261111-spring-heavy-1cpu-1gi-native`](results/20261111-spring-heavy-1cpu-1gi-native/summary.md) | spring-heavy, JDK 21 | 1 CPU, 1 GiB | init containers (native `fetch` and `run`) |
| [`20261111-spring-heavy-1cpu-1gi-imagevolume`](results/20261111-spring-heavy-1cpu-1gi-imagevolume/summary.md) | spring-heavy, JDK 21 | 1 CPU, 1 GiB | `image` volume |
| [`20261113-example-1cpu-1gi-imagevolume`](results/20261113-example-1cpu-1gi-imagevolume/summary.md) | small Spring Boot example (31 JARs, 20 MiB), JDK 21 | 1 CPU, 1 GiB | `image` volume |
| [`20261111-jenkins-1cpu-1gi`](results/20261111-jenkins-1cpu-1gi/summary.md) | Jenkins 2.492.3, JDK 21 | 1 CPU, 1 GiB | init containers (Java helpers); header says "0 JARs", a harness bug fixed since |
| [`20261111-jenkins-1cpu-1gi-native`](results/20261111-jenkins-1cpu-1gi-native/summary.md) | Jenkins 2.492.3, JDK 21 | 1 CPU, 1 GiB | init containers (native helpers) |
| [`20261111-jenkins-1cpu-1gi-imagevolume`](results/20261111-jenkins-1cpu-1gi-imagevolume/summary.md) | Jenkins 2.492.3, JDK 21 | 1 CPU, 1 GiB | `image` volume |
| [`20261111-sonarqube-2cpu-3gi`](results/20261111-sonarqube-2cpu-3gi/summary.md) | SonarQube 26.11, JDK 25 | 2 CPU, 3 GiB | init containers (native helpers) |

The small example's plain / cache-miss / cached medians were 2.5 / 3 / 2 seconds. The cached and plain ranges both include 2–3 seconds, and pod timestamps have one-second resolution; this is a local data point, not a general speed claim. All 12 cached pods used the archive. Other Docker containers were running on the host during this run. The run records base commit `0b0fecc` and a dirty worktree containing this feature’s implementation.

### The baseline, and what changed

The first two runs measured the original Java helpers. The archive shortened the application's own start by 0.6 to 1.3 s, but pod start-to-Ready did not improve, because the helpers cost about 1 s:

| spring-heavy | no limits | 1 CPU / 1 GiB |
|---|---|---|
| Pod start-to-Ready, `none` / `nocache` / `cached` | 3 s / 4 s / 4 s | 5 s / 6 s / 5 s |
| Application start (`Started ... in`), without / with the archive | 1318 ms / 696 ms | 3136 ms / 1872 ms |
| Time the helpers take before the app starts, `cached` | about 990 ms | about 1050 ms |

Native `fetch` and `run` (Go, [ADR 0010](../docs/adr/0010-native-run-planner.md) and [0011](../docs/adr/0011-native-fetch.md)) brought the helpers from about 1050 ms to about 140 ms. What remains is Kubernetes sequencing the init containers (about 1.75 s from the pod to the application container starting, of which our code is about 0.1 s). Delivering the tools as an `image` volume removes the init containers ([ADR 0012](../docs/adr/0012-tools-as-an-image-volume.md)).

### Three applications at 1 CPU / 1 GiB (SonarQube at 2 CPU / 3 GiB)

Pod start-to-Ready, medians (range), by delivery mode:

| Application | `none` | init containers: `nocache` / `cached` | `image` volume: `nocache` / `cached` | Application start without / with the archive |
|---|---|---|---|---|
| spring-heavy | 4.5 s (init run), 5 s (`image` volume run) | 6 s / 5 s | 5 s / **3 s (3-4)** | 3186 ms / 1888 ms |
| Jenkins | 4 s (3-5) | 5 s / 5 s | 5 s / 4 s (3-5) | 2334 ms / 2025 ms |
| SonarQube | 15 s (14-16) | 16 s / 17 s | not run | not meaningful (see below) |

`none` differed by up to 0.5 s between runs of the same application, which is the noise floor at this timestamp resolution.

- **spring-heavy.** With the `image` volume the pod with a trained archive is ready 2 s (40%) sooner than the pod without mnemo-warmup. With init containers it is not.
- **Jenkins.** The archive saves about 0.3 s of a 2.3 s start (85% of its classes already load from the archive; the rest of the start is initialization). Pod readiness is at parity with the `image` volume and 1 s slower with init containers.
- **SonarQube.** The archive was used in 12 of 12 pods and `cached` minus `nocache` was 0 s: it covers only the small coordinator JVM, and the three JVMs that do the work are started by it ([issue #77](https://github.com/baokhang83/mnemo-warmup/issues/77)). With init containers `cached` is 1 s (range 1 to 3) slower than no mnemo-warmup. One earlier training run did not report `TRAINED` and the cause was not found; the harness now keeps `raw/train.log`.

**What this does not say.** Laptop, local registry, no image pull, one pod at a time. It says nothing about a remote registry (which would make `cached` slower), or about many pods starting together. The 0.3.0 beta plans a scale-out burst against a remote registry and an application-size study. The separate [JDK 21 AppCDS versus JDK 25 AOT study](jdk21-vs-jdk25.md) compares both on the same Spring workload.

The [remote-registry burst study](remote-registry-bursts.md) describes the new one-to-ten replica harness and its current evidence status.

The remote study completed three one-to-ten bursts against GHCR and Docker Hub for each JDK/probe combination. Archives were 83.3 MiB (JDK 21) and about 104.3 MiB (JDK 25); successful per-pod downloads took roughly 190–301 s median on this laptop. Two five-second-probe bursts hit the fetcher's 120-second idle timeout and still started uncached, demonstrating fail-open behavior. These timings are environment-specific; see the [study report and raw records](remote-registry-bursts.md).

A follow-up measured one fetch against nine simultaneous fetches of the same 83.3 MiB archive. The single fetch took about 9 s; concurrent fetches took about 91 s per pod and moved roughly nine archive copies over the shared connection. Fetch-container CPU measurements suggest network bandwidth was the main limit in this run. See the [single versus concurrent download results](results/20261114-archive-concurrency-local-kind/summary.md).

Two earlier runs were discarded because other benchmark runs were active on the same machine. No other benchmark ran alongside the published runs.
