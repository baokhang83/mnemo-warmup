# mnemo-warmup reference

The details behind the [README](../README.md): the problem, the cache types, every command and its options, the container
image, the Helm chart and trainer, the manifest and fingerprint, and the current limits. For setting it up on a cluster, start
with the README.

## Why

The JVM can start faster when it reuses an archive of already-loaded and linked classes: **AppCDS** (JDK 13+) and
**Project Leyden's AOT cache** (JDK 24+; single-step creation since JDK 25, JEP 514). How much faster depends on the
application; this project's own (small) measurements are in the README. Using them in practice is awkward, because:

1. **Training is a ceremony.** You unpack the JAR, run the app, exercise it, and stop it
   cleanly so the archive is written.
2. **Archives are fragile.** They are bound to the exact JDK build, classpath and JVM flags.
   A mismatch silently disables the archive or fails startup.
3. **CI is the wrong place to train.** Real config, secrets and dependencies live in the
   cluster, not in the build.
4. **Nobody measures it.** There is no routine way to see what an archive actually saves.

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

Build the CLI (see [Building from source](../README.md#building-from-source)), then train an archive:

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

In a Dockerfile, use [`scripts/mnemo-warmup-exec`](../scripts/mnemo-warmup-exec) as the entrypoint:

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
starts it plain. See [ADR 0003](adr/0003-run-exec-model-and-layout.md).

`run` options: `--cache-dir` (default `$MNEMO_WARMUP_CACHE_DIR`, then `.mnemo-warmup/cache`),
`--image-digest` (default `$MNEMO_WARMUP_IMAGE_DIGEST`), `--print`, `--spawn`, `--stop-timeout-seconds`.

## Container image

The tool has to run **inside your application image**, because an archive is only valid for that
image's JDK build, JAR files, paths and modification times. So the published image
`ghcr.io/baokhang83/mnemo-warmup-tools:<version>` (about 7 MB, amd64 and arm64) contains no JDK; it only
carries `/opt/mnemo-warmup/mnemo-warmup.jar`, a launcher `bin/mnemo-warmup` and the exec wrapper
`bin/mnemo-warmup-exec`. Your application image needs Java 21 or newer.

Bake it into your image:

```dockerfile
COPY --from=ghcr.io/baokhang83/mnemo-warmup-tools:0.2.0 /opt/mnemo-warmup /opt/mnemo-warmup
ENTRYPOINT ["/opt/mnemo-warmup/bin/mnemo-warmup-exec"]
CMD ["java", "-jar", "/app/app.jar"]
```

or copy it at deploy time with an init container (what the Helm chart will do):

```yaml
initContainers:
  - name: mnemo-warmup-install
    image: ghcr.io/baokhang83/mnemo-warmup-tools:0.2.0
    command: ["cp", "-R", "/opt/mnemo-warmup/.", "/mnemo-tools/"]
    volumeMounts: [{ name: mnemo-tools, mountPath: /mnemo-tools }]
  - name: mnemo-warmup-fetch
    image: my-app:1.0            # the application image itself
    command: ["/mnemo-tools/bin/mnemo-warmup", "fetch", "--jar", "/app/app.jar",
              "--store", "oci://registry.example.com/team/warmup", "--out", "/mnemo-cache"]
    volumeMounts: [{ name: mnemo-tools, mountPath: /mnemo-tools }, { name: mnemo-cache, mountPath: /mnemo-cache }]
```

If the image has no Java, or one older than 21, the launcher makes `fetch` skip itself with exit 0 and
`run` start your original command unchanged, so the pod still starts. That safety net needs `/bin/sh`.
**Shell-free (distroless) application images** have no launcher: call
`java -jar /mnemo-tools/mnemo-warmup.jar ...` directly, which requires Java 21+ in the image. On an
older Java that init container would fail and block the pod, so do not enable it there. Build it locally with
`docker build -f image/Dockerfile -t mnemo-warmup-tools .`, and check the whole chain in Docker with
`scripts/verify-image.sh` (a local registry, JDK 21 with AppCDS and JDK 25 with AOT).
See [ADR 0004](adr/0004-tools-image.md).

## Kubernetes: the Helm chart and the trainer

[`deploy/helm/mnemo-warmup`](../deploy/helm/mnemo-warmup) renders the **trainer**: a CronJob that runs your
application image, plus the ServiceAccount, Role and RoleBinding it needs. It is deliberately a CronJob and not a Helm
hook, so a failing trainer can never fail or roll back a release. Each run executes
`mnemo-warmup train`: compute the fingerprint, stop if the store already has it, otherwise take a Kubernetes
`Lease` named after the fingerprint (so two trainers never train the same archive; an expired lease from a crashed
trainer is taken over), train, upload, release. A tag whose entry is incomplete (for example a missing blob) is
replaced rather than treated as done. It exits 0 for every outcome and logs which one happened.

The chart does **not** render your Deployment. Your own chart includes its named templates to add the init
containers, volumes, environment and the exec-wrapper entrypoint to your pod template (the chart's README has the
snippet, and [`deploy/helm/tests/parent`](../deploy/helm/tests/parent) is a working example):

```yaml
dependencies:
  - { name: mnemo-warmup, version: 0.1.0, repository: "file://path/to/mnemo-warmup/deploy/helm/mnemo-warmup", alias: warmup }
```

The values that must match your Deployment are `app.image`, `app.jar`, `app.jvmArgs` and `app.env`: an archive is only
valid for that exact launch, and a mismatch is safe (the pod starts without the cache). The trainer's ServiceAccount
may only `get`, `create`, `update` and `delete` Leases in its own namespace; application pods need no extra RBAC.

`mnemo-warmup train` can also be run directly (`--lock none` outside a cluster). It needs the extracted layout and exits
0 for every outcome unless `--strict`.

Check the chart with `scripts/test-chart.sh` (needs `helm`; set `KUBE_CONTEXT` to also have a cluster's API server
validate every manifest). See [ADR 0005](adr/0005-trainer-and-chart.md).

## Example and end-to-end test

[`examples/`](../examples/README.md) has a small Spring Boot application, an example workload chart that uses the
mnemo-warmup chart, and `scripts/e2e-kind.sh`, which runs the whole chain on a kind cluster for JDK 21 (AppCDS) and
JDK 25 (AOT): the app starts without an archive; the trainer trains and uploads once and releases its Lease; a second run
finds the entry complete; after a restart `fetch` finds the archive, the application's own classes load from it and `java`
is PID 1; and a JVM option changed only in the Deployment makes the pod start uncached, healthy, with a logged reason.

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

Not there yet: a published Helm chart (use `deploy/helm/mnemo-warmup` from this repository), results on amd64, and
more than one application (see the [project plan](PLAN.md)).

## Limitations

- **Tested on amd64 and arm64:** CI runs on Linux amd64 (the whole chain on a kind cluster, JDK 21 and 25); local runs were on arm64
  (Apple silicon, Docker, kind).
- **Linux and macOS only.** Stopping the app with SIGTERM is what makes the JVM write the
  archive; Windows kills the process instead.
- **AOT needs JDK 25+.** JDK 24 has the AOT cache but a two-step flow that is not
  supported yet; it uses AppCDS.
- **Spring Boot is tested with one small application** (the example, a web app with one controller), on JDK 21 and 25.
  To check your own Spring Boot 3.3+ JAR, run
  `mvn verify -pl warmup-core -Dwarmup.test.springBootJar=/path/to/app.jar`.
- AppCDS behaviour on JDK 21-24 was checked in Docker ([results](appcds-jdk-matrix.md)); CI runs
  the tests on 21 and 25.
- **Registry login:** Docker-config Basic/Bearer auth only. ECR/GCR/ACR credential helpers and
  workload-identity token exchange are not implemented, and only plain registries were tested (not TLS
  or a cloud registry).
- **`run` hashes the classpath JARs and the archive at start.** That costs startup time on a large
  classpath; it has not been measured yet.

## Getting it

- **The tool in a pod:** the tools image `ghcr.io/baokhang83/mnemo-warmup-tools:<version>` (see [Container image](#container-image)),
  published for each release tag. It is the only way the CLI is distributed; build from source to run it elsewhere.
- **As a library:** `warmup-core` is on Maven Central (`io.github.baokhang83.mnemo:warmup-core`), for example for
  mnemo-jvm-warden.
- **The Helm chart:** `deploy/helm/mnemo-warmup` in this repository (not published to a chart repository yet).

`mnemo-warmup --version` prints the version of the jar you run.

## Repository layout

```
warmup-core/   engine: JDK detection, extraction, training, manifest, OCI store, fetch/run logic
warmup-cli/    command-line interface (picocli): build, bench, push, fetch, run, train
coverage/      aggregates JaCoCo coverage for the badge (not published)
image/         Dockerfile and launcher of the tools image
deploy/helm/   Helm chart for the trainer plus the reusable pod-template helpers, and a parent-chart test fixture
examples/      a Spring Boot app, an example workload chart, and the notes behind scripts/e2e-kind.sh
scripts/       mnemo-warmup-exec wrapper, verify-image.sh, test-chart.sh, Docker checks for the AppCDS matrix
docs/PLAN.md   design, decisions and roadmap
docs/adr/      decisions: archive store, training trigger, run/exec model
```
