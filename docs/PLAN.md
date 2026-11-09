# mnemo-warmup: project plan (v2, Kubernetes-first)

Self-healing AppCDS / Project Leyden AOT caches for JVM workloads on Kubernetes.
Part of the Mnemo suite (`mnemo-jvm-warden`, `mnemo-cache`, `mnemo-warmup`).

> Changes from v1: the Maven plugin is dropped. The primary delivery is an
> init-container plus Helm chart, with a mutating webhook later. The CLI and core
> engine stay, because they run inside the cluster images.

## Problem
AppCDS / Leyden AOT caches cut JVM startup 30-60%, but are rarely used:
- Producing an archive needs a training run (unpack, start, drive code paths, clean exit).
- Archives are bound to the exact JDK build, classpath and JVM flags; a mismatch
  silently disables them or fails startup.
- Training in CI lacks real config, secrets and dependencies.
- Nobody measures the savings.

## Core idea
Train once per `(image digest + JDK build + JVM flags)` in the real cluster, store the
archive, and have every replica fetch it before the app starts. If no valid archive
exists, the pod boots normally. Never crash because of a cache.

## Architecture

```
            +-------------------+
 first pod  |  trainer Job      |  runs app with training flags, clean exit
 or Job --> |  (mnemo-warmup build)|-------------------+
            +-------------------+                   v
                                           +----------------+
                                           | archive store  |  key = fingerprint
                                           | (OCI / S3 / PVC)|
                                           +----------------+
                                                    |
 every pod:  init container (mnemo-warmup fetch) ----->|  lookup, verify manifest
                |                                   
                v  copies archive to emptyDir
          app container: java -XX:SharedArchiveFile / -XX:AOTCache  (or fallback)
```

Components:
- **core** (Java 21+): JDK detection, strategy selection (AppCDS on 21-24, Leyden AOT on 25+ (single-step JEP 514 flags)),
  fat-JAR extraction, training runner, fingerprint and manifest, bench.
- **cli**: `build`, `bench`, `fetch`, `run`.
- **image**: container image bundling the CLI, used as init container and trainer.
- **deploy/helm**: chart adding the init container, volume and trainer Job.
- **webhook** (later): injects the above into labelled Deployments.
- **operator / CRD**: only if real users need it.

## Open decisions
1. **Archive store:** OCI registry (easy auth, content-addressed) vs S3/GCS vs RWX PVC.
   Leaning OCI or S3; archives are hundreds of MB and RWX is not universal.
2. **Who trains:** one-off Job per fingerprint vs first pod to start.
3. **Training traffic:** startup only, probe-driven, or user warmup script.
4. **Fingerprint contents:** JDK vendor/build, classpath hashes, GC and heap flags.

## Repo layout
```
core/  cli/  image/  deploy/helm/  webhook/ (later)  examples/  docs/
```
Currently the modules are `mnemo-warmup-core` and `mnemo-warmup-cli`.

## Roadmap

### Phase 1: engine and CLI (v0.1.0)
- [x] Maven reactor scaffold, JDK detection and flag selection (`CacheStrategy`)
- [ ] Spring Boot fat-JAR extraction (`-Djarmode=tools extract`)
- [ ] Training runner, exit modes: `spring-refresh`, `http-probe`
- [ ] Fingerprint and `mnemo-warmup.manifest.json`
- [ ] `mnemo-warmup bench`: 3 cold starts with vs without, Markdown savings table

### Phase 2: Kubernetes delivery (v0.2.0)
- [ ] `mnemo-warmup fetch` (init container): look up by fingerprint, verify, copy to emptyDir
- [ ] `mnemo-warmup run` (entrypoint): validate manifest, add archive flag or fall back cleanly
- [ ] Archive store backend (pick from open decision 1), including upload from trainer
- [ ] Container image, trainer Job template, Helm chart
- [ ] Example Spring Boot app, tested on kind

### Phase 3: automation and ecosystem (v0.3.0)
- [ ] Mutating webhook: label a Deployment, init container and volume injected
- [ ] Multi-profile archives (peak vs off-peak heap) for `mnemo-jvm-warden`
- [ ] Savings metrics exported (startup ms, RSS) for dashboards

### Later
- [ ] Operator with an `AotCache` CRD, if demand justifies it
- [ ] Optional build-time integrations (Gradle, GitHub Action) for non-Kubernetes users

## Decisions made
- Maven multi-module, `--release 21`, tested on JDK 25.
- Base package `io.github.baokhang83.mnemo.warmup`; picocli in the CLI module only.
- Java for the in-cluster components; Go only if an operator is built.
- Start with Helm, then webhook, then operator.

## Risks
- Spring Boot and JDK 26 (JEP 514) are making this easier natively; keep the value in
  cluster-aware sharing and safe fallback, not in wrapping flags.
- Archive size and storage cost.
- Market is unproven; validate with a few JVM platform teams before the webhook phase.
