<div align="center">
  <img width="300" height="300" alt="image" src="https://github.com/user-attachments/assets/70f9a302-2f30-409c-b170-8f437391778a" />
  <h1>mnemo-warmup</h1>
</div>

**Cluster-aware startup caches for JVM workloads on Kubernetes.**
Train an AppCDS or Project Leyden AOT cache once, share it with every replica through an
init container, and fall back to a normal start whenever the cache doesn't match.

[![build](https://github.com/baokhang83/mnemo-warmup/actions/workflows/build.yml/badge.svg)](https://github.com/baokhang83/mnemo-warmup/actions/workflows/build.yml)
[![coverage](https://raw.githubusercontent.com/baokhang83/mnemo-warmup/refs/heads/gh-pages/badges/jacoco.svg)](https://baokhang83.github.io/mnemo-warmup/)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Status: pre-alpha](https://img.shields.io/badge/status-pre--alpha-orange)
![Java 21+](https://img.shields.io/badge/java-21%2B-informational)

> **Status: pre-alpha.** The command line can already extract a Spring Boot JAR and train
> an AppCDS or AOT archive for it. The manifest/fingerprint, benchmarking and the
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

## Usage

Build the CLI (see [Building from source](#building-from-source)), then train an archive:

```bash
# Spring Boot 3.3+ fat JAR: extract it, then let Spring end the run after the context refresh
mnemo-warmup build --jar app.jar

# Any framework: wait for a health endpoint, send some warmup traffic, then stop the app
mnemo-warmup build --jar app.jar --exit-mode http-probe \
    --url http://localhost:8080/actuator/health \
    --warmup-url /api/items --warmup-url /api/orders

# Train for a different JDK than the one running the tool, and force a cache type
mnemo-warmup build --jar app.jar --java-home /opt/jdk-21 --cache-type appcds
```

The archive is written to `<work-dir>/app.aot` or `<work-dir>/app.jsa` (default work
directory `.mnemo-warmup`), next to a `mnemo-warmup.manifest.json` (see below). Run the application later with the same JDK, JAR and flags and
pass `-XX:AOTCache=<file>` (AOT) or `-XX:SharedArchiveFile=<file>` (AppCDS).

| Option | Meaning |
|--------|---------|
| `--jar` | Application JAR. Spring Boot 3.3+ fat JARs are extracted with `-Djarmode=tools`; other executable JARs are used as they are. |
| `--java-home` | JDK of the target app. Defaults to `$JAVA_HOME`, then the JDK running the tool. |
| `--cache-type` | `auto` (default), `appcds` or `aot`. `auto` picks AOT on JDK 25+, AppCDS otherwise. |
| `--exit-mode` | `spring-refresh` (default) or `http-probe`. |
| `--url` | `http-probe`: endpoint that must return 2xx. Required for that mode. |
| `--warmup-url` | `http-probe`: GET request after the probe succeeds (repeatable; absolute URL or path). |
| `--warmup-script` | `http-probe`: program run after the warmup requests; gets the base URL, must exit 0. |
| `--poll-interval-millis` | `http-probe`: pause between probes (default 500). |
| `--timeout-seconds` | How long the app may take to become ready (default 300). |
| `--jvm-arg` | JVM option for the training run, e.g. `--jvm-arg=-Xmx512m` (repeatable). Use the options the app will run with. |
| `--image-digest` | Container image digest to record in the fingerprint. |
| `--work-dir` | Where files are written (default `.mnemo-warmup`). |

Exit codes: `0` success, `1` training failed (the message includes the app's last log
lines), `2` invalid input. A failed run never leaves an archive behind.

**Exit modes.** `spring-refresh` passes `-Dspring.context.exit=onRefresh`, so only the
startup path is trained. `http-probe` also trains the classes used while serving the
warmup requests you send, and works for any framework.

## The manifest and fingerprint

`build` writes `mnemo-warmup.manifest.json` next to the archive. It records what the archive
was trained against, and a `fingerprint` (`sha256:...`) derived from it:

- the cache type and the JDK vendor and full build,
- the optional `--image-digest`,
- the JVM options that can invalidate an archive: heap size and percentages, GC choice,
  `ObjectAlignmentInBytes`, compressed oops/class pointers, compact object headers. This is a
  deliberately conservative list (it also reads `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS`);
  [#20](https://github.com/baokhang83/mnemo-warmup/issues/20) will measure which of these really
  matter, and others such as `-D` properties are ignored,
- every classpath entry in order, with its size and SHA-256 (the launched JAR plus what its
  `Class-Path` lists, which is the `lib/` directory for an extracted Spring Boot JAR).

File modification times and the JDK's install path are recorded or ignored but are not part of
the fingerprint. Editing the file by hand makes it fail to load, because the recorded
fingerprint no longer matches. The manifest also stores the archive's size and SHA-256.

## What works today

- Target-JDK detection (`--java-home`, `$JAVA_HOME`, or the running JVM) and the choice
  between AppCDS and the AOT cache.
- Spring Boot fat-JAR extraction on the target JDK.
- The training runner: starts the app with the recording flag, waits for the exit mode,
  stops it with SIGTERM so the JVM writes the archive, and verifies the archive exists.
- Exit modes `spring-refresh` and `http-probe`.
- The manifest and fingerprint, including a function that explains how two environments
  differ (used by the upcoming `run` and `fetch`).

Not there yet: `bench`, `fetch`, `run`, the container image and the Helm chart (see the [roadmap](#roadmap)).

## Limitations

- **Linux and macOS only.** Stopping the app with SIGTERM is what makes the JVM write the
  archive; Windows kills the process instead.
- **AOT needs JDK 25+.** JDK 24 has the AOT cache but a two-step flow that is not
  supported yet; it uses AppCDS.
- **Tested with small apps, not yet with a real Spring Boot application.** The unit and
  process-level tests use tiny JVM apps. To check your own Spring Boot 3.3+ JAR, run
  `mvn verify -pl warmup-core -Dwarmup.test.springBootJar=/path/to/app.jar`.
- AppCDS on JDK 21-24 is exercised by CI on 21 only.

## Building from source

Requirements: JDK 21+ and Maven 3.9+. The code compiles with `--release 21` and is tested
on JDK 25.

```bash
mvn verify
java -jar warmup-cli/target/mnemo-warmup.jar --version
java -jar warmup-cli/target/mnemo-warmup.jar build --help
```

The build produces a self-contained `warmup-cli/target/mnemo-warmup.jar`; the examples above
call it as `mnemo-warmup`.

## Repository layout

```
warmup-core/   engine: JDK detection, JAR extraction, training runner, exit modes
warmup-cli/    command-line interface (picocli)
docs/PLAN.md         design, open decisions and roadmap
```

Planned: `image/` (init-container and trainer image), `deploy/helm/` (chart) and
`examples/` (sample Spring Boot app).

## Roadmap

**Phase 1: engine and CLI (v0.1.0)**
- [x] Project skeleton, JDK detection and flag selection
- [x] `cacheType` option with validation against the JDK
- [x] Spring Boot fat-JAR extraction
- [x] Training runner (`spring-refresh` and `http-probe` exit modes)
- [x] Fingerprint and manifest
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
