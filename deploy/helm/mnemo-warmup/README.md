# mnemo-warmup Helm chart

Trains a startup cache (AppCDS, or Leyden AOT on JDK 25+) for your JVM application **once per fingerprint** and
shares it with every replica. See the [project README](../../../README.md) and
[ADR 0005](../../../docs/adr/0005-trainer-and-chart.md).

The chart renders the **trainer** (ServiceAccount, Role/RoleBinding for Leases, CronJob). It does **not** render your
Deployment: you add the init containers and the exec wrapper to your own pod template with the named templates below.

## Use it from your application chart

```yaml
# Chart.yaml
dependencies:
  - name: mnemo-warmup
    version: 0.1.0
    repository: oci://ghcr.io/baokhang83/charts   # or file://../mnemo-warmup while developing
    alias: warmup
```

```yaml
# templates/deployment.yaml (pod spec)
volumes:
  {{- include "mnemo-warmup.volumes" .Values.warmup | nindent 8 }}
initContainers:
  {{- include "mnemo-warmup.installInitContainer" .Values.warmup | nindent 8 }}
  {{- include "mnemo-warmup.fetchInitContainer" .Values.warmup | nindent 8 }}
containers:
  - name: app
    image: {{ include "mnemo-warmup.appImage" .Values.warmup | quote }}
    command: {{ include "mnemo-warmup.command" .Values.warmup }}   # the exec wrapper
    args: ["java", "-Xmx512m", "-jar", "/app/app.jar"]            # your usual command
    env:
      {{- include "mnemo-warmup.env" .Values.warmup | nindent 12 }}
    volumeMounts:
      {{- include "mnemo-warmup.volumeMounts" .Values.warmup | nindent 12 }}
```

A complete working example is [`../tests/parent`](../tests/parent).

## Values that must agree with your Deployment

`app.image`, `app.jar`, `app.jvmArgs` and `app.env` (anything that changes the JVM, such as `JAVA_TOOL_OPTIONS`) must
match what the Deployment really runs, because the archive is only valid for exactly that launch. A mismatch is safe: the
pod starts without the cache and `run` logs why.

## Requirements

- The application image has Java 21+ and the JAR in its extracted layout (Spring Boot: `java -Djarmode=tools -jar app.jar extract` at image build time).
- An OCI registry for the archives (`store.url`), with separate pull and push credentials as `dockerconfigjson` Secrets (`store.pullSecret`, `store.pushSecret`).
- Image-pull secrets are only seen by the kubelet, not by containers, which is why these are mounted explicitly.

See `values.yaml` for every option.
