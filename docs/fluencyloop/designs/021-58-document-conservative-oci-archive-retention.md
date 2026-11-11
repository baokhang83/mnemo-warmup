# Design: conservative OCI archive retention guidance (#58)

Documentation only. No deletion code, no delete permissions. The guide tells operators how to bound storage growth with the registry's own lifecycle policy, and what a deletion costs.

## Shapes

```mermaid
classDiagram
  class ArchiveRepository {
    dedicated OCI repository
    tags fp-hex, one per fingerprint
  }
  class FingerprintEntry {
    tag fp-hex
    manifest
    config blob small
    archive blob hundreds of MB
  }
  class LifecyclePolicy {
    owned by the registry
    expire by count or age
    runs within about 24 h
  }
  class Consumers {
    fetch init containers
    trainer CronJob
    rollback targets
  }
  ArchiveRepository "1" o-- "many" FingerprintEntry
  LifecyclePolicy --> ArchiveRepository : scoped to fp- tags
  Consumers --> FingerprintEntry : read by fingerprint
  Consumers ..> LifecyclePolicy : not visible to it
```

## What a deletion costs

```mermaid
sequenceDiagram
  participant LP as Registry lifecycle policy
  participant Reg as Archive repository
  participant Pod as New pod (fetch)
  participant Tr as Trainer CronJob
  LP->>Reg: expire fp-old (count or age rule matched)
  Pod->>Reg: GET manifest fp-old
  Reg-->>Pod: 404
  Note over Pod: no archive, plain JVM start (constitution 1 and 3)
  Tr->>Reg: tag fp-old present?
  Reg-->>Tr: no
  Tr->>Tr: train, then push fp-old again
  Note over Pod,Tr: later pods get the archive again
```

## Scope notes

- Count-based rule (newest N by push time) is the conservative example. Age-based expiry does not know a version is running, and the guide says so.
- The ECR example is included because the policy format and rule evaluation are confirmed in AWS documentation; what the documentation does not state (OCI artifacts, re-pushed tags, in-use images, tag immutability) is listed as unverified.
- Out of scope: automatic deletion, a prune command, extra IAM actions, other registries' policy syntax.
