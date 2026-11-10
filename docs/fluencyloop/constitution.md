# Constitution

<!--
The project's developer-stated constraints. The plan skill raises a fixed set of areas and records
only stances the developer supplies; it never invents a stance. Delete this comment once the
constitution has been established.
-->

**Project:** <name>

## Constitution areas

### Guardrails

_No stance recorded yet._

### Architecture principles

_No stance recorded yet._

### Test methodology

_No stance recorded yet._

### Data and state

_No stance recorded yet._

### Dependencies

_No stance recorded yet._

### Security and privacy

_No stance recorded yet._

## Principles

### §1 A bad cache never stops an application

**Rule:** A bad or missing cache archive must never stop an application from starting. Never pass `-Xshare:on` or any flag that makes the JVM fail on an archive mismatch.

**Why:** The archive is an optimisation. A stale archive in a pod would otherwise turn a slow start into an outage. Verified on JDK 21-24 in `docs/appcds-jdk-matrix.md`.

### §2 Verify before use

**Rule:** An archive obtained from a store is digest-checked before the JVM can load it. A size or digest mismatch discards the download and means "no archive" (§1), never a partial or unverified file.

**Why:** A truncated or tampered archive handed to the JVM can crash it or run unintended code. Verification keeps a bad store or network from becoming an outage or a code-execution path.

### §3 Startup helpers fail open

**Rule:** Every component on the pod start path (`fetch`, `run`, wrapper scripts) exits 0 or falls back to a plain JVM start on any cache-related problem, and never leaves state that points at a stale archive. Only an explicit `--strict` mode may fail.

**Why:** An init container that exits non-zero keeps the pod from starting at all, so an optimisation failure would become an outage. Stale flags would silently apply the wrong archive.

<!-- Real principles land here, numbered §1, §2, … (features cite these numbers in their
`constitution:` fields). Each: a short title, the non-negotiable in a sentence or two, and the
why (the failure it prevents). Keep them checkable, not platitudes. -->
