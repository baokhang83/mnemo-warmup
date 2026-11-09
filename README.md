# mnemo-warmup

**Cluster-aware startup caches for JVM workloads on Kubernetes.**
Train an AppCDS or Project Leyden AOT cache once, share it with every replica through an
init container, and fall back to a normal start whenever the cache doesn't match.

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Status: pre-alpha](https://img.shields.io/badge/status-pre--alpha-orange)
![Java 21+](https://img.shields.io/badge/java-21%2B-informational)

> **Status: pre-alpha.** The repository currently contains the project skeleton and the
> JDK/cache-type selection logic. Training, fingerprinting, benchmarking and the
> Kubernetes components are not implemented yet. See the [roadmap](#roadmap) and the
> [project plan](docs/PLAN.md). Part of the Mnemo suite
> (`mnemo-jvm-warden`, `mnemo-cache`, `mnemo-warmup`).

---

## Why

The JVM can start much faster when it reuses an archive of already-loaded and linked
classes. Depending on the application, startup improvements of 30-60% are commonly
reported for **AppCDS** (JDK 13+) and **Project Leyden's AOT cache** (JDK 24+; single-step
creation since JDK 25, JEP 514). Almost nobody runs them in production, because:

1. **Training is a ceremony.** You unpack the JAR, run the app, exercise it, and stop it
   cleanly so the archive is written.
2. **Archives are fragile.** They are bound to the exact JDK build, classpath and JVM flags.
   A mismatch silently disables the archive or fails startup.
3. **CI is the wrong place to train.** Real config, secrets and dependencies live in the
   cluster, not in the build.
4. **Nobody measures it.** There is no routine way to see what an archive actually saves.

## How it works

```
            +-----------------------+
 trainer    | trainer Job           |  runs the app with training flags, clean exit
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
                v  writes archive + flags file to a shared emptyDir
          app container: java <archive flag> ...   (or a plain start if nothing matched)
```

- **Fingerprint:** `(image digest, JDK vendor and build, cache type, classpath hashes, GC and heap flags)`.
  The same fingerprint is recorded in a manifest next to the archive.
- **Train once, reuse everywhere:** the first training run for a fingerprint populates the
  store; later replicas and scale-ups just fetch.
- **Safe by default:** at pod start the order is matching AOT archive, then matching
  AppCDS archive, then a plain JVM start. A cache problem never prevents the app from
  starting.

## Relationship to mnemo-jvm-warden

[`mnemo-jvm-warden`](https://github.com/baokhang83/mnemo-jvm-warden) resizes running pods
in place; mnemo-warmup makes the pods that do start (deploys, scale-ups) boot faster. They
are separate projects. Warmup works on its own; the planned integration is that warden
depends on `warmup-core` as a library and handles injecting the init container into
workloads, so this repo does not ship its own controller or webhook.

## Cache types

AppCDS and the AOT cache are alternatives: the JVM is given one archive per run
(`-XX:SharedArchiveFile` or `-XX:AOTCache`). The AOT cache builds on the CDS machinery and
stores more.

| JDK | AppCDS (`.jsa`) | AOT cache (`.aot`) |
|-----|-----------------|--------------------|
| 21-23 | Supported | Not available |
| 24 | Supported | Needs a two-step flow; not supported yet |
| 25+ | Supported (fallback) | **Preferred**, single step (`-XX:AOTCacheOutput`) |

`cacheType: auto` (the default) chooses AOT on JDK 25+ and AppCDS otherwise. You can also
force `appcds` or `aot`.

## Planned usage

> None of the commands below exist yet. They describe the intended interface.

```bash
# Train an archive for a Spring Boot fat JAR and write the manifest
mnemo-warmup build --jar app.jar --exit-mode spring-refresh

# Or drive the app through a health endpoint before shutting it down
mnemo-warmup build --jar app.jar --exit-mode http-probe \
    --url http://localhost:8080/actuator/health

# Compare cold starts: no cache vs AppCDS vs AOT, printed as a Markdown table
mnemo-warmup bench --jar app.jar

# Kubernetes: init container fetches the archive, entrypoint validates and starts the JVM
mnemo-warmup fetch --store <store-url> --out /cache
mnemo-warmup run -- java -jar app.jar
```

## What works today

- `CacheStrategy` in `warmup-core`: picks AppCDS or AOT from the JDK feature version
  and produces the matching training and runtime JVM flags (unit tested).
- `warmup-cli`: a [picocli](https://picocli.info) application packaged as an
  executable JAR. `build` currently only prints the detected JDK and chosen cache type;
  `bench` is a stub.

The AOT flags were verified against JDK 25.0.1. AppCDS on JDK 21-24 has not been tested
yet.

## Building from source

Requirements: JDK 21+ and Maven 3.9+. The code compiles with `--release 21` and is tested
on JDK 25.

```bash
mvn verify
java -jar warmup-cli/target/mnemo-warmup.jar --version
java -jar warmup-cli/target/mnemo-warmup.jar build
```

## Repository layout

```
warmup-core/   engine: JDK detection, cache type selection (more to come)
warmup-cli/    command-line interface
docs/PLAN.md         design, open decisions and roadmap
```

Planned: `image/` (init-container and trainer image), `deploy/helm/` (chart) and
`examples/` (sample Spring Boot app).

## Roadmap

**Phase 1: engine and CLI (v0.1.0)**
- [x] Project skeleton, JDK detection and flag selection
- [ ] `cacheType` option with validation against the JDK
- [ ] Spring Boot fat-JAR extraction
- [ ] Training runner (`spring-refresh` and `http-probe` exit modes)
- [ ] Fingerprint and manifest
- [ ] `bench`: none vs AppCDS vs AOT

**Phase 2: Kubernetes delivery (v0.2.0)**
- [ ] `fetch` (init container) and `run` (entrypoint) commands
- [ ] Archive store backend, container image, trainer Job, Helm chart
- [ ] Example app tested on kind with JDK 21 and JDK 25

**Phase 3: automation (v0.3.0)**
- [ ] Publish `warmup-core` to Maven Central, investigate which JVM flags invalidate an archive (multi-profile archives), metrics
- [ ] Warden integration (implemented in the warden repo)

Open design questions (archive store, who runs training, training traffic) are tracked in
[docs/PLAN.md](docs/PLAN.md).

## Contributing

The project is at a very early stage and the design is still moving. Issues and
discussion about the open decisions in the plan are the most useful contribution right
now.

## License

Apache License 2.0. See [LICENSE](LICENSE).
