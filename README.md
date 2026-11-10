<div align="center">
  <img width="300" height="300" alt="image" src="https://github.com/user-attachments/assets/70f9a302-2f30-409c-b170-8f437391778a" />
  <h1>mnemo-warmup</h1>
</div>

**Faster JVM starts on Kubernetes, shared by every replica.**

A JVM starts much faster when it reuses an archive of classes it has already loaded (AppCDS, or the Leyden AOT cache on JDK 25;
speedups of 30 to 60% are commonly reported). mnemo-warmup trains that archive **once, inside your cluster**, keeps it in a
registry, and has every pod fetch it before the application starts. If the archive does not fit the pod exactly, or anything goes
wrong, the pod simply starts the normal way.

[![build](https://github.com/baokhang83/mnemo-warmup/actions/workflows/build.yml/badge.svg)](https://github.com/baokhang83/mnemo-warmup/actions/workflows/build.yml)
[![coverage](https://raw.githubusercontent.com/baokhang83/mnemo-warmup/refs/heads/gh-pages/badges/jacoco.svg)](https://baokhang83.github.io/mnemo-warmup/)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Status: alpha](https://img.shields.io/badge/status-alpha-yellow)
![Java 21+](https://img.shields.io/badge/java-21%2B-informational)

> **Status: alpha.** The whole chain is verified on a real kind cluster with JDK 21 (AppCDS) and JDK 25 (AOT). Not proven yet: amd64
> runs, more than one application, private-registry login. The Helm chart is not published (use it from this repository), and
> options and formats may still change. Details in [Limitations](#limitations) and the [project plan](docs/PLAN.md).

## How it works

```
 trainer CronJob (your app image)                     every pod (your Deployment)
   computes the fingerprint                             init container: fetch  -- looks up the archive, verifies it
   trains if the registry has none   ---> registry <--- app container:   run    -- uses it only if it fits this exact launch
```

- A **fingerprint** identifies what an archive is valid for: the JDK build, the classpath JAR contents, and the heap and GC options.
- A **trainer CronJob** runs your application image with the tool, trains an archive for the fingerprint and uploads it, once.
- Each **pod** gets two extra init containers and starts through a small wrapper; `java` is still PID 1.
- A missing, stale or unreachable archive is never an error: the pod starts without it and logs why.

## Configure it on a cluster

You need: an application image with **Java 21+**, an **OCI registry** reachable from the cluster (any container registry works), and Helm.
The Helm chart is in this repository (`deploy/helm/mnemo-warmup`); it is not published to a chart repository yet.

### 1. Fix the JARs when you build the image

An archive remembers the JAR files it was trained on, so the trainer and every pod must run the same files. For Spring Boot, extract
the JAR at build time (other executable JARs with a `Class-Path` work as they are):

```dockerfile
RUN java -Djarmode=tools -jar target/app.jar extract --destination /out
...
COPY --from=build /out /app
CMD ["java", "-jar", "/app/app.jar"]
```

Build one image per JDK you run: the image's JDK decides the cache type (AppCDS on 21 and later, the AOT cache on 25).

### 2. Add the chart to your application's chart

Copy `deploy/helm/mnemo-warmup` next to your chart (or point at it with `file://`) and declare it as a dependency under the alias `warmup`:

```yaml
# your-chart/Chart.yaml
dependencies:
  - name: mnemo-warmup
    version: 0.1.0
    repository: file://../mnemo-warmup
    alias: warmup
```

### 3. Set the values

```yaml
# your-chart/values.yaml
warmup:
  app:
    image: { repository: registry.example.com/team/my-app, tag: "1.4.2" }  # the SAME image your Deployment runs
    jar: /app/app.jar
    jvmArgs: ["-Xmx512m"]          # the heap/GC options your app really runs with; the Deployment must use the same list
  store:
    url: oci://registry.example.com/team/warmup     # where archives are kept
    pullSecret: warmup-pull                          # dockerconfigjson Secrets; omit both for an anonymous registry
    pushSecret: warmup-push
  trainer:
    exitMode: spring-refresh       # use http-probe (and trainer.probe.url) for apps that are not Spring Boot
```

The trainer runs your application for real, so it needs your configuration too: use `warmup.trainer.env`, `envFrom` and `volumes`.
Create the two Secrets with `kubectl create secret docker-registry warmup-pull --docker-server=... --docker-username=... --docker-password=...`
(the pull one needs read access to the repository, the push one read and write).

### 4. Wire your Deployment

Add the chart's helpers to your pod template (a complete example is in [`examples/helm/example-app`](examples/helm/example-app/templates/deployment.yaml)):

```yaml
spec:
  template:
    spec:
      volumes:
        {{- include "mnemo-warmup.volumes" .Values.warmup | nindent 8 }}
      initContainers:
        {{- include "mnemo-warmup.installInitContainer" .Values.warmup | nindent 8 }}
        {{- include "mnemo-warmup.fetchInitContainer" .Values.warmup | nindent 8 }}
      containers:
        - name: app
          image: {{ include "mnemo-warmup.appImage" .Values.warmup | quote }}
          command: {{ include "mnemo-warmup.command" .Values.warmup }}     # the wrapper that decides, then execs java
          args: ["java", "-Xmx512m", "-jar", "/app/app.jar"]                 # your usual command, same options as warmup.app.jvmArgs
          env:
            {{- include "mnemo-warmup.env" .Values.warmup | nindent 12 }}
          volumeMounts:
            {{- include "mnemo-warmup.volumeMounts" .Values.warmup | nindent 12 }}
```

### 5. Deploy, then train

```bash
helm dependency update your-chart
helm upgrade --install my-app your-chart -n my-namespace
```

The trainer CronJob runs every 10 minutes (`warmup.trainer.schedule`). To train right away:

```bash
kubectl -n my-namespace get cronjob                                   # find the trainer, e.g. my-app-warmup-trainer
kubectl -n my-namespace create job first-train --from=cronjob/my-app-warmup-trainer
kubectl -n my-namespace logs job/first-train -c trainer               # expect: train: TRAINED ...
```

Pods that start before the archive exists simply run without it.

### 6. Check that pods use it

```bash
kubectl -n my-namespace rollout restart deploy/my-app
kubectl -n my-namespace logs <new-pod> -c mnemo-warmup-fetch     # Archive: ... (APP_CDS)   or   (LEYDEN_AOT) on JDK 25
kubectl -n my-namespace logs <new-pod> -c app | grep "run:"      # run: using APP_CDS archive ...
```

## When it falls back

The pod always starts. It runs without the archive, and says why in its log:

| Situation | What you see |
|---|---|
| No archive yet (first deploy) | `fetch`: "No usable archive"; `run`: "no archive was fetched" |
| JVM options or env differ from the trainer's | `run`: "archive does not match this launch: [...]" |
| New image or JARs | a new fingerprint: the trainer trains again within one schedule interval |
| Registry unreachable or login refused | `fetch` logs the reason and exits 0 |
| The image has no Java, or Java older than 21 | the launcher skips the tool and your app starts as before |

## Limitations

- The tool needs **Java 21+ in your application image**, and the JARs must be fixed at build time (step 1).
- The first archive appears after the trainer's first run (up to one schedule interval, unless you start a job by hand).
- Tested on **arm64 only**, with one small Spring Boot application, on JDK 21 and 25. Several trainers racing and private-registry login are untested.
- Registry login supports Docker-config basic and token auth; cloud credential helpers and workload identity are not implemented.
- The Helm chart is not published; use it from this repository.

## More

- [Reference](docs/reference.md): every command and option, the cache types, the manifest and fingerprint, the container image, the trainer and chart in detail.
- [Example](examples/README.md): a Spring Boot app, an example chart, and `scripts/e2e-kind.sh`, which runs the whole chain on a kind cluster.
- [Design decisions](docs/adr/) and the [project plan and roadmap](docs/PLAN.md).
- Related: [`mnemo-jvm-warden`](https://github.com/baokhang83/mnemo-jvm-warden) resizes running pods in place; the planned integration is that warden injects the init containers (not built yet). `warmup-core` is on Maven Central (`io.github.baokhang83.mnemo:warmup-core`).

## Building from source

Requirements: JDK 21+ and Maven 3.9+. The code compiles with `--release 21` and is tested
on JDK 25.

```bash
mvn verify
java -jar warmup-cli/target/mnemo-warmup.jar --version
java -jar warmup-cli/target/mnemo-warmup.jar build --help
```

The build produces a self-contained `warmup-cli/target/mnemo-warmup.jar`; the examples above
call it as `mnemo-warmup`.

## Contributing

The project is at a very early stage and the design is still moving. Issues and
discussion about the open decisions in the plan are the most useful contribution right
now.

## License

Apache License 2.0. See [LICENSE](LICENSE).
