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

> **Status: pre-alpha.** The whole pod-side chain works from the command line: `build` trains an
> archive, `push` stores it in an OCI registry, `fetch` (init container) retrieves and verifies it,
> and `run` (entrypoint) starts the JVM with it only if it still fits. `bench` measures the savings.
> The container image, the trainer CronJob, the Helm chart and an end-to-end example are not
> built yet. See the [roadmap](#roadmap) and the [project plan](docs/PLAN.md). Part of the Mnemo
> suite (`mnemo-jvm-warden`, `mnemo-cache`, `mnemo-warmup`).

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
            +------------------------+
 trainer    | trainer CronJob        |  computes the fingerprint, trains if missing, pushes
 (planned)  | (build + push)         |--------------------+
            +------------------------+                    v
                                             +------------------------+
                                             | archive store          |
                                             | OCI registry, tag =    |
                                             | fp-<fingerprint hex>   |
                                             +------------------------+
                                                         |
 every pod:  init container (mnemo-warmup fetch) <-------+  look up, verify digest
                |
                v  writes archive + manifest + flags file to a shared emptyDir
          app container: mnemo-warmup-exec -> mnemo-warmup run -> exec java [archive flag] -jar ...
                         (a plain start if nothing fits)
```

Solid today: `build`, `push`, `fetch`, `run`. Planned: the image, the trainer CronJob and the Helm chart
(a Helm *hook* was rejected because a failing trainer would fail the release; see
[ADR 0002](docs/adr/0002-training-trigger-and-traffic.md)).

- **Fingerprint:** `(image digest, JDK vendor and build, cache type, classpath hashes, GC and heap flags)`.
  The same fingerprint is recorded in a manifest next to the archive.
- **Train once, reuse everywhere:** the first training run for a fingerprint populates the
  store; later replicas and scale-ups just fetch.
- **Safe by default:** at pod start the order is matching AOT archive, then matching
  AppCDS archive, then a plain JVM start. A cache problem never prevents the app from
  starting: `fetch` exits 0 on every problem (unless `--strict`), and `run` starts the command
  unchanged whenever the archive does not fit this exact launch.
- **Verified before use:** every downloaded archive is checked against its content digest, and
  `run` re-hashes the archive against its manifest right before handing it to the JVM.

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

### Measure the savings: `bench`

```bash
mnemo-warmup bench --jar app.jar --runs 3
```

Starts the app with no archive, with AppCDS and (on JDK 25+) with AOT, in fresh JVMs, discards a
warm-up start and prints a Markdown table of median cold-start times. It takes the same application
options as `build`, plus `--runs` (timed starts per variant, default 3) and `--warmups` (default 1).

### Share the archive: `push`, `fetch`

```bash
# trainer: after `build`, upload the archive and manifest (tag = fp-<fingerprint hex>)
mnemo-warmup push --store oci://registry.example.com/team/warmup --registry-auth ~/.docker/config.json

# every pod, as an init container: look up, verify and write the archive
mnemo-warmup fetch --jar /app/app.jar --jvm-arg=-Xmx512m --image-digest sha256:... \
    --store oci://registry.example.com/team/warmup --out /mnemo-cache
```

`fetch` computes the live fingerprint the same way `build` does, so give it the same `--jar`,
`--jvm-arg` and `--image-digest` as the training run. With `--cache-type auto` it looks for an AOT
archive first (JDK 25+) and then AppCDS. A hit writes the archive, its manifest and a one-line
`mnemo-warmup.flags` file (for example `-XX:SharedArchiveFile=/mnemo-cache/app.jsa`) to `--out`.

| Option | Meaning |
|--------|---------|
| `--store` | `oci://host[:port]/repository`. Archives are OCI artifacts tagged `fp-<hex>`. |
| `--registry-auth` | Docker-config style JSON (a mounted `dockerconfigjson` Secret). `push` needs push scope, `fetch` pull scope. Defaults to `$MNEMO_WARMUP_REGISTRY_AUTH`, then anonymous. Basic and Bearer-token auth are supported; credential helpers and cloud workload identity are not yet. |
| `--plain-http` | Use http instead of https (local registries only). |
| `--out` | `fetch`: output directory (default `.mnemo-warmup/cache`). |
| `--cache-type` | `fetch`: `auto` (default), `appcds` or `aot`. |
| `--store-timeout-seconds` | `fetch`: per request and download stall (default 120). |
| `--strict` | `fetch`: exit 1 on a store problem and 2 on bad arguments, for CI. |

**`fetch` exits 0 by default**, whatever goes wrong (unreachable registry, bad login, corrupt
download, bad options). An init container that exits non-zero keeps the pod from starting, so the
failure is logged and any old flags file is removed instead. `push` uses exit codes normally:
`0` success, `1` store failure, `2` invalid input (including an archive that does not match its manifest).

### Start the app: `run`

```bash
# prints the final command; the wrapper script then execs it, so java is PID 1
mnemo-warmup run --print --cache-dir /mnemo-cache -- java -Xmx512m -jar /app/app.jar
```

`run` rebuilds the description of this exact launch and compares it with the manifest `fetch`
stored. Only if the JDK build, JVM options, classpath content, JAR modification times and the
directory the app was trained from all agree, it inserts `-XX:SharedArchiveFile=` or
`-XX:AOTCache=` right after `java`. Otherwise it starts the command exactly as given and logs why
(for example `-Xmx512m` against `-Xmx1g`). Only `java [options] -jar <jar> [args]` launches are
supported; anything else is left unchanged.

In a Dockerfile, use [`scripts/mnemo-warmup-exec`](scripts/mnemo-warmup-exec) as the entrypoint:

```dockerfile
# Extract a Spring Boot fat JAR at build time, so training and running use the same files.
RUN java -Djarmode=tools -jar app.jar extract --destination /app
ENTRYPOINT ["/opt/mnemo-warmup/mnemo-warmup-exec"]
CMD ["java", "-Xmx512m", "-jar", "/app/app.jar"]
```

The script runs `mnemo-warmup run --print` and then `exec`s the result, so the JVM replaces the
shell as PID 1 and receives Kubernetes' SIGTERM directly with no extra process. If `mnemo-warmup`
is missing or fails, it execs your original command. Images without `/bin/sh` can use
`mnemo-warmup run --spawn -- java ...` instead: it starts the command as a child process,
forwards SIGTERM, waits for it (up to `--stop-timeout-seconds`, default 25) and returns its exit
code, but keeps a second JVM resident.

Why extraction at build time: the JVM silently ignores an archive when a classpath JAR's size,
modification time or path differs from training. Run the same extracted files, at the same path,
in the trainer and in the app. If a Spring Boot fat JAR is launched directly, `run` says so and
starts it plain. See [ADR 0003](docs/adr/0003-run-exec-model-and-layout.md).

`run` options: `--cache-dir` (default `$MNEMO_WARMUP_CACHE_DIR`, then `.mnemo-warmup/cache`),
`--image-digest` (default `$MNEMO_WARMUP_IMAGE_DIGEST`), `--print`, `--spawn`, `--stop-timeout-seconds`.

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

File modification times, the directory the app was launched from, and the JDK's install path are
recorded but are not part of the fingerprint, because they can legitimately differ between a trainer
and a replica. `run` checks the modification times and the launch directory separately, since the
JVM does. Editing the file by hand makes it fail to load, because the recorded fingerprint no
longer matches. The manifest also stores the archive's size and SHA-256.

## What works today

- Target-JDK detection (`--java-home`, `$JAVA_HOME`, or the running JVM) and the choice
  between AppCDS and the AOT cache.
- Spring Boot fat-JAR extraction on the target JDK.
- The training runner: starts the app with the recording flag, waits for the exit mode,
  stops it with SIGTERM so the JVM writes the archive, and verifies the archive exists.
- Exit modes `spring-refresh` and `http-probe`.
- The manifest and fingerprint, and a function that explains how two environments differ.
- `bench`, `push` and `fetch` against an OCI registry (checked against a real `registry:2`), and `run`
  with the exec wrapper (a real JVM was started with a planned archive and loaded classes from it).
- `warmup-core` is on [Maven Central](https://central.sonatype.com/artifact/io.github.baokhang83.mnemo/warmup-core)
  as `io.github.baokhang83.mnemo:warmup-core`.

Not there yet: the container image, the trainer CronJob, the Helm chart, and an end-to-end test on a
real cluster (see the [roadmap](#roadmap)).

## Limitations

- **Linux and macOS only.** Stopping the app with SIGTERM is what makes the JVM write the
  archive; Windows kills the process instead.
- **AOT needs JDK 25+.** JDK 24 has the AOT cache but a two-step flow that is not
  supported yet; it uses AppCDS.
- **Tested with small apps, not yet with a real Spring Boot application.** The unit and
  process-level tests use tiny JVM apps. To check your own Spring Boot 3.3+ JAR, run
  `mvn verify -pl warmup-core -Dwarmup.test.springBootJar=/path/to/app.jar`.
- AppCDS behaviour on JDK 21-24 was checked in Docker ([results](docs/appcds-jdk-matrix.md)); CI runs
  the tests on 21 and 25.
- **Registry login:** Docker-config Basic/Bearer auth only. ECR/GCR/ACR credential helpers and
  workload-identity token exchange are not implemented, and only plain registries were tested (not TLS
  or a cloud registry).
- **`run` hashes the classpath JARs and the archive at start.** That costs startup time on a large
  classpath; it has not been measured yet.

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
warmup-core/   engine: JDK detection, extraction, training, manifest, OCI store, fetch/run logic
warmup-cli/    command-line interface (picocli): build, bench, push, fetch, run
coverage/      aggregates JaCoCo coverage for the badge (not published)
scripts/       mnemo-warmup-exec entrypoint wrapper; Docker checks used for the AppCDS matrix
docs/PLAN.md   design, decisions and roadmap
docs/adr/      decisions: archive store, training trigger, run/exec model
```

Planned: `image/` (init-container and trainer image), `deploy/helm/` (chart) and
`examples/` (sample Spring Boot app).

## Roadmap

**Phase 1: engine and CLI (v0.1.0, released)**
- [x] Project skeleton, JDK detection and flag selection
- [x] `cacheType` option with validation against the JDK
- [x] Spring Boot fat-JAR extraction
- [x] Training runner (`spring-refresh` and `http-probe` exit modes)
- [x] Fingerprint and manifest
- [x] `bench`: none vs AppCDS vs AOT

**Phase 2: Kubernetes delivery (v0.2.0)**
- [x] Decisions: archive store (OCI), training trigger (CronJob + Lease), run/exec model
- [x] Archive store backend with `push`
- [x] `fetch` (init container) and `run` (entrypoint)
- [ ] Container image for the init container and trainer
- [ ] Trainer CronJob and Helm chart
- [ ] Example app tested on kind with JDK 21 and JDK 25

**Phase 3: automation (v0.3.0)**
- [ ] Investigate which JVM flags invalidate an archive (multi-profile archives), metrics
- [ ] Warden integration (implemented in the warden repo)

Design decisions and their reasons are in [docs/PLAN.md](docs/PLAN.md) and [docs/adr/](docs/adr/).

## Contributing

The project is at a very early stage and the design is still moving. Issues and
discussion about the open decisions in the plan are the most useful contribution right
now.

## License

Apache License 2.0. See [LICENSE](LICENSE).
