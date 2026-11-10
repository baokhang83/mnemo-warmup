# fetch command

**Before:** archives could be uploaded, but nothing in a pod could retrieve them.
**After:** `mnemo-warmup fetch` runs before the application. It works out which archive fits this
JDK, classpath and options, tries the AOT cache and then AppCDS, verifies the download, and writes
the archive, its manifest and a one-line flags file. If nothing fits, or anything goes wrong, it
clears old flags and exits successfully so the pod starts without a cache ([[§1]], [[§3]]).
The flags file is for `run` (next) or `JAVA_TOOL_OPTIONS`.
