# Security roadmap

Status: written 2026-11-15 against `v0.3.0-beta.2`, from a security audit of the launcher (Go), the core and CLI (Java), the injector and Helm charts, and the repository and release pipeline. Companion to [security.md](security.md) (what the project already guarantees) and the [constitution](fluencyloop/constitution.md) (§1 a bad cache never stops an application, §2 verify before use, §3 helpers fail open, §4 no runtime dependencies in the core or launcher).

Report a vulnerability privately: see [SECURITY.md](../SECURITY.md).

## How to read this

- **Verified** means a maintainer re-checked it in the repository or the GitHub API. **Reported** means an audit read the code and described it; it has not been reproduced on a cluster. Nothing here was demonstrated against a live cluster.
- Findings are described at the level needed to plan the fix. Open items stay at this level of detail until they are fixed.
- Severity is the audit's, for a default install: **High** (design gap with code-execution potential), **Medium**, **Low**, **Info**.

## The trust model today

mnemo-warmup verifies that an archive is **intact**: the layer digest and size match the manifest, and the archive hash matches the manifest on disk. It does not verify **who published it**. The registry is trusted. So today:

> Anyone who can write to the archive repository (or intercept an unencrypted registry connection) can publish an archive that every pod with that fingerprint will load. A CDS or AOT archive is trusted input to the JVM, so this is a path to code execution in the application.

This is the single most important gap. Until it is closed, the mitigations are operational: [Phase 0](#phase-0-this-week-settings-and-documentation-no-changes-to-the-product) documents them.

## Findings

| ID | Sev | Area | Finding | Status |
|---|---|---|---|---|
| S1 | High | Archive | No authenticity check: digests prove integrity against the registry's own manifest, not the publisher. Tags are mutable and the fingerprint does not cover the archive. Raised independently by the launcher, core and injector audits. | Verified (no signing exists) |
| S2 | Medium | Fetch | No total download deadline and no size cap (idle timeout only; registry reads use unbounded `ofByteArray`). A hostile or broken registry can stall or fail pod start, against §1 and §3. Java `OutOfMemoryError` is not caught by the fail-open guard. | Verified (Java reads), reported (Go) |
| S3 | Medium | Trainer | The container that runs the application also holds the registry push credential, the ServiceAccount token and, with workload identity, cloud tokens. Application code in training can read them. | Reported |
| S4 | Medium | Chart | With `trainer.serviceAccount.create=false` and no name, the Lease Role is bound to the namespace's `default` ServiceAccount. | Verified in the template |
| S5 | Medium | Chart | The Lease Role covers every Lease in the namespace and the token is mounted into the pod that runs the application. | Reported |
| S6 | Medium | Supply chain | Release artifacts (tools image, chart, CLI jar) have no signatures, SBOM or provenance. The CLI install script checks a checksum fetched from the same release, which gives integrity, not authenticity. | Verified (release assets are `mnemo-warmup`, `mnemo-warmup.jar`, `SHA256SUMS`) |
| S7 | Medium | CI | No action is pinned to a commit SHA (13 distinct tag references); three third-party actions run with a write token or release secrets. Actions settings allow all actions and do not require SHA pinning. | Verified |
| S8 | Medium | CI | Release secrets are repository-level (only a `github-pages` environment exists), there are no tag or branch rulesets, and `main` requires 0 approving reviews with admins not enforced. `build.yml` grants `contents: write` to pull-request runs. | Verified (API) |
| S9 | Medium | Repo | Dependabot alerts and security updates are off, there is no `dependabot.yml`, and code scanning is not configured. Secret scanning and push protection are on. | Verified (API) |
| S10 | Medium/Low | Chart | The tools image is referenced by a mutable tag; the chart cannot take a digest for it. The launcher it supplies runs as the application entrypoint. | Verified for the chart value; reported for the injector |
| S11 | Medium/Low | Trainer | The trainer has no resource limits and runs every 10 minutes; the injector's CronJob lacks history and TTL limits that the chart sets. | Reported |
| S12 | Low | Fetch | The Bearer token `realm` from a registry's 401 response is trusted, so Basic credentials can be sent to a host the registry chooses. Same behaviour in Go and Java. | Reported |
| S13 | Low | Fetch | The archive file name in a stored manifest is under-validated (reserved names, `:`); the registry host in `oci://` is not validated; manifests fetched by digest in the image-config reader are not hashed against the requested digest. | Reported |
| S14 | Low | Fetch | A pod-local writer to the shared cache volume could replace the archive together with its manifest; hashing and use are separate steps. | Reported |
| S15 | Low | Logging | Error messages can include full registry or redirect URLs with presigned query strings. | Reported |
| S16 | Low | Network | `plainHttp` sends credentials in cleartext without a warning; AWS endpoint overrides accept plain `http`. | Reported |
| S17 | Low | Trainer | `http-probe` URLs come from an annotation and may point anywhere (blind SSRF from the trainer, including redirects). | Reported |
| S18 | Low | Chart | Chart trainer and fetch containers have no hardened security context by default (only the install init container does). The node-cache chart has no NetworkPolicy and no digest option. | Reported |
| S19 | Low | Node cache | The node-cache server has no concurrency limit on TokenReview calls, and its store checks mode bits but not the owner. | Reported |
| S20 | Low | Docs | `SECURITY.md` lists `0.2.x` as supported and links the Security tab to `github.com`; `docs/security.md` has no registry-trust statement; `docs/security.md` said the injector blocks `pull-secret` with the image volume (ADR 0012 superseded that; the chart still refuses it, so the `values.yaml` comment is accurate). | Verified |
| S21 | Info | Chart | The generated-resource checksum is unkeyed SHA-256 against accidents, not attackers; documentation should not call it integrity protection. | Reported |

What was checked and found sound (so it is not re-audited blindly): no `InsecureSkipVerify` anywhere; `Authorization` is only sent to the registry's own origin on redirects and https is never downgraded; digests are verified while streaming and before the atomic rename; no shell is used to build the application command; YAML is emitted with SnakeYAML (no string templating of annotation values); `warmup-core` and the launcher have no runtime dependencies; no `pull_request_target` and no untrusted `github.event.*` text in `run:` blocks; the node-cache DaemonSet is non-root, read-only, capabilities dropped and has no hostPath.

## Roadmap

### Phase 0: this week, settings and documentation (no changes to the product)

Goal: remove the cheap risks and state the trust model honestly.

- [ ] Document the trust model in `docs/security.md`: registry write access equals code execution in pods that use that fingerprint (S1). Recommend a dedicated repository per application, immutable tags, pull-only credentials for pods, push credentials only for the trainer.
- [ ] Fix `SECURITY.md` (supported versions, Security tab link) and the stale `values.yaml` comment (S20).
- [ ] Enable Dependabot alerts and security updates; add `.github/dependabot.yml` for `maven`, `gomod`, `github-actions` and `docker` (S9).
- [ ] Add CodeQL for Java, Go and Actions (S9).
- [ ] Pin every action to a full commit SHA, let Dependabot bump them, and set `sha_pinning_required` (S7).
- [ ] Workflows: top-level `contents: read`; a separate deploy job with write access; `persist-credentials: false` on checkouts that run builds (S8).
- [ ] Create a `release` environment with required reviewers and move the Maven Central and GPG secrets into it; add a ruleset protecting `v*` tags and `main` (reviews, required checks including image, chart and e2e, admins enforced); turn on immutable releases (S8, S6).

### Phase 1: before 0.3.0 final, fail-safe fetch and chart defaults

Goal: a bad or hostile registry cannot stall or crash a pod start (§1, §3), and chart defaults are least-privilege.

- [ ] A total download deadline (configurable) and a maximum archive size, refused before download; manifest and config reads capped (about 1 MiB) with the descriptor size enforced; free-space check; catch `Throwable` in the Java fetch entry point (S2).
- [ ] Token `realm` must be on the registry's own host or an operator allow-list; never send credentials over plain http; pin the token scope to the repository (S12).
- [ ] Ignore the archive file name in the manifest (write a fixed name per cache type); validate the `oci://` host; hash manifests fetched by digest; strip query strings from logged URLs (S13, S15).
- [ ] Chart: fail the render when `serviceAccount.create=false` and no name is given; Lease Role limited with `resourceNames` where possible and without verbs that are not needed; `automountServiceAccountToken: false` on the application container (S4, S5).
- [ ] Trainer: copy the workload's `resources` or set safe defaults; add history, TTL and starting-deadline limits to the injector CronJob (S11).
- [ ] Chart: default `securityContext` for the trainer and the fetch container (non-root, no privilege escalation, capabilities dropped, seccomp `RuntimeDefault`); `tools.image.digest` and a documented digest-pinning recipe for both charts (S10, S18).
- [ ] Refuse `plainHttp` except for loopback or in-cluster hosts, and require https for AWS endpoint overrides; default `http-probe` to loopback URLs and same-origin redirects (S16, S17).

### Phase 2: 0.4.0, archive authenticity and trainer isolation

Goal: close S1 and S3.

- [ ] **Design the signing model (ADR first).** Constraint from §4: the launcher and `warmup-core` take no runtime dependencies, so verification must use the standard library. Candidates: a keyed digest or Ed25519 signature over the manifest and archive digest, with the public key supplied by the operator (annotation, ConfigMap or chart value), verified by the launcher with Go's `crypto/ed25519`; optionally cosign-compatible signing in the trainer. Decide the failure rule: an archive that fails verification is treated as a miss (ordinary start, §1), never an error.
- [ ] Implement signing in the trainer and verification in the Go launcher and the Java fetch; make it opt-in in the beta, default-on when stable.
- [ ] Separate the push credential from the container that runs application code: upload in a separate container or step that starts after training; scrub `AWS_*`, `MNEMO_WARMUP_*` and `KUBERNETES_*` from the application child's environment; mount Secrets with `defaultMode: 0400` (S3).
- [ ] Harden the shared cache directory: a mode-0700 subdirectory with an owner check; hash the opened file rather than the path (S14).
- [ ] Node cache: concurrency limit and negative caching for failed TokenReview, owner check on the store, an optional NetworkPolicy (S19, S18).

### Phase 3: release integrity

Goal: users can verify what they install.

- [ ] Sign the tools image keylessly with cosign; publish an SBOM and build provenance (`provenance: mode=max`, `actions/attest-build-provenance`) (S6).
- [ ] Sign the Helm charts (`helm package --sign` or attestations) and verify the mirrored package digest against `index.yaml` before publishing (S6).
- [ ] Attest the CLI jar and wrapper; make `scripts/install.sh` verify the attestation or signature, not only `SHA256SUMS` (S6).
- [ ] Pin base images in `image/Dockerfile` by digest and run the final stage as a non-root user (S6).
- [ ] Replace the settings-file approach in the Maven publish job: secrets only via `env:`, no cache in the secrets job, loopback pinentry for the GPG passphrase.

### Ongoing

- Re-run the audit before each minor release and after any change to fetch, store, auth, the injector or the chart RBAC.
- Keep a security-relevant checklist in the pull-request template: does this touch verification, credentials, RBAC, or a default?
- Track every item above as a GitHub issue on a "Security hardening" milestone; close items with the commit and a test.

## Not in scope

Application start-up side effects during training (documented in `SECURITY.md`) and registry storage growth are operator responsibilities, as stated there.
