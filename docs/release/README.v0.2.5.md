<!-- Draft README for the v0.2.5 release. Copy it over README.md when v0.2.5 is tagged; see docs/release/v0.2.5.md. Links are written for README's location at the repository root. -->
<div align="center">
  <img width="300" height="300" alt="image" src="https://github.com/user-attachments/assets/70f9a302-2f30-409c-b170-8f437391778a" />
  <h1>mnemo-warmup</h1>
</div>

<div align="center">

<b>Shared JVM startup caches for Kubernetes.</b>

<p>
mnemo-warmup trains an AppCDS or Project Leyden AOT archive <b>once, inside your cluster</b>, and delivers it to every replica of your
Java application. A pod starts from the archive when it fits its launch exactly, and starts the ordinary way when it does not.
Built for services whose pods start often: frequent deployments, autoscaling scale-ups and node moves.
</p>

<a href="https://github.com/baokhang83/mnemo-warmup/actions/workflows/build.yml"><img alt="build" src="https://github.com/baokhang83/mnemo-warmup/actions/workflows/build.yml/badge.svg"></a>
<a href="https://baokhang83.github.io/mnemo-warmup/"><img alt="coverage" src="https://raw.githubusercontent.com/baokhang83/mnemo-warmup/refs/heads/gh-pages/badges/jacoco.svg"></a>
<a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-blue.svg"></a>
<img alt="Status: alpha" src="https://img.shields.io/badge/status-alpha-yellow">
<img alt="Java 21+" src="https://img.shields.io/badge/java-21%2B-informational">

<p>
<a href="#try-the-example">Try the example</a> &middot;
<a href="#add-it-to-an-application">Add it to an application</a> &middot;
<a href="docs/reference.md">Reference</a>
</p>

</div>

## Why use it?

Java applications repeat class loading and linking whenever a pod starts. AppCDS and the JDK's AOT cache can reuse some of that work, but creating a cache and getting it to every pod takes effort. mnemo-warmup handles the lifecycle:

1. A **trainer** runs your application and stores the cache in an OCI registry.
2. New pods **fetch** the matching cache before starting.
3. A small **launcher** checks that the cache fits the application and starts Java with it, so `java` is still PID 1.
4. When the image or JVM options change, a new cache is trained.

AppCDS is used on JDK 21 and later, the Leyden AOT cache on JDK 25. If a cache is missing or does not fit, the pod starts the ordinary way and its log says why.

> **Status: alpha.** Command-line options, annotations, chart values and the manifest format may still change. Validate both the benefit and the deployment behaviour with your own workload before you rely on it. [What has and has not been verified](docs/operations.md#known-limitations).

## Try the example

Run the bundled Spring Boot application through the whole annotated path on a throw-away [kind](https://kind.sigs.k8s.io/) cluster, on a Temurin and on a distroless Java image:

```bash
git clone --depth 1 --branch v0.2.5 https://github.com/baokhang83/mnemo-warmup.git mnemo-warmup-src
cd mnemo-warmup-src
scripts/e2e-kind-inject.sh temurin     # needs docker, kind, kubectl, Helm 3, Java 21+ and Maven; several minutes
```

It previews and installs an annotated Deployment, shows it starting without a cache, trains one, restarts the pods onto it, changes a JVM option, corrupts the stored archive, stops the registry, and removes everything, checking at each step that the application still starts. See [`examples/`](examples/README.md).

## Add it to an application

### 0. Get the CLI

The CLI runs on your machine for the preview and as the Helm post-renderer. It needs Java 21 or newer. Copy it from the tools image (the same image the injected init containers use):

```bash
docker create --name mnemo-tools ghcr.io/baokhang83/mnemo-warmup-tools:0.2.5
docker cp mnemo-tools:/opt/mnemo-warmup ./mnemo-warmup-tools && docker rm mnemo-tools
export PATH="$PWD/mnemo-warmup-tools/bin:$PATH"
mnemo-warmup --version
```

Helm 3 needs an executable to call as the post-renderer. Save this as `mnemo-warmup-inject` (and `chmod +x` it):

```sh
#!/bin/sh
exec mnemo-warmup inject "$@"
```

### 1. Annotate the Deployment

Add three annotations to the Deployment's **pod template**, and give the container an explicit Java command (`java [options] -jar <jar> [args]`):

```yaml
spec:
  template:
    metadata:
      annotations:
        warmup.mnemo.io/enabled: "true"
        warmup.mnemo.io/store: "oci://registry.example.com/team/my-app-warmup"
        warmup.mnemo.io/exit-mode: "spring-refresh"     # or http-probe, with warmup.mnemo.io/probe-url
    spec:
      containers:
        - name: app
          image: registry.example.com/team/my-app:1.4.2
          command: ["java"]
          args: ["-Xmx512m", "-jar", "/app/app.jar"]
```

The store is a repository for caches, not for your application image. [Every annotation and override](docs/configuration.md#annotations).

### 2. Preview

```bash
helm template my-app ./my-chart --namespace my-namespace \
  | mnemo-warmup inject --namespace my-namespace --preview
```

The preview names the application container, image and JAR, the store, the trainer mode, and what the trainer copies from your workload (environment variable names, `envFrom` names, volumes and mounts, **never values**), then lists the Kubernetes fields it would add or change. The trainer starts your application with that configuration, so read it. A launch the injector cannot handle is reported here with the reason, and nothing is changed.

### 3. Deploy

```bash
helm upgrade --install my-app ./my-chart --namespace my-namespace \
  --post-renderer ./mnemo-warmup-inject \
  --post-renderer-args=--namespace=my-namespace
```

The injector adds the install and fetch init containers, starts your application through the launcher, and adds a trainer CronJob with its ServiceAccount and Role. Your application's source does not change. With Helm 4, or without Helm, pipe `helm template` through `mnemo-warmup inject` and apply the result ([details](docs/offline-injector.md)).

> **Spring Boot:** the trainer and every pod must run the same JAR files, so extract the Spring Boot JAR when you build the image. [Preparing the image](docs/configuration.md#spring-boot), with a distroless example.

### 4. Train, then check

The trainer runs every 10 minutes. To train now and check the result:

```bash
kubectl -n my-namespace get cronjob
kubectl -n my-namespace create job first-train --from=cronjob/<trainer-cronjob>
kubectl -n my-namespace wait --for=condition=complete job/first-train --timeout=15m
kubectl -n my-namespace logs job/first-train -c trainer                   # train: TRAINED ...
kubectl -n my-namespace rollout restart deployment/my-app
kubectl -n my-namespace logs <new-pod> -c mnemo-warmup-fetch               # Archive: ... (APP_CDS)
```

A selected archive shows the integration works. It does **not** show that pods became ready faster: [measure the whole start](docs/operations.md#measure-the-benefit).

## Registry authentication

The trainer needs read and write access to the archive repository, the pods read-only access. Use Docker-config Secrets (`warmup.mnemo.io/pull-secret`, `push-secret`) for registries with Basic or Bearer login. On **Amazon EKS**, pods can use IAM roles for service accounts to get an ECR login with no stored password. Both are in [registry authentication](docs/registry-auth.md) and the [EKS and ECR guide](docs/aws-workload-identity.md); the ECR path has not yet been verified on a live cluster.

## Minimal and distroless images

The injector supports Java images without `/bin/sh`, including distroless ones: every generated step runs a static launcher copied into the pod. It supports launches of the form `java [JVM options] -jar <jar> [arguments]`. The preview reports anything else with the reason. The bundled example is built and run on `gcr.io/distroless/java21-debian12` in the end-to-end test.

## Requirements

- Kubernetes with CronJobs and the Lease API; `Deployment` workloads.
- An application image with Java 21 or newer and a supported `java ... -jar ...` launch.
- An OCI registry reachable from the cluster, with the permissions above.
- The same JAR files in the trainer and in every pod.
- A workload whose startup can safely run once more in a separate trainer pod.

Details and what is not supported: [configuration](docs/configuration.md#requirements).

## Operational considerations

- **Training starts a real instance of your application.** Connections, migrations and scheduled jobs may run. Review the preview.
- **The first pods start uncached.** Training is asynchronous; later pods use the archive.
- **Caches use registry storage, and nothing deletes them.** Set a [retention policy](docs/archive-retention.md) on the archive repository.
- **Cache problems fall back; deployment problems do not.** A missing, damaged or unreachable archive means an ordinary start. A missing Secret, an unpullable image or a wrong command can still keep a pod from starting: [what can still stop a pod](docs/operations.md#what-can-still-stop-a-pod).

## Measure the benefit

Compare **pod creation to Ready** with and without the cache over several restarts, including init container time, and read the JVM's own start-up figure. On the small bundled example the JVM-reported start dropped from 0.48 s to 0.31 s while pod start-to-Ready stayed at 2 to 3 seconds; your numbers will differ. [How to measure](docs/operations.md#measure-the-benefit); `mnemo-warmup bench --jar app.jar` compares start-up modes locally.

## Disable or remove it

Remove the `warmup.mnemo.io/*` annotations (or set `enabled` to `"false"`) and redeploy through the injector. The next render has your original command and no init containers, and Helm removes the generated trainer resources. Archives stay in the registry until your retention policy removes them. [Details](docs/operations.md#disable-or-remove-it).

## Other ways in

The explicit Helm-subchart setup (you wire the helpers into your own templates; needs `/bin/sh` in the image) is in [docs/helm-chart.md](docs/helm-chart.md).

## Documentation

- [Configuration and requirements](docs/configuration.md): annotations, overrides, Spring Boot.
- [Registry authentication](docs/registry-auth.md) and [EKS and ECR](docs/aws-workload-identity.md)
- [Operating it](docs/operations.md) and [archive retention](docs/archive-retention.md)
- [Offline injector](docs/offline-injector.md): preview, Helm and GitOps use, what was verified.
- [Reference](docs/reference.md): every command and option.
- [Example](examples/README.md), [Helm chart setup](docs/helm-chart.md), [design decisions](docs/adr/) and the [project plan](docs/PLAN.md).
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
