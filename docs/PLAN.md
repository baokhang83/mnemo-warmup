# mnemo-warmup: project plan (v3, Kubernetes init container, AppCDS and/or AOT)

Cluster-aware startup caches for JVM workloads on Kubernetes: AppCDS (`.jsa`) and
Project Leyden AOT cache (`.aot`), trained once and shared by every replica through an
init container, with a safe fallback to a normal start.
Part of the Mnemo suite (`mnemo-jvm-warden`, `mnemo-cache`, `mnemo-warmup`).

> History: v2 dropped the Maven plugin for a Kubernetes-first design. v3 makes the
> cache type explicit: AppCDS and AOT are both first-class, selected per workload.

## Problem
AppCDS / Leyden AOT caches cut JVM startup 30-60%, but are rarely used:
- Producing an archive needs a training run (unpack, start, drive code paths, clean exit).
- Archives are bound to the exact JDK build, classpath and JVM flags; a mismatch
  silently disables them or fails startup.
- Training in CI lacks real config, secrets and dependencies.
- Nobody measures the savings.

## Core idea
Train once per fingerprint in the real cluster, store the archive, and have every
replica fetch it in an init container before the app starts. If no valid archive
exists, the pod boots normally. Never crash because of a cache.

Fingerprint = `(image digest, JDK vendor and build, cache type, classpath hashes, GC and heap flags)`.

## Cache types

AppCDS and the Leyden AOT cache are alternatives, not layers: the JVM is given one
archive per run (`-XX:SharedArchiveFile` or `-XX:AOTCache`). The AOT cache builds on the
CDS machinery and stores more (linked classes, and on JDK 25+ method profiles).

| JDK | AppCDS (`.jsa`) | AOT cache (`.aot`) |
|---|---|---|
| 21-23 | Supported (dynamic archive) | Not available |
| 24 | Supported | Exists, but needs two-step record/create; **not supported in v1** |
| 25+ | Supported (fallback) | **Preferred**, single step via `-XX:AOTCacheOutput` (JEP 514) |

- `cacheType: auto` (default): AOT on JDK 25+, AppCDS otherwise.
- `cacheType: appcds` or `aot`: force one. Forcing `aot` on JDK < 25 is a config error
  reported by the trainer and init container, and the app still starts uncached.
- Both can be trained for the same image (separate fingerprints). This lets `bench`
  compare them, and lets a rollout fall back from AOT to AppCDS if the AOT archive is
  rejected.
- The type is part of the fingerprint and the manifest.

Fallback chain at pod start: matching AOT archive, then matching AppCDS archive, then
plain JVM start. The JVM's own default behaviour on a mismatched archive is to ignore it
and continue; we never set flags that make a bad archive fatal.

## Architecture

```
            +-----------------------+
 trainer    | trainer Job           |  runs app with training flags, clean exit
 (once per  | (mnemo-warmup build)  |--------------------+
 fingerprint)+----------------------+                    v
                                             +------------------------+
                                             | archive store          |
                                             | key = fingerprint      |
                                             | (OCI / S3 / PVC)       |
                                             +------------------------+
                                                         |
 every pod:  init container (mnemo-warmup fetch) <-------+  lookup, verify manifest
                |
                v  writes archive + env file to shared emptyDir
          app container: java <archive flag> ...   (or plain start if nothing matched)
```

How the app picks up the archive: the init container writes the archive and a small
flags file into the `emptyDir`. The app container either runs through
`mnemo-warmup run` (preferred; validates the manifest against live JDK and flags, then
execs `java`) or sets `JAVA_TOOL_OPTIONS` from the flags file (no entrypoint change, less
validation).

Components:
- **core** (Java 21+): JDK detection, cache type selection, fat-JAR extraction, training
  runner, fingerprint and manifest, bench.
- **cli**: `build`, `bench`, `fetch`, `run`.
- **image**: JDK-free tools image carrying the CLI, the shell launcher and exec wrapper, and the static native
  launcher (`launcher/`) for shell-free images into the application image (`docs/adr/0004-tools-image.md`); the tool runs inside the app image.
- **deploy/helm**: chart adding the init container, volume and trainer Job.
- **Injection into workloads:** lives in `mnemo-jvm-warden` (see below), not in this repo.

## Relationship to mnemo-jvm-warden

`mnemo-jvm-warden` (Java) already ships a controller, CRDs (`WardenPolicy`,
`WardenBoost`), a sidecar and Helm charts, and publishes to Maven Central. Warden shrinks
and grows running pods in place without restarts; warmup speeds up the pods that do start
(deploys, scale-ups, node moves). They are complementary but separate:

- Separate repos. Warmup is usable without warden (plain init-container snippet).
- Integration is by library: warden depends on `warmup-core` (fingerprint and manifest
  API) in-process, instead of shelling out to the CLI.
- Injecting the init container into workloads is a warden feature (for example a
  `WardenPolicy` field), so no second controller or webhook is built here.
- Open technical question: which JVM flags change an archive's validity. If
  `SoftMaxHeapSize` does not, one archive can survive warden's day/night resizes.

## Open decisions
1. **Archive store:** DECIDED: OCI registry first, behind a put/get/exists interface
   (see `docs/adr/0001-archive-store.md`). S3/GCS may follow; RWX PVC not planned.
2. **Who trains:** DECIDED: chart-owned CronJob with a Lease, non-hook (see
   `docs/adr/0002-training-trigger-and-traffic.md`).
3. **Training traffic:** DECIDED: `spring-refresh` default, `http-probe` opt-in, no warmup
   script in v0.2.0 (same ADR).
4. **App pickup:** DECIDED: `run` is supported; `JAVA_TOOL_OPTIONS` from the flags file works
   but is unvalidated (see `docs/adr/0003-run-exec-model-and-layout.md`).
5. **AppCDS flavour:** dynamic archive on top of the JDK default archive vs static
   dump. Dynamic is simpler. Verified on 21-24: see `docs/appcds-jdk-matrix.md`.
6. **JAR modification times:** DECIDED: extract at image build time so trainer and runtime share
   the same files; `run` compares modification times and starts plain on a difference (same ADR).

## Repo layout
```
warmup-core/  warmup-cli/  image/  deploy/helm/  examples/  docs/
```
Maven coordinates: groupId `io.github.baokhang83.mnemo` (same as warden), artifacts
`warmup-core` and `warmup-cli`.

## Roadmap

### Phase 1: engine and CLI (v0.1.0)
- [x] Maven reactor scaffold, JDK detection and flag selection (`CacheStrategy`)
- [x] `cacheType` option (`auto`, `appcds`, `aot`) with validation against the JDK
- [x] Spring Boot fat-JAR extraction (`-Djarmode=tools extract`)
- [x] Training runner for both types, exit modes: `spring-refresh`, `http-probe`
- [x] Fingerprint and `mnemo-warmup.manifest.json` (includes cache type)
- [x] `mnemo-warmup bench`: 3 cold starts, none vs AppCDS vs AOT, Markdown savings table

### Phase 2: Kubernetes delivery (v0.2.0)
- [x] `mnemo-warmup fetch` (init container): look up by fingerprint, verify, copy to
      emptyDir; try AOT, then AppCDS, then give up cleanly
- [x] `mnemo-warmup run` (entrypoint): validate manifest, add archive flag or fall back
- [x] Archive store backend (open decision 1), including upload from trainer
- [x] Container image (tools image, see `docs/adr/0004-tools-image.md`)
- [x] Trainer CronJob, `train` command with a Lease, Helm chart with `cacheType` value (`docs/adr/0005-trainer-and-chart.md`)
- [x] Example Spring Boot app, tested on kind with JDK 21 (AppCDS) and JDK 25 (AOT) (`examples/`, `scripts/e2e-kind.sh`)

### Phase 2.5: simpler workload adoption (v0.2.5)
- [x] Offline injector: annotate a Deployment, preview, deploy through a Helm post-renderer (`docs/adr/0007-offline-injection-contract.md`, `docs/offline-injector.md`)
- [x] Shell-free native launcher, so distroless images work (`launcher/`, ADR 0004 amended)
- [x] Registry login providers and Amazon ECR through EKS workload identity (`docs/adr/0008-registry-auth-providers.md`, `docs/aws-workload-identity.md`)
- [x] Conservative retention guidance, no deletion (`docs/archive-retention.md`)
- [x] End-to-end check of the annotated path on kind with a distroless image, in CI on amd64 and arm64 (`scripts/e2e-kind-inject.sh`)
- [x] Adoption guide and configuration reference (`docs/configuration.md`, `docs/registry-auth.md`, `docs/operations.md`)
- [ ] Not verified yet: a live EKS and ECR, Helm 4, JDK 25 through the injector

### Phase 3: automation and ecosystem (v0.3.0)
- [x] Publish `warmup-core` to Maven Central so warden can depend on it (0.1.0, 0.2.0)
- [ ] Investigate which JVM flags invalidate an archive (e.g. `SoftMaxHeapSize` vs `-Xmx`)
      and support multi-profile archives accordingly
- [ ] Warden integration (implemented in the warden repo): inject the init container
- [ ] Savings metrics exported (startup ms, RSS) for dashboards
- [ ] Evidence: run it on many real applications (frameworks, sizes, JDKs, amd64 and arm64, private registries,
      several replicas) and publish the results, including pod start to Ready, not only JVM startup
- [ ] JDK 24 two-step AOT flow, only if users ask

### Later
- [ ] Optional build-time integrations (Gradle, GitHub Action) for non-Kubernetes users

## Decisions made
- Maven multi-module, `--release 21`, tested on JDK 25.
- Base package `io.github.baokhang83.mnemo.warmup`; picocli in the CLI module only.
- Java for all components (warden is Java too); no controller or webhook in this repo.
- Start with the standalone Helm chart; workload injection is done by warden.
- Maven coordinates aligned with warden: groupId `io.github.baokhang83.mnemo`.
- AOT from JDK 25 (single-step flags verified on 25.0.1); AppCDS for 21-24.

## Risks
- The JDK and Spring Boot are making this easier natively (JEP 514 already simplified the
  flags in 25); keep the value in cluster-aware sharing, fingerprinting and safe
  fallback, not in wrapping flags.
- Archive size and storage cost (AOT caches are larger than CDS archives).
- AppCDS behaviour on JDK 21-24 is verified (`docs/appcds-jdk-matrix.md`); the fingerprint
  rules (issue #20) are not yet measured with a real application.
- Market is unproven; validate with a few JVM platform teams before the warden integration.
