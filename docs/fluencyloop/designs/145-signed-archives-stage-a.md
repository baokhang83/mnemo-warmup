# Design: signed archives, stage A (#145)

Implements stage A of [ADR 0013](../../adr/0013-signed-archives.md). The trainer signs every archive it publishes with an Ed25519 private key. A pod that has been given the matching public key uses an archive only if its signature verifies; otherwise the archive is a **miss** and the pod starts without a cache. With no public key configured, nothing changes (opt-in).

Stage A protects against anyone who can write to the registry but does not hold the private key. It does not protect against code running inside the trainer container; that is stage B.

## Shapes

```mermaid
classDiagram
  class SignedStatement {
    <<record, Java and Go>>
    fingerprint
    manifestDigest
    archiveDigest
    size
    repository
    +text() the exact bytes that are signed
  }
  class SigningKey {
    <<Ed25519>>
    +generate()
    +keyId() first 8 bytes of SHA-256 of the public key
  }
  class ArchiveSigner {
    +sign(statement) signature
  }
  class ArchiveVerifier {
    -publicKeys keyId to key
    +verify(statement, keyId, signature)
  }
  class OciArchiveStore {
    +put(manifest, archive) signs, writes annotations
    +inspect(fingerprint) unsigned = incomplete
    +get(fingerprint, dir) verifies before download
  }
  class ArchiveFetcher {
    +fetch() writes archive, manifest, flags, signature file
  }
  class RunPlanner {
    +decide() re-verifies the files on disk
  }
  class GoLauncher {
    <<fetch_native.go, planner.go>>
    same statement bytes as Java
  }
  class KeygenCommand {
    +run() key pair, key id, Secret manifest
  }
  class Injector {
    annotation signing-public-keys
    env on fetch and run
  }
  SigningKey --> ArchiveSigner
  ArchiveSigner ..> SignedStatement : signs
  ArchiveVerifier ..> SignedStatement : rebuilds and checks
  OciArchiveStore --> ArchiveSigner : put
  OciArchiveStore --> ArchiveVerifier : get, inspect
  ArchiveFetcher --> OciArchiveStore
  RunPlanner --> ArchiveVerifier
  GoLauncher ..> SignedStatement : byte for byte
  KeygenCommand --> SigningKey
  Injector ..> ArchiveVerifier : hands over public keys
```

## Publish (trainer)

```mermaid
sequenceDiagram
  participant T as Trainer
  participant S as OciArchiveStore
  participant G as ArchiveSigner
  participant R as Registry
  T->>S: inspect(fingerprint)
  S->>R: GET manifest, config blob
  S-->>T: absent, or entry not signed by this key = incomplete
  T->>T: train, build manifest and archive
  T->>S: put(manifest, archive)
  S->>S: statement = fingerprint, manifest digest, archive digest, size, repository
  S->>G: sign(statement)
  G-->>S: key id and signature
  S->>R: PUT blobs, PUT manifest with 2 annotations (signature, key id)
```

## Pod start (fetch, then run)

```mermaid
sequenceDiagram
  participant F as fetch (init or launcher)
  participant R as Registry
  participant V as Verifier
  participant D as cache dir
  participant N as run (every start)
  F->>R: GET manifest, config blob
  F->>F: rebuild statement from what it observed
  F->>V: verify(statement, key id, signature)
  alt no valid signature from a listed key
    F-->>D: nothing published, log the reason, exit 0
    N-->>N: no archive flag, plain java start
  else valid
    F->>R: GET archive, check size and digest
    F->>D: archive, manifest, signature file, flags last
    N->>D: read manifest, hash archive, read signature file
    N->>V: verify(statement rebuilt from the files)
    N-->>N: java with the archive flag, or plain start
  end
```

## Build order (each is one reviewable PR)

1. **The statement and shared test vectors.** `SignedStatement` in Java and Go, with a fixed-key vector (Ed25519 is deterministic) that both must reproduce byte for byte. This is the contract everything else stands on.
2. **Keys.** Key id, `ArchiveSigner`, `ArchiveVerifier`, `mnemo-warmup keygen`.
3. **The store.** `put` signs; `inspect` treats an entry not signed by the trainer's key as incomplete; `get` verifies. The Java fetch writes the config blob unchanged and a signature file.
4. **Java `run`** re-verifies the files on disk.
5. **Go `fetch` and `run`** verify, using the same vectors.
6. **Wiring and proof.** Injector annotation, chart value, preview, docs, a kind scenario with a tampered archive.

## Constitution check

- §1 and §3: a missing or invalid signature is a miss, the pod starts and the helper exits 0.
- §2: amended to "digest-checked, and signature-checked when keys are configured".
- §4: the JDK's built-in Ed25519 and Go's `crypto/ed25519`. No dependency is added.
