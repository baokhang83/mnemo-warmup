# ADR 0010: the run planner moves into the launcher

Status: accepted (v0.2.6)

## Context

Every application container start asks a helper JVM whether the fetched archive fits this exact launch (ADR 0003). The helper
starts a JVM, reads the manifest, hashes the archive (51 to 85 ms) and the classpath JARs (34 to 90 ms), probes the JDK by starting
a second JVM (16 to 22 ms), compares, and prints the command. It takes 200 to 312 ms of wall time before the application can
start (ADR 0009). Most of that is work done in a JVM that has not warmed up. The launcher that starts it is already a native
program.

Constraints: an archive is digest-checked before the JVM can load it (§2); a bad or missing archive never stops the application (§1);
every helper falls back to the plain start on any problem (§3); nothing may make the JVM fail on an archive mismatch (§1).

## Decision

The launcher makes the decision itself, without a JVM, for the case that matters, and asks the Java planner for everything else.

- **It answers "use the archive" only when every check the Java planner makes passes:** the flags file is one option that
  matches the manifest's cache type; the manifest parses, has no field this tool does not know, and its recorded fingerprint
  equals the fingerprint recomputed from its contents; the archive exists, has the recorded size and the recorded SHA-256
  (hashed now, §2); the command is `java [options] -jar <jar>` with no archive option of its own; the JAR is not a Spring Boot
  fat JAR; the SHA-256 and size of the launched JAR and of every `Class-Path` entry equal the manifest's, in order; the JDK
  build and vendor equal the manifest's; the relevant JVM options and image digest equal the manifest's; the JAR directory is
  the directory it was trained from; and every JAR's modification time agrees to the second.
- **Anything else is "not decided"** and the launcher runs the Java planner exactly as before. The Java planner stays the
  authority for the reasons it logs; the native planner never explains a refusal. So a mistake in the native planner can cost
  the speed-up, but it cannot make a mismatching archive be used, because "use" requires all of the checks, and the archive
  bytes are hashed before the flag is added.
- **One answer is also given natively:** when nothing was fetched (no flags file, or an empty one) the command is returned
  unchanged with the same log line the Java planner prints. That is the first deploy and the uncached pod, and it needs no JVM.
- **The JDK is identified from its `release` file** (`IMPLEMENTOR`, `JAVA_RUNTIME_VERSION`) instead of by starting it. If the
  file is missing or its values differ from the manifest's, the answer is "not decided" and the Java planner probes the real
  JVM. A vendor whose `release` file differs from `java.vendor` therefore loses the speed-up, never correctness.
- **The fingerprint is the same function in both languages.** `launcher/planner.go` rebuilds the canonical JSON that
  `ArchiveManifest.fingerprint()` hashes. A fixture written by the Java classes (`launcher/testdata/compat/Gen.java`) is the
  Go tests' input, and `CompatFixtureTest` in `warmup-core` fails when Java stops accepting the committed manifest. A change
  to the fingerprint must update both.
- **Hashing is done in parallel,** the archive and the classpath files together, with the standard library's SHA-256
  (hardware-accelerated on arm64 and amd64). No dependency is added to the launcher (`go.mod` stays empty).
- **Escape hatch:** `MNEMO_WARMUP_NATIVE_PLAN=0` sends every decision to the Java planner. It exists for diagnosis and for
  measuring one against the other.

## Rejected

- **Port the whole planner and drop the Java one.** The refusal messages are the operator's only explanation of why an archive is
  not used, and they are long and specific. Keeping one implementation of them avoids two sets drifting.
- **Skip the archive hash because `fetch` verified it** (ADR 0009): contradicts §2 and ADR 0003.
- **Trust a stamp from `fetch` instead of re-reading the JARs:** the point of the comparison is to catch a launch that
  differs from the training, not only a damaged file.

## Consequences

- With the timing switch on, the native path prints the same `run: timing:` line as the Java planner, with
  `tool-jvm-uptime=0 ms (native planner)` and the combined hash phase, and the launcher prints `plan-process=N ms (native)`.
- The launcher binary grows by the standard `archive/zip`, `crypto/sha256` and `encoding/json` code (a few hundred KB).
- A new cache type or manifest field needs the Go planner updated, or it falls back to Java, which is the safe direction.
