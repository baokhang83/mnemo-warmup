<div align="center">
  <img width="300" height="300" alt="image" src="https://github.com/user-attachments/assets/70f9a302-2f30-409c-b170-8f437391778a" />
  <h1>mnemo-warmup</h1>
</div>

**Shared JVM startup caches for Kubernetes.**

mnemo-warmup trains an AppCDS or Project Leyden AOT archive **once, inside your cluster**, and delivers it to every replica of your
Java application. A pod starts from the archive when it fits its launch exactly, and starts the ordinary way when it does not.
Built for services whose pods start often: frequent deployments, autoscaling scale-ups and node moves.

[![build](https://github.com/baokhang83/mnemo-warmup/actions/workflows/build.yml/badge.svg)](https://github.com/baokhang83/mnemo-warmup/actions/workflows/build.yml)
[![coverage](https://raw.githubusercontent.com/baokhang83/mnemo-warmup/refs/heads/gh-pages/badges/jacoco.svg)](https://baokhang83.github.io/mnemo-warmup/)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Status: alpha](https://img.shields.io/badge/status-alpha-yellow)
![Java 21+](https://img.shields.io/badge/java-21%2B-informational)


## What you get

- **Faster pod starts, without touching your application.** Your code and build stay as they are. Pods start from a ready-made JVM archive instead of
  loading and linking every class from scratch.
- **Your pods keep starting, whatever happens to the cache.** If the archive is missing, outdated or the registry is unreachable, the pod starts exactly
  as it does today, and its log says why.
- **Set it up once.** When your image or JVM options change, the trainer builds a fresh archive on its own and replaces damaged entries; nothing to
  schedule by hand.
- **Fits what you already run.** A Helm chart, two init containers and the registry you already have. No controller, no webhook, no CRD, and `java`
  stays PID 1.
- **AppCDS or the AOT cache, chosen for you** from your image's JDK (AppCDS on 21 and later, the Leyden AOT cache on 25).

## How it works


<div align="center">
  <img width="557" height="313" alt="How mnemo-warmup works: a trainer CronJob fills the registry, every pod fetches and validates the archive" src="https://github.com/user-attachments/assets/6e775dcb-7c3f-42ec-8e26-cc7403750373" />
</div>

- A **fingerprint** identifies what an archive is valid for: the JDK build, the classpath JAR contents, and the heap and GC options.
- A **trainer CronJob** runs your application image with the tool, trains an archive for the fingerprint and uploads it, once.
- Each **pod** gets two extra init containers and starts through a small wrapper; `java` is still PID 1.
- If looking up or validating the archive fails, the pod starts without it and logs why. Deployment problems are different, see
  [What can still stop a pod](#what-can-still-stop-a-pod).

## Requirements

What the setup below needs from your application and your cluster.

- **Application image:** Java **21 or newer**, and **`/bin/sh`** (the chart's launcher and wrapper are shell scripts, so shell-free images such
  as distroless are not supported by the chart).
- **Launch command:** `java [options] -jar <jar> [args]`. Launches with `-cp` and a main class are left to start normally, without a cache.
- **JARs fixed at build time:** a Spring Boot **3.3+** application extracted with `-Djarmode=tools extract` (step 1), or another executable JAR
  whose dependencies are listed in its `Class-Path`.
- **Registry:** an OCI registry (the OCI distribution API) that your cluster can reach and that accepts **Basic or Bearer-token** login from a
  Docker-config Secret, with **tag immutability off** for the archive repository. Cloud credential helpers and workload identity (for example
  ECR with IAM roles) are not supported yet. Archives can reach hundreds of MB, so check the registry's blob-size limit.
- **Kubernetes:** `batch/v1` CronJobs (1.21 or newer) and the `coordination.k8s.io/v1` Lease API; tested only on 1.35.
- **Your application's startup runs once more, for real, during training** (see [What it costs](#what-it-costs)).

## Configure it on a cluster

The Helm chart is part of the [v0.2.0 release](https://github.com/baokhang83/mnemo-warmup/releases/tag/v0.2.0); it is not published to a chart
repository yet. Pick the release that matches the image tag in the chart's defaults.

### 1. Fix the JARs when you build the image

An archive remembers the JAR files it was trained on, so the trainer and every pod must run the same files. For Spring Boot, extract
the JAR at build time:

```dockerfile
RUN java -Djarmode=tools -jar target/app.jar extract --destination /out
...
COPY --from=build /out /app
CMD ["java", "-jar", "/app/app.jar"]
```

Build one image per JDK you run: the image's JDK decides the cache type (AppCDS on 21 and later, the AOT cache on 25).

### 2. Add the chart to your application's chart

```bash
git clone --depth 1 --branch v0.2.0 https://github.com/baokhang83/mnemo-warmup.git
cp -R mnemo-warmup/deploy/helm/mnemo-warmup ./mnemo-warmup      # next to your chart; its defaults point at the 0.2.0 tools image
```

```yaml
# your-chart/Chart.yaml
dependencies:
  - name: mnemo-warmup
    version: 0.1.0
    repository: file://../mnemo-warmup
    alias: warmup
```

### 3. Create the namespace and the registry Secrets

The Secrets must exist in the namespace you deploy to, before you deploy (a missing Secret keeps the pod from starting). Skip them only for an
anonymous registry. The pull Secret needs read access to the archive repository, the push Secret read and write:

```bash
export NS=my-namespace
kubectl create namespace $NS
kubectl -n $NS create secret docker-registry warmup-pull --docker-server=registry.example.com --docker-username=... --docker-password=...
kubectl -n $NS create secret docker-registry warmup-push --docker-server=registry.example.com --docker-username=... --docker-password=...
```

### 4. Set the values

```yaml
# your-chart/values.yaml
warmup:
  app:
    image: { repository: registry.example.com/team/my-app, tag: "1.4.2" }  # the SAME image your Deployment runs
    jar: /app/app.jar
    jvmArgs: ["-Xmx512m"]          # the heap/GC options your app really runs with; the Deployment's java command is built from this list
  store:
    url: oci://registry.example.com/team/warmup     # where archives are kept
    pullSecret: warmup-pull                          # omit both for an anonymous registry
    pushSecret: warmup-push
  trainer:
    exitMode: spring-refresh       # use http-probe (and trainer.probe.url) for apps that are not Spring Boot
```

The trainer runs your application for real, so it needs your configuration too (`warmup.trainer.env`, `envFrom`, `volumes`, `volumeMounts`).
If your Deployment sets `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS`, repeat them in `warmup.app.env`: they are part of what an archive is valid for.

### 5. Wire your Deployment

The helpers print list items, so put each `include` **inside the list you already have** and keep your own entries next to them. A complete example is
[`examples/helm/example-app`](examples/helm/example-app/templates/deployment.yaml).

```yaml
spec:
  template:
    spec:
      volumes:
        # ...your own volumes...
        {{- include "mnemo-warmup.volumes" .Values.warmup | nindent 8 }}
      initContainers:
        # ...your own init containers, if any...
        {{- include "mnemo-warmup.installInitContainer" .Values.warmup | nindent 8 }}
        {{- include "mnemo-warmup.fetchInitContainer" .Values.warmup | nindent 8 }}
      containers:
        - name: app
          image: {{ include "mnemo-warmup.appImage" .Values.warmup | quote }}
          command: {{ include "mnemo-warmup.command" .Values.warmup }}     # the wrapper decides, then execs java
          args:                                                              # your original command, moved here
            - java
            {{- range .Values.warmup.app.jvmArgs }}
            - {{ . | quote }}
            {{- end }}
            - -jar
            - {{ .Values.warmup.app.jar | quote }}
          env:
            # ...your own env...
            {{- include "mnemo-warmup.env" .Values.warmup | nindent 12 }}
          volumeMounts:
            # ...your own mounts...
            {{- include "mnemo-warmup.volumeMounts" .Values.warmup | nindent 12 }}
```

The wrapper replaces the container's entrypoint, so whatever `command` and `args` the container had (or the image's `ENTRYPOINT`) must become the
`java ...` arguments above. Options that do not affect an archive (`-D` properties, logging) can be added after the `jvmArgs` loop.

### 6. Deploy, train, then check

```bash
helm dependency update your-chart
helm upgrade --install my-app your-chart -n $NS
kubectl -n $NS get cronjob                                         # the trainer, e.g. my-app-warmup-trainer
kubectl -n $NS create job first-train --from=cronjob/my-app-warmup-trainer
kubectl -n $NS wait --for=condition=complete job/first-train --timeout=15m
kubectl -n $NS logs job/first-train -c trainer                     # must say: train: TRAINED ...  (FAILED prints the reason)
```

The trainer also runs every 10 minutes by itself (`warmup.trainer.schedule`). Pods that start before the first archive exists run without it.
Only when the outcome says `TRAINED` (or `ALREADY_STORED`), restart the pods and look at what they did:

```bash
kubectl -n $NS rollout restart deploy/my-app
kubectl -n $NS logs <new-pod> -c mnemo-warmup-fetch     # Archive: ... (APP_CDS)    or    (LEYDEN_AOT) on JDK 25
kubectl -n $NS logs <new-pod> -c app | grep "run:"      # run: using APP_CDS archive ...
```

## What it costs

- **Training runs a real instance of your application**, once per new fingerprint (for Spring Boot until the context has refreshed). Whatever your
  startup does (connections, migrations, background jobs, calls to other services) happens in the trainer pod too, so give it a safe configuration
  with `warmup.trainer.env` and `envFrom`.
- **Pods do extra work before they are ready:** two init containers, the archive download, and hashing the classpath JARs (`fetch`, `run`) and the archive
  (`run`). Larger classpaths and archives cost more. The saving only counts if it exceeds this.
- **One archive per fingerprint** (per image, JDK and relevant JVM options) is stored in your registry; old ones are not removed automatically.

## Measure the benefit

An archive being selected proves nothing about speed. Compare **pod start to Ready**, with and without the cache, over several restarts:

```bash
kubectl -n $NS get pods -l app=my-app -o json | jq -r '.items[] | [.metadata.name,
  (((.status.conditions[]|select(.type=="Ready").lastTransitionTime|fromdate) - (.status.startTime|fromdate)))] | @tsv'
```

That is seconds from the kubelet starting the pod (including both init containers) to Ready. For JVM startup alone,
`mnemo-warmup bench --jar /app/app.jar` compares no cache, AppCDS and AOT locally ([reference](docs/reference.md#measure-the-savings-bench)).
The example's `scripts/e2e-kind.sh` proves behaviour, not speed.

## What can still stop a pod

Failures **looking up or validating** the archive never stop a pod: it runs without the cache and says why in its log.

| Situation | What you see |
|---|---|
| No archive yet (first deploy) | `fetch`: "No usable archive"; `run`: "no archive was fetched" |
| JVM options or env differ from the trainer's | `run`: "archive does not match this launch: [...]" |
| New image or JARs | a new fingerprint: the trainer trains again within one schedule interval |
| Registry unreachable or login refused | `fetch` logs the reason and exits 0 |
| The image has no Java, or Java older than 21 | the launcher skips the tool and your app starts as before |

**Deployment failures are not covered**, because the pod cannot get as far as running the tool:

- a `warmup.store.pullSecret` / `pushSecret` that does not exist in the namespace (the pod waits for the missing volume);
- the tools image or the application image cannot be pulled;
- the application image has no `/bin/sh` (the init container cannot start);
- a Deployment edit that gets the entrypoint or arguments wrong (the app command fails as it would anyway).

## Disable or remove it

Suspending the trainer (`warmup.trainer.suspend: true`) only stops **new training**: pods keep fetching and using the archives that already exist. To stop
using the cache:

1. Take the `include` lines out of your Deployment and restore its original `command` and `args`; redeploy. New pods start without the init containers, the
   wrapper or the cache.
2. Set `warmup.trainer.enabled: false` (or remove the dependency) to delete the trainer CronJob, its ServiceAccount and its Role.
3. Optionally delete the archives from the registry (tags `fp-<hex>` in the archive repository) and any Leases left behind
   (`kubectl -n $NS get lease | grep mnemo-warmup`; they expire on their own).

## Current scope

- Alpha: command-line options, chart values and the manifest format may still change.
- Verified in CI on a real Kubernetes cluster for JDK 21 (AppCDS) and JDK 25 (AOT); more applications and architectures are being added.
- The tool needs **Java 21+ and `/bin/sh` in your application image**, and the JARs must be fixed at build time (step 1).
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
