# ADR 0004: A JDK-free tools image that runs inside the application image

Status: accepted (2026-11-10) · Resolves issue #16 (supersedes its "bundle a JDK, per-JDK variants" wording) · Amended by issue #56: shell-free images are supported through a native launcher

## Context

An archive is valid only for the exact JDK build, the classpath JARs (content, path, modification
time) and the JVM options it was trained with. `fetch` and the trainer must therefore see the
application's own JDK and JAR files. An image that bundles the CLI plus a JDK cannot see the
application's JARs, and matching only the JDK version would not give the same files, paths or times.

## Decision

Publish **one JDK-free "tools" image** (`ghcr.io/baokhang83/mnemo-warmup-tools:<version>`, about 7 MB,
busybox base, linux/amd64 and linux/arm64) holding `/opt/mnemo-warmup/{mnemo-warmup.jar, bin/mnemo-warmup,
bin/mnemo-warmup-exec}`. The tool always runs **inside the application image**:

- **Copy at deploy time:** an init container based on the tools image runs
  `cp -R /opt/mnemo-warmup/. /mnemo-tools/` (`-a` also works but prints ownership errors for non-root users) into a shared `emptyDir`. The `fetch` init container and the
  trainer then use the *application image* with `command: [/mnemo-tools/bin/mnemo-warmup, fetch, ...]`,
  and the app container uses `/mnemo-tools/bin/mnemo-warmup-exec`.
- **Bake in:** `COPY --from=ghcr.io/baokhang83/mnemo-warmup-tools:<version> /opt/mnemo-warmup /opt/mnemo-warmup`.

`bin/mnemo-warmup` is a launcher that runs the jar with the image's own `java`. If the image has no Java
or one older than 21 (the CLI needs 21+), the launcher keeps the pod starting (§3): `fetch` exits 0,
`run --print` prints nothing, and `run [--spawn] -- cmd` execs `cmd` unchanged; other commands fail. Per-JDK image variants are not needed, because the JDK is the application's.

`scripts/verify-image.sh` checks the whole chain in Docker with a local registry on JDK 21 (AppCDS) and
25 (AOT), and runs in CI (`image.yml`). Tags publish the image to GHCR.

## Rejected

- **Per-JDK images with a bundled JDK (the issue's wording):** the tool cannot see the application's
  JARs, so fingerprints do not match unless the application files are also mounted into it.
- **Distroless or scratch base for the tools image:** the Helm chart's copy pattern needs `cp`, and its wrapper needs `sh`. The tools image stays busybox; shell-free *application* images are handled by the native launcher (below).

## Consequences

- The application image needs Java 21+ for the tool to run in it. With an older Java the cache is skipped.
- Distroless application images have no shell. `bin/mnemo-launcher`, a static Go program in the same image
  (`launcher/`, built for amd64 and arm64 with `CGO_ENABLED=0`), does the install, fetch and application-start
  steps without one. It locates `java` the way the application's own command does, runs the jar, and replaces
  itself with the final command so the JVM is PID 1. Cache failures fall back to the original command (§1-§3);
  `install` and a missing `java` for the application itself cannot, and fail visibly. The jar prints the final
  command with `run --print --format nul` (NUL-terminated arguments), so no shell quoting is needed. The offline
  injector (#55) uses the launcher for all generated containers; the Helm chart keeps the shell scripts.
  Rejected for the launcher: spawning the application as a child (needs signal forwarding and keeps a parent
  resident), and a Java version probe (the jar already fails fast on an old Java, which takes the fallback).
  Verified on `gcr.io/distroless/java21-debian12` by `scripts/verify-image.sh`. See the
  [reference](../reference.md#shell-free-distroless-application-images) for limits.
- The Helm chart (#17) uses the copy-at-deploy pattern and must run the trainer, `fetch` and the app from the same image.
