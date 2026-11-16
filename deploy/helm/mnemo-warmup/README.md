# mnemo-warmup Helm chart

Trains a startup cache (AppCDS, or Leyden AOT on JDK 25+) for your JVM application **once per fingerprint** and
shares it with every replica. See the [project README](https://github.com/baokhang83/mnemo-warmup/#readme) and
[ADR 0005](https://github.com/baokhang83/mnemo-warmup/blob/main/docs/adr/0005-trainer-and-chart.md).

The chart renders the **trainer** (ServiceAccount, Role/RoleBinding for Leases, CronJob). It does **not** render your
Deployment: you add the init containers and the exec wrapper to your own pod template with the named templates below.

## Get the chart

The chart is published as a Helm repository, and its version is the release version (the chart, the app it deploys and the default
tools image `ghcr.io/baokhang83/mnemo-warmup-tools` all carry the same number):

```bash
helm repo add mnemo-warmup https://baokhang83.github.io/mnemo-warmup/
helm repo update
helm search repo mnemo-warmup
```

## Use it from your application chart

```yaml
# Chart.yaml
dependencies:
  - name: mnemo-warmup
    version: 0.2.5
    repository: https://baokhang83.github.io/mnemo-warmup/
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

A complete working example is [`examples/helm/example-app`](https://github.com/baokhang83/mnemo-warmup/tree/main/examples/helm/example-app).

## Values that must agree with your Deployment

`app.image`, `app.jar`, `app.jvmArgs` and `app.env` (anything that changes the JVM, such as `JAVA_TOOL_OPTIONS`) must
match what the Deployment really runs, because the archive is only valid for exactly that launch. A mismatch is safe: the
pod starts without the cache and `run` logs why.

## Requirements

- The application image has Java 21+ and the JAR in its extracted layout (Spring Boot: `java -Djarmode=tools -jar app.jar extract` at image build time).
- An OCI registry for the archives (`store.url`), with separate pull and push credentials as `dockerconfigjson` Secrets (`store.pullSecret`, `store.pushSecret`).
- Image-pull secrets are only seen by the kubelet, not by containers, which is why these are mounted explicitly.

See `values.yaml` for every option.

## Isolated training and signing

Set `trainer.isolation.enabled: true` to run application training in a separate pod from the coordinator that holds the signing key and pushes the archive. Configure `signing.keySecret`, `signing.publicKeys`, `trainer.isolation.publisherImage`, `trainer.isolation.publisherJava`, and `trainer.isolation.handoffClaim`. Pin the publisher image by digest and set `tools.image.digest` to the trusted tools image digest. `trainer.isolation.allowMutableImages` bypasses these checks for testing only. List every Secret the application needs during training in `trainer.isolation.allowedSecrets`; the training pod may reference no other Secret, and the signing and push Secrets are refused.

The training template is rendered into an immutable ConfigMap named after its content, and the coordinator refuses it unless its SHA-256 matches the digest passed in the CronJob, so write access to ConfigMaps is not enough to change what the coordinator creates. The template must satisfy Pod Security `restricted`; the chart adds `runAsNonRoot: true`, a `RuntimeDefault` seccomp profile, `allowPrivilegeEscalation: false` and dropped capabilities, so the application image must not run as root unless `trainer.securityContext.runAsUser` sets a non-zero user. Training pods are labelled `warmup.mnemo.io/role: training` (set by the coordinator, never by the template) for NetworkPolicies and admission policies, and are owned by the coordinator pod so garbage collection removes them if it dies.

The publisher image must contain the same JDK build and extracted application JAR at the same path and with compatible file metadata as the training image. The configured Java executable and application files must be readable by the publisher's non-root user (UID 65532). The two pods exchange files through an existing POSIX-capable PVC; its group must match `trainer.isolation.fsGroup`. Ensure the volume supports the pods' scheduling: a ReadWriteOnce volume can require both pods to run on the same node. If the target JVM uses `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS`, provide their effective values as `trainer.isolation.targetJavaToolOptions` and `trainer.isolation.targetJdkJavaOptions`; these are target fingerprint inputs, not options used to start the publisher. The values must match the training environment, including options supplied through `envFrom`.

The coordinator and training pods use distinct service accounts. Give the training identity no registry push or signing privileges, including through cloud workload identity and metadata services, and prevent admission webhooks from injecting such credentials into it. The coordinator requires permission to create pods; that permission can allow access to other service accounts or Secrets in its namespace. Use a dedicated namespace and admission policies appropriate to your cluster to restrict pod creation, service accounts, volumes and credentials. Treat the publisher and tools images and the training pod template as trusted. Signing identifies the publisher and protects the published bytes; it does not prove that an archive produced by a malicious application or JDK is safe.

A failed, timed-out or uncertain training run can leave a handoff directory on the PVC. Training code can also force one to be kept, for example by creating a subdirectory the coordinator may not remove; monitor the number of `mnemo-run-*` directories and the coordinator's `retained handoff` log lines. A training pod carries the `warmup.mnemo.io/handoff` annotation naming its directory while the pod exists. Before removing a directory, verify that its training pod has terminated and no other process can write to it; a deleted pod may no longer be available for inspection. The coordinator only removes a handoff after a successful training pod has stopped. The signing key Secret is mounted only in the coordinator; applications that consume signed archives need the configured public keys, not the private key.

The coordinator copies the archive into a private, size-limited `emptyDir` (`trainer.isolation.publisherTmpSizeLimit`), without following links and with time limits on opening and copying, so a named pipe or a slow writer on the PVC fails one run instead of blocking the coordinator. It hashes, checks and signs only that private copy. Keep the handoff PVC dedicated to this workflow.
