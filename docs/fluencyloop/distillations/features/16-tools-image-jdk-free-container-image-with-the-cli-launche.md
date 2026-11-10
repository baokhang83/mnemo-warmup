# Tools image

**Before:** the commands existed, but there was no way to put them into a pod.
**After:** a small image without a JDK carries the command-line tool. It is copied into the
application image, either by an init container at deploy time or by a build step, and runs there,
so it sees the same JDK and JAR files the application runs with ([[docs/adr/0004-tools-image.md]]).
A launcher makes `fetch` and `run` skip themselves, successfully, when the image has no suitable
Java ([[§3]]). A Docker script checks the whole chain on JDK 21 and 25 and runs in CI.
