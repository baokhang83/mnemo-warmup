# Benchmarks

Measurements of what mnemo-warmup does to **pod start-to-Ready**, with the method, the raw data and the machine specification, so that anyone can rerun the study or check a number.

The question is not "does the JVM start faster with an archive" (it does). It is "does a *pod* become Ready sooner once you add the init containers, the archive download, the digest checks and the helper JVMs that the cache needs", and where the time goes.

## Method

`scripts/bench-cluster.sh` creates a throw-away [kind](https://kind.sigs.k8s.io/) cluster and an archive registry (`registry:2` on the kind network), builds the tools image from this checkout and the application image, and deploys **three arms** of the same chart (`examples/annotated-app`), all running the same application image:

| arm | what runs |
|---|---|
| `none` | the plain Deployment, no mnemo-warmup |
| `nocache` | mnemo-warmup on (init containers, launcher, helper JVMs), but the archive repository is empty: the cost of the machinery with no benefit |
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
scripts/bench-cluster.sh --restarts 30 --out /tmp/mine    # more rounds, other output directory
KEEP=1 scripts/bench-cluster.sh                           # keep the cluster for poking around
```

To re-derive the tables from a stored run, with no cluster: `python3 scripts/bench_summarize.py benchmarks/results/<run>`. To check a number, open `results.csv` or the `raw/` pod JSON: every figure in `summary.md` is computed from them.

## Cluster specification

Recorded by `scripts/bench_env.py` in `environment.json` for every run: host CPU model, logical CPUs and memory; the CPUs and memory Docker gives its VM; the kind node image and its Docker and cgroup limits (`nano_cpus`, `cpu_quota`, `cpuset_cpus`, `memory_bytes`, `cpu.max`; 0 or `max` means unlimited); Kubernetes and containerd versions, node capacity and allocatable; the CPU and memory limits of the application container (`none` by default); tool versions; the git commit, and whether the tree had uncommitted changes; and the exact command. The pod QoS class and resources are in each `raw/*.json`.

Things to know when comparing with another environment:

- **Registry latency is a best case.** The registry is a container one network hop from the node; `registry.txt` records the request time and the archive download speed from the node. A registry in another zone, or ECR, adds its own round trips and a slower download, which falls entirely on the `cached` arm. This study does not measure that; the numbers here are a lower bound on the cache's cost.
- **No pod limits by default.** The JVM then sees all the CPUs Docker has. With `--app-cpu 1` it sees one, as in many production clusters, and starts much more slowly; that changes the benefit and, because the helper JVMs run in the *application* container's limits only if you set them there, not the cost. The injector does not copy resource limits to the generated `fetch` init container, so `fetch` is unlimited in both modes.
- **kind on Docker Desktop is a virtual machine on a laptop.** Timings are noisy and not representative of a production node. The point is the *shape* (which phases exist and how large they are relative to each other) and the paired comparison, not the absolute seconds.

## Limits of the study

- One application family (Spring Boot). A non-Spring application (`http-probe`) and other frameworks are not covered.
- One JDK (21, AppCDS). The Leyden AOT cache on JDK 25 is not measured here.
- A local registry; no network cost, no authentication round trips (ECR's token exchange adds some).
- Image pulls excluded by design (see above).
- Single node, one pod at a time: no contention between replicas starting together, which is the autoscaling case where the cache could matter more.
- Arms differ only by mnemo-warmup; the application image is identical.

## Results

Two complete runs are committed, each with its full `summary.md` (the end-to-end breakdown), `results.csv`, `environment.json`, `registry.txt` and the raw pod objects and logs:

- [`20261111-spring-heavy-no-limits-clean`](results/20261111-spring-heavy-no-limits-clean/summary.md): no pod limits (the JVM sees all 10 CPUs).
- [`20261111-spring-heavy-1cpu-1gi`](results/20261111-spring-heavy-1cpu-1gi/summary.md): application container at 1 CPU and 1 GiB, requests = limits.

Both: spring-heavy, 12 measured rounds per arm, Apple M5 laptop, kind on Docker Desktop, JDK 21. Medians; pod timestamps have 1 s resolution.

| | no limits | 1 CPU / 1 GiB |
|---|---|---|
| Pod start-to-Ready, `none` | 3 s | 5 s |
| Pod start-to-Ready, `nocache` | 4 s | 6 s |
| Pod start-to-Ready, `cached` | 4 s | 5 s |
| `cached` minus `none`, paired per round | +1.5 s (range +1 to +2) | 0 s (range 0 to +1) |
| Application start (`Started ... in`), without / with the archive | 1318 ms / 696 ms | 3136 ms / 1872 ms |
| Time the helpers (`fetch` + `run`) take before the app starts, `cached` | about 990 ms | about 1050 ms |

**What this says.** The archive does what it is for: the application starts 0.6 to 1.3 s sooner. On this application and this setup that gain is matched or exceeded by the cost of the helpers, which mostly start two small JVMs before the application can start (`fetch` about 770 to 790 ms of wall time, `run` about 200 to 280 ms; the registry download itself is about 270 to 280 ms). So **pod start-to-Ready did not get shorter**: it was equal with a 1 CPU limit and about 1.5 s longer without limits. The `nocache` arm shows the price of the machinery alone: 1 s or more.

**What this does not say.** It is one application family on one laptop with a local registry and no image pull. The result may be different for an application whose start-up takes much longer than 1.3 to 3.1 s, where the same fixed helper cost is a smaller share, and for pods that start together on a busy node. It also says nothing about a remote registry, which would make `cached` slower. Reducing the helper cost (for example one helper JVM instead of two) is the obvious lever; these numbers are the baseline for it.

An earlier no-limits run and a first 1 CPU run were discarded because other benchmark runs were active on the same machine while they ran. The two published runs ran alone.
