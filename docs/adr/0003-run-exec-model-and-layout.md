# ADR 0003: How `run` starts the JVM, and which files the archive matches

Status: accepted (2026-11-10) · Resolves issue #14 and PLAN open decisions 4 and 6

## Context

`fetch` leaves an archive, its manifest and a one-line flags file in a cache directory. The
application container must (a) start the JVM with that archive only if it still fits this exact
launch, and (b) be stopped cleanly by Kubernetes, which sends SIGTERM to PID 1.

The JVM also checks each classpath JAR's size and modification time when it maps an archive, and
records JAR paths. The fingerprint ignores modification times on purpose. An archive trained on one
extraction and used on another is silently ignored by the JVM: safe (§1) but no speed-up.

## Decision

**Exec model: decide in Java, exec from a shell.**
`mnemo-warmup run --print -- java ...` prints the final command (shell-quoted, one line).
`scripts/mnemo-warmup-exec` evaluates it with `exec`, so the JVM replaces the shell and is PID 1
with no wrapper process. If `run` is missing or fails, the script execs the original command
(§3). `run --spawn` (the default without `--print`) starts the command as a child, forwards
SIGTERM and **waits** for it (`--stop-timeout-seconds`, default 25, then SIGKILL) so the child gets
its shutdown time, and returns its exit code, for images without `/bin/sh`; it keeps a second JVM resident.

**Layout: require extraction at image build time.**
The trainer and the application run the same extracted files at the same path
(`java -Djarmode=tools -jar app.jar extract` in the Dockerfile). `run`:
- supports `java [options] -jar <jar> [args]` launches only;
- hashes the archive and compares it with the manifest's SHA-256 (size alone would accept
  same-length corruption), then compares the live manifest to the fetched one (`mismatches`);
- separately compares the JAR modification times to the second (`modificationTimeMismatches`) and
  the directory the app was trained from (`locationMismatches`, from the manifest's `launchDir`,
  because the JVM records paths; a manifest without one cannot be verified and is not used);
- bounds the JDK probe (10 s) so a hung launcher cannot hold the application back;
- on any difference, or if a Spring Boot fat JAR is launched directly, starts the command unchanged
  and logs the reason;
- inserts `-XX:SharedArchiveFile=` / `-XX:AOTCache=` right after the java executable, and leaves a
  command alone that already sets an archive flag.

**App pickup (PLAN decision 4):** `run` is the supported way. Setting `JAVA_TOOL_OPTIONS` from the
flags file still works but is unvalidated. Archive flags are not part of the fingerprint, so using
both does not change it.

## Rejected

- **Resident Java supervisor only:** a second JVM (about 40-60 MB per pod) for the pod's whole life.
- **`run` extracts at startup and restores modification times:** adds startup time and depends on
  restoring paths and times exactly.
- **Failing the pod on a mismatch:** against §1 and §3.

## Consequences

- Images need `/bin/sh` for the recommended mode. Dockerfiles must extract Spring Boot JARs at build time.
- `run` hashes the classpath JARs and the archive at start (`fetch` hashes the JARs too). Measure the cost before optimising.
- Manifests written before `launchDir` existed are not used by `run`; retrain.
- The image (#16) ships `mnemo-warmup-exec` and sets `MNEMO_WARMUP_CACHE_DIR` and `MNEMO_WARMUP_IMAGE_DIGEST`.
