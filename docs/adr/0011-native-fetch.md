# ADR 0011: the init container fetches without a JVM when it can

Status: accepted (v0.2.6)

## Context

The `fetch` init container starts a JVM that detects the JDK (a second JVM), extracts and hashes the classpath, looks the
archive up in the registry, downloads it and verifies it. On the benchmark it takes 770 to 790 ms of wall time, of which about
430 ms is JVM start-up and tool class loading before the tool's own clock starts, and about 270 ms is the registry exchange
and the digest check (ADR 0009). After ADR 0010 it is more than 90% of the cost the helpers add to a pod. The launcher that
runs it is already a native program.

Constraints: an archive is digest-checked before the JVM can load it and a bad one is discarded, never used (§2); a bad or missing
archive never stops the application (§1); every helper exits 0 or falls back on any cache-related problem and never leaves state
that points at a stale archive (§3); the core library has no runtime dependencies and cloud integrations are small clients
behind `RegistryAuth` (§4). The Go launcher has no dependencies and keeps that.

## Decision

The launcher does the lookup itself, with the Go standard library, for the supported case, and runs the Java tool for the rest.

- **Same inputs, same fingerprint.** The native fetch computes the live manifest the way `ArchiveManifest.describe` does (the
  planner of ADR 0010 already shares that code and its fixture test) and asks for the tag `fp-<fingerprint>`. It follows the same
  registry protocol as `OciArchiveStore`: the OCI manifest, the config blob (which must hash to its digest and to the
  fingerprint asked for), and the single archive layer.
- **Verified before use (§2).** The layer is hashed while it is written to a temporary file in the output directory. It is
  renamed into place only if its size and SHA-256 equal both what the registry's manifest declares and what the stored
  manifest records for the archive; otherwise it is deleted and the answer is "no archive". The flags file is written last,
  atomically, after the archive and manifest are in place, exactly as `ArchiveFetcher` does, and everything the previous
  fetch left is removed first, so a failed fetch cannot leave flags that point at an old archive (§3).
- **Fails open (§1, §3).** A registry that is unreachable, slow, refuses the login or answers an error is a final answer: the
  note says why, the output directory holds no flags file, and the exit code is 0 (1 with `--strict`). The Java tool is **not**
  asked after a network failure, because it would wait on the same dead registry a second time.
- **The Java tool is asked (the native step makes no decision) when:**
  - the arguments are not the set the injector and the chart produce, or `--cache-type` is not `auto` or `appcds`;
  - the JDK cannot be identified from its `release` file, or the launched JAR is a Spring Boot fat JAR that needs extraction;
  - the registry login would come from somewhere the native code does not read: an ECR host without a Docker-config login
    (workload identity, §4: that client stays in one place, in Java, not copied), or a Docker config that cannot be read;
  - the JVM options or environment configure a proxy, which the JDK's HTTP client would honour and this one does not;
  - the registry's certificate is not trusted by the operating system's roots. The JDK uses its own trust store, which may
    hold a private CA the launcher cannot see; failing there would turn a working setup into a silent loss of the cache.
  - the lookup finds **no archive** (every candidate answered 404). The native step identifies the JDK from `release`
    where Java asks the running JVM; a vendor string that differs between the two would produce another fingerprint and a false
    miss. So a miss is re-checked by the Java tool, and a **hit** is final. A hit cannot be a false positive: the stored
    manifest must hash to the fingerprint asked for, which covers the JDK build, the JVM options and the classpath hashes.
- **Escape hatch:** `MNEMO_WARMUP_NATIVE_FETCH=0` always runs the Java tool, for diagnosis and for measuring one against the other.

## Rejected

- **Port the ECR login (STS and SigV4) to Go.** It would be a second copy of the code that holds credentials. Pods that use it
  keep the Java path until there is a reason to take that on (§4, ADR 0008).
- **Trust a native miss.** It makes the uncached pod, which has nothing to gain, as fast as it can be, at the price of a silent
  permanent miss if the two ways of identifying the JDK ever disagree. The cost of the re-check is paid only by a pod that
  starts without an archive: the first deploy of an image, until its archive is trained.
- **Keep one JVM and make it faster:** measured in ADR 0009; flags do not help the real path.

## Consequences

- The uncached pod (first deploy of an image) pays for the native attempt and then the Java tool; the cached pod pays for the
  native attempt only. The benchmark's `nocache` arm shows that cost.
- A change to the registry protocol, the fingerprint or the manifest format has to be made in both implementations; the compat
  fixture (ADR 0010) and the native fetch tests fail first.
- The Go code adds no dependency to the launcher.
