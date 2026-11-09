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

<!-- Real principles land here, numbered §1, §2, … (features cite these numbers in their
`constitution:` fields). Each: a short title, the non-negotiable in a sentence or two, and the
why (the failure it prevents). Keep them checkable, not platitudes. -->
