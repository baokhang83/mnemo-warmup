# Same-moment startup measurement

A startup comparison is only useful if every variant is stopped at the same point. If one variant is stopped when the server opens its port and another when the first request works, the difference says nothing about the cache.

The product therefore reuses the exit mode's "ready" condition for training and for timing. Each timed start is a new JVM process. One start per variant is run and discarded first, because the first start after training reads the JAR and archive files from disk, and later starts read them from the operating system's memory cache. The report uses the median of the timed starts.

Timing and training differ in one way: training may send warmup requests to the application, but timing must not. Warmup requests are not startup.
