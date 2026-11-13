# JVM archive compatibility and fingerprints

A fingerprint chooses which archive to fetch. It is a conservative approximation of compatibility, not a complete model of the JVM. A matching fingerprint also does not prove that the JVM mapped the archive. A normal exit can mean that the JVM ignored it and started uncached.

## Measured results

The [raw study](../benchmarks/results/20261113-archive-compat-xms/results.csv) ran the extracted Spring Boot example in Linux ARM64 containers on Temurin 21.0.13 (dynamic AppCDS) and 25.0.5 (AOT cache). The usual training baseline used G1, `-Xmx256m`, `-XX:SoftMaxHeapSize=192m`, and a 1 GiB container. Each row is one run per JDK. The outcome below means that the example's `DemoApplication` class was reported as loaded from the archive; it does not prove every cached class or AOT optimisation was used.

| Change from training to runtime | JDK 21 | JDK 25 | Fingerprint rule |
|---|---|---|---|
| No change | used | used | Baseline |
| `-Xmx256m` ↔ `-Xmx512m` | used | used | Retain heap-size flags: larger changes can alter pointer layout. |
| Add `-Xms64m` with `-Xmx256m` | used | used | Retain initial heap flags conservatively; this tested change did not invalidate the archive. |
| `-Xmx256m` → `-Xmx40g` (both use `-Xms32m`) | not used | not used | Retain `-Xmx`; the runtime changed compressed-reference layout. The 40 GiB value is a virtual maximum, not a 40 GiB allocation. |
| `SoftMaxHeapSize=192m` ↔ `128m` under G1 | used | used | Exclude this soft target from the fingerprint. |
| `SoftMaxHeapSize=192m` → `128m` under ZGC | used | used | Exclude this soft target from the fingerprint. |
| G1 ↔ Serial GC; G1 → Parallel GC | used | used | Retain GC-selection flags: other GC changes can alter layout. |
| G1 ↔ ZGC | not used | not used | Retain GC-selection flags. |
| Compressed oops on ↔ off | not used | not used | Retain explicit compressed-oops flags. |
| Compressed class pointers on → off | not used | not used | Retain explicit compressed-class-pointer flags. |
| Compressed class pointers off → on | not tested: no archive produced | not used | JDK 21 training exited 0 but did not create an archive. |
| Container memory 1 GiB → 512 MiB or 2 GiB, fixed `-Xmx` | used | used | No separate cgroup profile is selected. |
| Container memory 1 GiB → 512 MiB or 2 GiB, JVM-derived heap | used | used | The observed max heaps were about 248, 124 and 512 MiB respectively. |
| Default `MaxRAMPercentage` → 35% with JVM-derived heap | used | used | Retain RAM-percentage flags conservatively; a larger change may alter layout. |

`not used` means the application exited 0 and its class loaded from its JAR. `No archive produced` is different from a runtime miss. The runner verifies an uncached control and every successful training baseline. It stores exit status, the class-source line and filtered JVM diagnostics in the result directory. `environment.json` records the image IDs and the dirty worktree used for the study. Reproduce with:

```sh
scripts/study-archive-compat.py --out /tmp/archive-compat-results
```

The run uses a local Docker daemon and mutable image tags; image IDs and full JVM versions record what was actually tested. It measures one application, architecture and pair of JDK builds, with one launch per case. It does not establish behavior across every collector, heap boundary, JVM vendor, cgroup limit or live resize. The 40 GiB maximum is outside the container limit and is used only to exercise an effective layout change; it is not a supported production sizing recommendation.

## Selection and fallback

The Java implementation and native launcher exclude `SoftMaxHeapSize` from `jvmFlags`. They retain heap sizes, RAM percentages, GC choices, pointer settings and object alignment as conservative inputs. Some retained changes did not invalidate the tested archives. Keeping them can cause extra training, but avoids assuming that small-test results apply across layout boundaries. Changes to properties outside the selected set do not necessarily create a new fingerprint.

A repository already holds multiple archives under distinct fingerprint tags. Different explicit `-Xmx` or GC profiles therefore get separate entries; a missing profile starts uncached, and a later training run can populate it. Returning to an earlier explicit profile can reuse its retained archive. The store does not provide named profiles or infer a profile from the cgroup memory limit. An implicit cgroup change that changes the effective JVM layout may select an existing fingerprint that the JVM then ignores. The small cgroup changes above did not cross such a boundary; larger implicit-layout changes remain unverified.

Existing archives whose stored fingerprint included `SoftMaxHeapSize` have a different key after this change. They will not be found under the new key. A pod starts uncached until the trainer writes a new entry; registry retention still controls the old entries. This also matters during a rollout with old and new tools versions.
