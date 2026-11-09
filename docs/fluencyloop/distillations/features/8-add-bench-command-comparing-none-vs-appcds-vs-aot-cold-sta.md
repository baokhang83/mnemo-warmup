# The product can now measure what a cache saves

**Before:** The product could train a startup cache, but it could not show how much time or memory the cache saves. The `bench` command was a stub.

**After:** `bench` trains an AppCDS archive and, on JDK 25 and later, an AOT cache. It then starts the application several times with no cache, with AppCDS and with AOT. It prints one Markdown table: median startup time, the range, the saving against no cache, and memory use.

How the comparison stays fair:

- Every variant is stopped at the same point, the "ready" moment that the training exit mode already defines.
- Each start is a new JVM process. One start per variant is thrown away first, because it pays one-off file-reading costs.
- The table uses the median, so one odd run does not change the result.
- Timing runs use a probe without warmup traffic. Training runs keep their warmup, so the archive still contains request-path classes.

Memory is a sampled peak. Treat it as a lower bound.
