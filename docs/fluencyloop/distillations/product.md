# mnemo-warmup

**Problem.** A JVM application on Kubernetes takes a long time to start because every pod loads and checks the same classes again. The JVM can save this work in a cache file, but teams rarely use it because making and sharing the file is manual.

**Shape.** The product trains a cache once for a given application and JDK, and every replica reuses it. The cache is either an AppCDS archive (JDK 21 to 24) or a Leyden AOT cache (JDK 25 and later). A fingerprint of the JDK, the application and the relevant JVM settings decides if a stored cache fits a pod. A command line tool trains and measures; a Kubernetes init container fetches the matching cache.

**Safety rule.** The cache is only an optimisation. If it does not fit, the pod starts without it. The product never makes the JVM fail on a bad cache.

**Not done yet.** The command that compares start time with no cache, AppCDS and AOT, and the Kubernetes delivery, are still to be built.
