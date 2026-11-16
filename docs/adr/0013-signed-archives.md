# ADR 0013: sign archives, and keep the signing key away from application code

Status: proposed. Tracking issue: #145. Security roadmap findings S1 and S3 ([security-roadmap.md](../security-roadmap.md)).

## Context

An archive is checked for **integrity**: its layer digest and size match the registry's manifest, its SHA-256 matches the stored `mnemo-warmup.manifest.json`, and that manifest's fingerprint matches the pod's JDK, options and classpath ([security.md](../security.md#trust-model-who-can-publish-an-archive)). Every one of those checks compares against values the same registry served. Nothing says **who published** the archive.

The JVM treats a class-data or AOT archive as trusted input and does not re-verify the classes in it. So anyone who can write to the archive repository, sit on the network path when `plainHttp` is on, or hold the trainer's push credential can publish an archive that passes every check and runs their code in every pod with that fingerprint. Constitution §2 states the aim as keeping a store from "becoming a code-execution path"; today the code only keeps it from corrupting a start.

A second gap decides whether a fix is worth anything. The trainer container **runs the application** (that is how the archive is made) and **holds the push credential**, a service-account token and cloud identity. A compromised dependency that runs during training can read all of them. A signing key placed in that container would be readable the same way, and a signature made by an attacker is still valid.

## Decision

### 1. What is signed

Ed25519, using only the platform: Go's `crypto/ed25519` in the launcher and the JDK's built-in `Ed25519` signature in the Java tool. No dependency is added (constitution §4).

The signed statement is a fixed text, rebuilt by the verifier from what it actually observes, never read from the registry:

```
mnemo-warmup-archive-v1
fingerprint: sha256:<hex>
manifest: sha256:<hex>      the stored manifest (the OCI config blob)
archive: sha256:<hex>       the archive (the OCI layer)
size: <bytes>
repository: <repository path, without the registry host>
```

Binding the fingerprint stops a valid signature being moved to another tag. Binding the repository stops one application's signed archive being replayed into another's repository when a key is shared. The registry host is left out on purpose: the trainer and the pods may reach the same registry under different names.

### 2. Where the signature lives

In the OCI manifest the trainer already pushes, as two manifest annotations: `io.github.baokhang83.mnemo.warmup.signature` (base64) and `io.github.baokhang83.mnemo.warmup.keyid`. It arrives with the same request that fetches the manifest and is replaced atomically with the tag, so there is no extra round trip and no separate tag to keep in step or to retain.

The annotation sits in the manifest it does not sign (that would be circular); it signs the statement above, which pins the config and layer digests. A registry writer can edit the annotation but cannot produce a signature that verifies.

Cosign-style signatures stored as referrers are not used now. The annotation format does not exclude adding them later.

### 3. How a pod decides

An operator supplies one or more public keys (`id=base64`). The key id is the first 8 bytes of the SHA-256 of the public key, in hex.

- **No key configured:** behaviour is exactly as today (integrity only). Signing is opt-in until it is proven.
- **Keys configured:** an archive without a valid signature from a listed key is **a miss**: the log says why, no archive or flags are published, the pod starts without a cache and exits 0. §1 and §3 hold; a signature problem can never stop an application.

Verification runs in two places:

1. **`fetch`**, before anything is published into the cache directory. It verifies against the registry's manifest and writes `mnemo-warmup.signature` (key id, signature, repository) next to the archive.
2. **`run`**, on every start, against the files on disk: it rebuilds the statement from the stored manifest's fingerprint, the SHA-256 of the manifest file, the SHA-256 and size of the archive it already hashes, and the repository from the signature file, then verifies. A process that can write to the shared cache directory (finding S14) can swap files but cannot make a signature for them, so the directory becomes tamper-evident without relying on file modes. In the opt-in mode, `run` ignores an unsigned cache directory.

The cost is one Ed25519 verification (microseconds) and the hash of a small manifest file.

This depends on the manifest file on disk being byte-for-byte the config blob the registry served. The Go fetch already writes the raw blob. The Java fetch does not: `ArchiveFetcher` rewrites the file from the parsed manifest with `toJson()`, which usually produces the same bytes but is not guaranteed to. It must write the downloaded bytes unchanged; that is part of the first implementation slice.

### 4. Keys and rotation

- `mnemo-warmup keygen` writes a private key (a Kubernetes Secret manifest for the signer, mode 0400 when mounted) and prints the public key and key id. The private key is created where the operator chooses; the tool does not store it.
- Pods accept **any** listed public key. To rotate: add the new public key to pods, then switch the trainer to the new private key, then remove the old public key once nothing signed with it is wanted.
- The trainer treats a stored entry as complete only if it also verifies under the key it signs with. An entry that is unsigned or signed by another key is incomplete and is re-published (reusing the stored blobs). Without this rule a rotation or a first rollout of signing would be blocked by `ALREADY_STORED`. Registries with immutable tags would refuse that tag overwrite; `docs/archive-retention.md` already lists the repair against immutable tags as untested, and this adds a reason to test it.
- Private keys held in a cloud KMS are not designed here.

### 5. Keep the key away from application code (S3)

Delivered in two stages, because stage A is useful on its own and stage B is a larger change to the trainer pod.

**Stage A: signed archives.** The trainer signs in its current container. This stops a registry writer, a network attacker and anyone else who can push but does not hold the private key. It does **not** stop code running inside the trainer, which can read the key. This limit is stated in the documentation.

**Stage B: split the trainer pod** into three steps that share a volume:

| Step | Runs application code | Holds secrets |
|---|---|---|
| `check` (init) | no | pull credential, service-account token: looks the fingerprint up, and writes a "skip" marker if a valid entry exists |
| `train` (init) | **yes** | **none**: no push credential, no signing key, no service-account token (`automountServiceAccountToken: false`) |
| `publish` (main) | no | push credential, signing key, token: takes the lease, checks the store again, verifies and signs what `train` wrote, uploads |

`train` can only leave files in the shared volume. `publish` treats them as untrusted input: it recomputes the manifest and hashes from the archive, then signs. The lease moves to `publish` (a short window around the upload). Training is no longer protected from a second trainer by a lease held through the run; a duplicate training run only wastes CPU, `concurrencyPolicy: Forbid` still serialises one workload's trainers, and the store re-check before upload still prevents a double publish.

`check` and `publish` need a JVM for the Java tool, so they run the application image (its JDK, our jar), but never the application itself.

### 6. Defaults

Opt-in through the beta. At 1.0 (or earlier if the opt-in proves safe) the injector requires a public key and refuses to enable warmup without one, unless the operator passes `--allow-unsigned`.

## Alternatives considered

- **Cosign / Sigstore keyless.** The standard answer, and no keys to handle. It needs network calls to external services on the start path and, for verification, large dependencies in the launcher, against §3 and §4. Kept open: the signed statement and the annotation format can later be accompanied by a cosign signature.
- **A keyed hash (HMAC) with a shared secret.** Cheaper, but every pod would hold the secret that can forge archives, so one compromised pod could poison all the others. Public-key signing means pods hold nothing that can sign.
- **Pin an allowed digest per workload.** Strong, but it makes every retraining a Deployment change and defeats the trainer's purpose.
- **Rely on registry controls only** (separate repository per app, immutable tags, pull-only credentials). Done and documented in Phase 0; it reduces who can publish but does not remove the code-execution path through a registry compromise.

## Consequences

- A new key to create, store and rotate. The operator documentation must say this clearly, including that losing the private key means pods keep working but start uncached until a new key is rolled out.
- The first rollout of signing makes existing entries incomplete: the trainer re-publishes them, and pods start uncached until it has. This is the fail-open behaviour, but it is visible in start times.
- Stage A on its own can look like more protection than it is. The docs and the preview must say that stage B is what separates the key from application code.
- Constitution §2 is amended to read "digest-checked, and signature-checked when keys are configured".
- The Java fetch must write the config blob unchanged (see section 3) before `run` can re-verify what it finds on disk.
- The Go launcher and the Java tool both implement the statement, the verifier and the sidecar file; they must agree byte for byte, and a shared test fixture (the same approach as `launcher/testdata/compat`) is part of the work.
- The trainer-pod split changes the injector's output and the chart. It is breaking only for deployments that opt in.

## Decisions on the questions raised in review

- **The repository in the signed statement is the path as the operator configures it** (`team/app` from `oci://registry/team/app`), exactly as the launcher parses it. No normalisation, so the trainer and the pods build the same text from the same annotation.
- **Key scope: one key per namespace by default, one per workload where the trust differs.**
  - The private key is a Kubernetes Secret, and a Secret lives in one namespace. A cluster-wide key would have to be copied into every namespace, and one leaked copy would let its holder sign for every workload that trusts the key.
  - One key per workload is the safest but needs a `keygen`, a Secret and an annotation for every application, and that friction would keep people from turning signing on.
  - A key per namespace keeps the private key next to the trainer that uses it and limits a leak to one team. Pods accept a list of public keys, so a workload that needs its own key (one that runs third-party plugins, or processes untrusted input) just uses a different one. The design needs no change to allow either.
  - Until stage B ships, application code in the trainer can read the key. In a namespace where workloads have different trust levels, the riskier ones should have their own key, so a compromised one cannot sign for the others.
