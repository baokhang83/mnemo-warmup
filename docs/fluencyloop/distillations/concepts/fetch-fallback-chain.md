# Fetch fallback chain

A pod asks the store for the best archive that fits it: first the AOT cache, then AppCDS. The
first archive that downloads and passes verification wins, and a small flags file tells the JVM
to use it. If nothing fits, or the store is slow, unreachable or returns bad data, the helper
removes any old flags file and finishes successfully. The application then starts as it would
without a cache.
