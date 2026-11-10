# ADR 0007: Offline workload injection contract

Status: proposed · Issue #54 · v0.2.5

## Context

Today an application chart repeats its image, JAR, JVM arguments and environment in the warmup chart, then includes multiple pod-template helpers. A mismatch can prevent cache reuse. Helm renders templates before a post-renderer sees the resulting resources; a post-renderer cannot assume that image entrypoints or cluster defaults appear in that YAML.

## Decision

The v0.2.5 injector is an offline transformation of a complete rendered resource stream. It changes opted-in Deployments and emits their trainer resources in the same stream. It does not call the Kubernetes API, inspect registries, resolve Secrets, or create resources as a side effect. A preview reports discoveries, overrides, generated resources and a diff. A Helm adapter preserves the post-renderer contract: read YAML from standard input, emit only transformed YAML on standard output, and put diagnostics on standard error. An error returns a nonzero exit and does not emit a partial resource stream.

### Opt-in and discovery

Annotations live on `spec.template.metadata.annotations`:

- `warmup.mnemo.io/enabled: "true"` opts in. Missing or `"false"` leaves the Deployment unchanged. Other values are errors.
- `warmup.mnemo.io/store` is the explicit `oci://host/repository` archive store. It is required for an enabled workload; no application-image repository is assumed writable.
- `warmup.mnemo.io/container` selects a container by name when more than one application container exists. With exactly one container, its name is discovered. Missing, ambiguous or duplicate selections are errors.
- `warmup.mnemo.io/command` optionally supplies the complete original launch as a JSON array of strings, for example `["java","-jar","/app/app.jar"]`. It overrides discovery; malformed JSON, an empty array or an unsupported launch is an error. Use it when the image supplies the entrypoint or when an explicit launch must replace the Deployment's launch.
- `warmup.mnemo.io/exit-mode` is required and is either `spring-refresh` or `http-probe`. For `http-probe`, `warmup.mnemo.io/probe-url` is required and specifies the URL reachable from the trainer pod. A probe URL with `spring-refresh` is an error.

A supported launch has an explicit `command` and optional `args` that concatenate to `java [JVM options] -jar <jar> [application args]`, with a Java executable path or `java`. Only the selected container is modified. The injector preserves argument boundaries and the complete original vector, including application arguments, and derives the JAR and JVM options from it. It does not interpret shell commands, argument files or unrecognized JVM option forms by guessing. Image-provided ENTRYPOINT/CMD are not in the rendered Deployment; absent or incomplete commands require the explicit command override, or the injector reports an error before deployment. Overrides are validated against the same launch rules.

Image, pull policy, explicit JVM environment and applicable pod configuration are read from the selected workload. Existing containers, init containers, volumes, mounts, environment and unrelated fields are preserved. Generated names are deterministic and scoped to the release/workload; a collision with user-owned names is an error. Runtime-only image resolution, dynamically injected environment, and flags hidden in image metadata cannot be inferred reliably; preview must disclose them and require an override when necessary for correctness.

### Trainer and safety

The emitted CronJob is derived from the selected Deployment in the same namespace and stream. It uses the same image, JAR and JVM arguments and only the required application configuration. Preview lists copied environment, Secret and volume references and the trainer identity; users can override trainer configuration. The trainer starts the application for real: migrations, consumers and background jobs can run. No Secret values are printed. Credential configuration for registry read and write is explicit; image pull credentials are not presumed to grant either. The existing Lease protocol and separate trainer RBAC remain in force.

Spring refresh and readiness-probe inference are not automatic in this contract: require an explicit, validated trainer mode and probe details when needed. This avoids treating a framework or probe guess as safe. Archive read or validation failures fall back to the original launch (§1–§3); unsupported injection input instead fails the render with a clear diagnostic. Missing images or Kubernetes Secrets may still block pod startup.

### Reconciliation and removal

The declarative source is the uninjected Deployment plus opt-in annotations. The transformer marks its owned output with a versioned marker and stores enough original command information for deterministic reinjection or removal. It must not overwrite user changes to managed fields silently. Repeated transformation of unchanged input produces equivalent output, without duplicate containers, mounts, environment entries or trainers. Conflicting markers, names or edits produce actionable errors.

Removing the annotations from the source and rendering again emits the original Deployment without warmup additions and omits the generated trainer resources. For already transformed input, removal restores only values the injector owns, preserves other edits, and reports conflicts rather than guessing. Helm and GitOps own deletion through their normal resource reconciliation; the offline tool does not delete live objects. No archive tags, external Secrets or user-owned resources are deleted on removal.

## Verification for implementation

- Golden and round-trip fixtures cover explicit Java commands, spaced arguments, image defaults, multiple containers, unsupported launches, collisions and overrides.
- Repeated injection is stable; disabling and removal preserve unrelated fields and restore the original command. Test Helm upgrade, rollback, post-renderer stderr/stdout and GitOps reconciliation, including missing or invalid input.
- Test trainer configuration, credential separation, Secret redaction, safe override handling, cache miss and failure paths. Run a real Kubernetes end-to-end test before presenting the contract as implemented.

## Alternatives and consequences

A mutating webhook and controller could remove the post-render step, but add availability, TLS, permissions and reconciliation responsibilities. They remain in v0.3.0. This contract favors deterministic, reviewable manifests; it requires explicit input where the Deployment cannot reveal the original launch or safe training settings. The current chart remains supported until the injector exists.
