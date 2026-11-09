# Ignore-not-fail archive fallback

A startup cache is a file made by a training run. It holds class data that the JVM would otherwise have to load and check at every start. The file is only valid for the exact JDK build, JAR files and some memory settings used to make it.

The JVM can react in two ways when the file does not match. In `-Xshare:auto` mode, the default, it ignores the file and starts normally, only slower. In `-Xshare:on` mode it refuses to start. The product always uses the first mode.

This means a wrong cache costs time, never availability. It also means a wrong cache can go unnoticed, because some mismatches print nothing. Checking that the cache was used needs the class-loading log.
