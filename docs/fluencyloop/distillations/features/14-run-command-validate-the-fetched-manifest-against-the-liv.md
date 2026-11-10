# run command

**Before:** `fetch` could place an archive in a pod, but nothing started the application with it,
and the JVM silently ignores an archive that does not fit.
**After:** the application container starts through `mnemo-warmup run`. It checks the fetched
archive against this exact launch (JDK, JVM options, classpath content and JAR modification times)
and adds the archive flag only on a full match; otherwise it starts the command unchanged and logs
why ([[§1]], [[§3]]). A small shell wrapper then replaces itself with the final command so the JVM is
PID 1 and receives Kubernetes' stop signal directly. Spring Boot images must extract at build time
so training and running use the same files ([[docs/adr/0003-run-exec-model-and-layout.md]]).
