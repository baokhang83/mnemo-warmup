# Example application and the cluster end-to-end test

**Before:** every piece was tested alone, with tiny plain JARs; nothing had run in a real cluster, and no real
Spring Boot application had gone through the tool.
**After:** an example Spring Boot application, extracted at image build time, and an example workload chart that uses
the mnemo-warmup chart. One script starts a throw-away cluster and registry and runs the whole chain on JDK 21 and JDK 25:
the application starts without a cache, the trainer trains and uploads once and releases its lease, a restart picks the
archive up (the application's own classes load from it and `java` is PID 1), and a changed JVM option makes a pod start
uncached and healthy with a logged reason ([[§1]], [[§3]]).
The first real Spring Boot JAR also showed that the extracted layout was being mistaken for a fat JAR, which would have
made the tool reject exactly the layout it requires; that is fixed ([[warmup-core/src/main/java/io/github/baokhang83/mnemo/warmup/core/JarExtractor.java]]).
