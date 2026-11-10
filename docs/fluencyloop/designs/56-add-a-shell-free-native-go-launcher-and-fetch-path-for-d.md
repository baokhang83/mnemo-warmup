# Design: shell-free native launcher for distroless images (#56)

A static Go binary, `bin/mnemo-launcher`, replaces the shell for install, fetch and app start. The Java CLI keeps all cache logic; the launcher locates `java`, runs the jar, and replaces itself with the final command. The `sh` scripts and the busybox tools image stay for the Helm chart.

## Shapes

```mermaid
classDiagram
  class OfflineInjector {
    +initContainer: launcher install
    +fetch: launcher fetch
    +app: launcher exec
  }
  class Launcher {
    <<Go, static, amd64+arm64>>
    +main(args)
  }
  class Installer { +install(dest) }
  class FetchRunner { +fetch(args) exit 0 on any failure }
  class ExecRunner { +exec(cmd) syscall.Exec }
  class JavaLocator { +find() PATH, JAVA_HOME, JDK paths }
  class WarmupJar {
    +fetch
    +run --print --format nul
  }
  OfflineInjector ..> Launcher : emits commands
  Launcher *-- Installer
  Launcher *-- FetchRunner
  Launcher *-- ExecRunner
  FetchRunner --> JavaLocator
  ExecRunner --> JavaLocator
  FetchRunner --> WarmupJar
  ExecRunner --> WarmupJar
```

## Pod start

```mermaid
sequenceDiagram
  participant I as Install init (tools image)
  participant F as Fetch init (app image)
  participant A as App container (app image)
  participant J as Warmup jar
  participant R as OCI registry
  I->>I: launcher install /mnemo-tools
  F->>J: spawn java -jar fetch --out /mnemo-cache
  J->>R: pull and validate archive
  R-->>J: archive or miss
  J-->>F: exit status
  Note over F: any failure: log, exit 0
  A->>J: spawn run --print --format nul -- java -jar app.jar
  J-->>A: final argv or nothing
  Note over A: syscall.Exec(argv), JVM is PID 1
```

## Decisions to confirm

1. Keep the shell scripts and busybox base; add `bin/mnemo-launcher` beside them; the injector switches to it.
2. Add `run --print --format nul` so the launcher receives exact argv without `eval`.
3. No Java version probe in the launcher; a too-old `java` fails in the jar and takes the existing fallback.
