# AppCDS (dynamic archive) on JDK 21-24

Verified for issue #10 with `scripts/verify-appcds.sh` (Docker, `eclipse-temurin:<n>-jdk`, linux/arm64).
Builds tested: 21.0.13+9, 22.0.2+9, 23.0.2+7, 24.0.2+12. The app is a two-class JAR run by absolute
path. Training uses `-XX:ArchiveClassesAtExit`, runs use `-XX:SharedArchiveFile`.

## Result

**The same flags work on JDK 21, 22, 23 and 24.** `CacheStrategy` needs no per-JDK change.
Behaviour is identical on all four releases in every scenario below, except for the number of
classes loaded from the archive (13 on 22, 17 on the others), which only reflects the JDK's own
class set.

## What the JVM does when the archive does not match

"Archive used" means classes were loaded from `shared objects file (top)` (the dynamic layer).

| Scenario | `-Xshare:auto` (JVM default) | `-Xshare:on` |
|---|---|---|
| Same JAR, same flags | archive used | archive used |
| JAR modification time changed | **ignored**, exit 0, `[warning][cds]` line | exit 1 |
| Same bytes, new mtime (plain `cp`) | **ignored**, exit 0, warning | exit 1 |
| JAR content changed | **ignored**, exit 0, warning | exit 1 |
| JAR moved to another directory | archive used | archive used |
| Archive from another JDK build | **ignored**, exit 0, warning | exit 1 |
| Archive truncated | **ignored**, exit 0, warning | exit 1 |
| Archive file missing | ignored, exit 0, no message | exit 1 |
| `-Xmx40g` (no compressed oops) | **ignored, exit 0, no message** | exit 1 |
| `-XX:-UseCompressedClassPointers` | **ignored, exit 0, no message** | exit 1 |
| `-Xmx64m` | archive used | archive used |
| `-XX:+UseSerialGC` / `-XX:+UseParallelGC` (trained with default) | archive used | archive used |

## Consequences for the design

1. **The fallback promise in `PLAN.md` holds.** With the default `-Xshare:auto` a bad archive is
   never fatal. `-Xshare:on` is fatal in every case, so we must never pass it.
2. **Invalidation is all or nothing.** One changed JAR drops the whole archive. There is no partial use.
3. **JAR modification time matters, content equality does not.** A byte-identical JAR with a new
   mtime is rejected. This confirms the open question about mtimes (`PLAN.md`, question 6): trainer
   and runtime must use the same extracted files, or the same preserved mtimes.
4. **The JAR path is not checked** when the file is at the same relative layout, so moving the
   directory is safe (tested with an absolute path on both sides).
5. **Some mismatches are silent.** Heap size beyond the compressed-oops limit and
   compressed-class-pointer changes make the JVM drop the archive without a log line. A pod cannot
   tell "archive used" from "archive ignored" by exit code. Verification needs
   `-Xlog:class+load` or `-Xshare:auto -Xlog:cds`.
6. **Evidence for issue #20 (which flags to fingerprint).** For a dynamic archive, GC choice and a
   small `-Xmx` did not invalidate it here. Compressed oops and compressed class pointers did.
   `JvmFlags` keeps its conservative set until #20 measures this with a real Spring Boot app.

## Reproduce

```
scripts/verify-appcds.sh 21 22 23 24
```

Output is one tab-separated row per scenario and mode.
