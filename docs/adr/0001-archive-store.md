# ADR 0001: Archive store backend

Status: accepted (2026-11-10) · Resolves issue #11 · Unblocks #12, #13, #15

## Context

Each fingerprint (JDK build + classpath + JVM flags) has one AppCDS or AOT archive, trained once
and fetched by every pod's init container. Archives are hundreds of MB. The store must support:
upload from the trainer, lookup by fingerprint, integrity verification, and fast cold fetch.
Constitution §1 applies: an unreachable store or missing archive means a plain JVM start, never a
failed pod.

## Options

| | OCI registry | S3 / GCS | RWX PVC |
|---|---|---|---|
| Auth | Reuses the cluster's image-pull credentials | Separate IAM / workload identity | Kubernetes volume mount, no network auth |
| Integrity | Content digest (SHA-256) built in | We add a checksum ourselves | We add a checksum ourselves |
| Large blobs | Good; some registries cap blob size | Native | Native |
| Retention | Registry GC may drop untagged blobs | Lifecycle rules | Manual |
| Availability | Every cluster that runs images | Needs a bucket per environment | Needs an RWX storage class; often missing |
| Client code | OCI client needed | Simple SDK | Plain file I/O |

## Decision

Implement the **OCI registry** backend first. Store each archive as an OCI artifact with the
fingerprint as the tag and the SHA-256 digest recorded in a manifest. `fetch` verifies the digest
before it writes the archive.

Hide the store behind a small interface (`put`, `get`, `exists` by fingerprint) so S3/GCS can be
added later without changes to `fetch` or `run`. RWX PVC is not planned.

## Consequences

- Adopters need no new credentials or infrastructure beyond their image registry.
- We take on an OCI client dependency (library or minimal HTTP implementation: tracked in #15).
- Registries with low blob-size limits or aggressive garbage collection of untagged artifacts
  need documented settings. The trainer must tag every upload so GC does not remove it.
- Every failure (auth, network, digest mismatch, missing tag) maps to "no archive": `fetch` logs
  the reason and the pod starts uncached.
