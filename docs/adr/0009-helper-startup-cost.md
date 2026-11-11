# ADR 0009: what the start-path helpers cost, and what we do about it

Status: accepted (v0.2.6)

## Context

`benchmarks/README.md` measures pod start-to-Ready with and without the cache on a laptop kind cluster. The archive
shortens the application's own start-up (0.6 to 1.3 s on a Spring Boot application, 0.24 s on Jenkins), but the two helper
JVMs that decide whether to use it take about 1 s before the application can start, so pod start-to-Ready did not improve.

Median wall time of the helpers in the `cached` arm (Spring Boot application, no pod limits; Jenkins numbers are the same
within noise):

| helper | wall | where it goes |
|---|---|---|
| `fetch` (init container) | 790 ms | JVM start and tool start-up about 350 ms; store lookup, download and digest check 279 ms; extract and hash the classpath 58 ms; JDK detection 16 ms |
| `run` (application container) | 200 to 312 ms | SHA-256 of the archive 51 to 85 ms; hash of the classpath 34 to 90 ms; JDK probe 16 to 22 ms (a second JVM start); manifest 12 ms; the rest is JVM start |

Measured on this branch, with the local JDK 25 on an arm64 laptop: a helper JVM that does almost nothing (the `run` fast path
with no archive) takes 89 ms to start and finish with default flags and 79 ms with the flags below. So JVM flags are worth about
10 ms per helper; most of `run` is **work done in a cold JVM** (hashing in code that has not yet been compiled), and most of
`fetch` is the JVM and the registry round trips.

The constitution limits what can be done: an archive is digest-checked before the JVM can load it (§2), the helpers fail open
(§3), the core has no runtime dependencies (§4), and nothing may make the JVM fail on an archive mismatch (§1).

## Decision

1. **Tune the helper JVMs** (tried and rejected). Starting the helpers with `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC
   -XX:-UsePerfData` saved about 10 ms on a helper that does almost nothing (89 to 79 ms), which is what was measured first.
   On the real `fetch`, which hashes, makes TLS and HTTP requests and digests an archive, the same flags made it **slower**:
   Spring `cached` 768 to 1206 ms, Jenkins `cached` 724 to 1177 ms, and the plain `nocache` arm 574 to 777 ms. With the three
   flags removed again, and nothing else changed, `fetch` returned to 779 ms. The flags were only measured as a set, so this
   does not say which of them costs the time; the likely suspect is C1-only compilation of the hashing and HTTP code, which
   the default tiering would have optimised further. The lesson recorded here: a start-up flag has to be measured on the real
   path, not on a no-op.

2. **Do not skip the archive hash in `run`** (rejected). `fetch` verifies the digest of what it downloaded, so hashing the file
   again in `run` looks redundant. ADR 0003 made `run` hash the bytes that are about to be handed to the JVM because a size check
   accepts same-length corruption, and §2 requires the check before the JVM can load the file. A stamp of size and modification
   time cannot detect corruption that preserves both. The cost (51 to 85 ms) is removed differently: see ADR 0010, which does
   the same hash natively, without a cold JVM.

3. **Remove the helper JVM from the application container** (ADR 0010) and **from the init container for the common
   cases** (ADR 0011). These are the two decisions that change the number.

## Consequences

- The `benchmarks/` results published before this ADR are the baseline. Every later run is compared with them under the same
  conditions (same application, same limits, same machine, run alone).
- Not doing idea 2 keeps ADR 0003's guarantee intact.
