# Deploy with the Helm chart

This is the explicit setup: you add the mnemo-warmup chart as a dependency of your application's chart and wire its helpers into your Deployment. Use it when you want to see and own the generated YAML, or for images that have `/bin/sh` and a Helm chart you already control. For the quickest start, with no template changes and with shell-free (distroless) images, use the annotation path described in the [README](../README.md) and [configuration](configuration.md) instead.

The chart's version is the release version: chart `0.2.5` deploys the `0.2.5` tools image ([release notes](https://github.com/baokhang83/mnemo-warmup/releases/tag/v0.2.5)). This path is unchanged since 0.2.0.

**Requirements specific to the chart:** the application image needs `/bin/sh` (the chart's launcher and wrapper are shell scripts) and Java 21 or newer. Everything else is in [configuration](configuration.md#requirements).

## Set up the chart

The chart is published as a Helm repository.

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
helm repo add mnemo-warmup https://baokhang83.github.io/mnemo-warmup/
helm search repo mnemo-warmup              # mnemo-warmup/mnemo-warmup  0.2.5
```

```yaml
# your-chart/Chart.yaml
dependencies:
  - name: mnemo-warmup
    version: 0.2.5
    repository: https://baokhang83.github.io/mnemo-warmup/
    alias: warmup
```

### 3. Create the namespace and the Secrets

The Secrets must exist in the namespace you deploy to, before you deploy (a missing Secret keeps the pod from starting). Two of them let the tool
talk to the **archive** registry (skip them only for an anonymous registry): the pull Secret needs read access to the archive repository, the push
Secret read and write. They are mounted into containers and do **not** authenticate image pulls, which the kubelet does with `imagePullSecrets`.
If your application image is in a private registry, create an image-pull Secret as well (it may hold the same credentials):

```bash
export NS=my-namespace
kubectl create namespace $NS
kubectl -n $NS create secret docker-registry warmup-pull --docker-server=registry.example.com --docker-username=... --docker-password=...
kubectl -n $NS create secret docker-registry warmup-push --docker-server=registry.example.com --docker-username=... --docker-password=...
kubectl -n $NS create secret docker-registry regcred --docker-server=registry.example.com --docker-username=... --docker-password=...   # image pulls
```

### 4. Set the values

```yaml
# your-chart/values.yaml
warmup:
  app:
    image: { repository: registry.example.com/team/my-app, tag: "1.4.2" }  # the SAME image your Deployment runs
    imagePullSecrets: [{ name: regcred }]   # for that image if it is private; the trainer uses it, and so does the Deployment below
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
[`examples/helm/example-app`](../examples/helm/example-app/templates/deployment.yaml).

```yaml
spec:
  template:
    spec:
      {{- with .Values.warmup.app.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
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

## Disable or remove it

Suspending the trainer (`warmup.trainer.suspend: true`) only stops **new training**: pods keep fetching and using the archives that already exist. To stop
using the cache:

1. Take the `include` lines out of your Deployment and restore its original `command` and `args`; redeploy. New pods start without the init containers, the
   wrapper or the cache.
2. Set `warmup.trainer.enabled: false` (or remove the dependency) to delete the trainer CronJob, its ServiceAccount and its Role.
3. Optionally delete the archives from the registry (tags `fp-<hex>` in the archive repository) and any Leases left behind
   (`kubectl -n $NS get lease | grep mnemo-warmup`; they expire on their own).

