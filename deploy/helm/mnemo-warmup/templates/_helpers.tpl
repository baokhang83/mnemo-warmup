{{/*
Everything below that injects the cache into a workload takes THIS CHART'S VALUES MAP as its context, so a parent
chart that uses mnemo-warmup as a (possibly aliased) dependency calls, for example,

  initContainers:
    {{- include "mnemo-warmup.installInitContainer" .Values.warmup | nindent 8 }}
    {{- include "mnemo-warmup.fetchInitContainer" .Values.warmup | nindent 8 }}

(With tools.delivery=image-volume the two init container templates render nothing, the tools are an image volume and the
fetch runs in the application container; put {{ include "mnemo-warmup.argsPrefix" ... }} before the application's own command in
`args` as well, see docs/adr/0012. The prefix renders nothing in the default mode, so the same Deployment works with both.)

(Named templates of a subchart are visible to its parent.) Templates that need the release (names, RBAC) are
only used inside this chart.
*/}}

{{- define "mnemo-warmup.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mnemo-warmup.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "mnemo-warmup.labels" -}}
app.kubernetes.io/name: {{ include "mnemo-warmup.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end -}}

{{- define "mnemo-warmup.serviceAccountName" -}}
{{- if .Values.trainer.serviceAccount.create -}}
{{- default (printf "%s-trainer" (include "mnemo-warmup.fullname" .)) .Values.trainer.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.trainer.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* ---- values-map helpers (context: the values map) ---- */}}

{{- define "mnemo-warmup.appImage" -}}
{{- if .app.image.digest -}}
{{- printf "%s@%s" .app.image.repository .app.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .app.image.repository (required "app.image.tag or app.image.digest is required" .app.image.tag) -}}
{{- end -}}
{{- end -}}

{{- define "mnemo-warmup.toolsImage" -}}
{{- printf "%s:%s" .tools.image.repository .tools.image.tag -}}
{{- end -}}

{{/* Arguments every command needs to compute the same fingerprint as the others. */}}
{{- define "mnemo-warmup.launchArgs" -}}
- --jar
- {{ .app.jar | quote }}
- --work-dir
- /tmp/mnemo-warmup
- --store
- {{ required "store.url is required" .store.url | quote }}
- --cache-type
- {{ .cacheType | quote }}
{{- if .app.image.digest }}
- {{ printf "--image-digest=%s" .app.image.digest | quote }}
{{- end }}
{{- range .app.jvmArgs }}
- {{ printf "--jvm-arg=%s" . | quote }}
{{- end }}
{{- if .store.plainHttp }}
- --plain-http
{{- end }}
{{- end -}}

{{/*
Tools delivery (docs/adr/0012). Validated whenever the volumes are rendered, which every use of this chart does.
imageVolume renders "true" or nothing, so it can be used as a condition. toolsRoot is where the tools directory is mounted:
an image volume holds the whole tools image, and the tools sit under /opt/mnemo-warmup in it.
*/}}
{{- define "mnemo-warmup.imageVolume" -}}
{{- if not (has .tools.delivery (list "init-container" "image-volume")) -}}
{{- fail (printf "tools.delivery must be init-container or image-volume, got %q" .tools.delivery) -}}
{{- end -}}
{{- if eq .tools.delivery "image-volume" -}}
{{- if .store.pullSecret -}}
{{- fail "tools.delivery=image-volume cannot be used with store.pullSecret: the fetch runs in the application container, which could then read the Secret. Use tools.delivery=init-container" -}}
{{- end -}}
true
{{- end -}}
{{- end -}}

{{- define "mnemo-warmup.toolsRoot" -}}
{{- if include "mnemo-warmup.imageVolume" . -}}/mnemo-tools/opt/mnemo-warmup{{- else -}}/mnemo-tools{{- end -}}
{{- end -}}

{{/* ---- the pieces a workload needs ---- */}}

{{- define "mnemo-warmup.volumes" -}}
- name: mnemo-tools
  {{- if include "mnemo-warmup.imageVolume" . }}
  image:
    reference: {{ include "mnemo-warmup.toolsImage" . | quote }}
    pullPolicy: {{ .tools.image.pullPolicy }}
  {{- else }}
  emptyDir: {}
  {{- end }}
- name: mnemo-cache
  emptyDir: {}
- name: mnemo-work
  emptyDir: {}
{{- if .store.pullSecret }}
- name: mnemo-registry-pull
  secret:
    secretName: {{ .store.pullSecret | quote }}
    items:
      - key: .dockerconfigjson
        path: config.json
{{- end }}
{{- end -}}

{{/* Copies the tools out of the (JDK-free) tools image into the shared volume. */}}
{{- define "mnemo-warmup.installInitContainer" -}}
{{- if not (include "mnemo-warmup.imageVolume" .) -}}
- name: mnemo-warmup-install
  image: {{ include "mnemo-warmup.toolsImage" . | quote }}
  imagePullPolicy: {{ .tools.image.pullPolicy }}
  command: ["cp", "-R", "/opt/mnemo-warmup/.", "/mnemo-tools/"]
  securityContext:
    {{- toYaml .tools.securityContext | nindent 4 }}
  volumeMounts:
    - name: mnemo-tools
      mountPath: /mnemo-tools
{{- end }}
{{- end -}}

{{/* Runs IN THE APPLICATION IMAGE, so it sees the JDK and JAR files the app will run with. Exits 0 on every problem. */}}
{{- define "mnemo-warmup.fetchInitContainer" -}}
{{- if not (include "mnemo-warmup.imageVolume" .) -}}
- name: mnemo-warmup-fetch
  image: {{ include "mnemo-warmup.appImage" . | quote }}
  imagePullPolicy: {{ .app.image.pullPolicy }}
  command: ["/mnemo-tools/bin/mnemo-warmup"]
  args:
    - fetch
    - --out
    - /mnemo-cache
    {{- include "mnemo-warmup.launchArgs" . | nindent 4 }}
  env:
    {{- with .app.env }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
    {{- if .store.pullSecret }}
    - name: MNEMO_WARMUP_REGISTRY_AUTH
      value: /var/run/mnemo-warmup/registry/config.json
    {{- end }}
  volumeMounts:
    - name: mnemo-tools
      mountPath: /mnemo-tools
      readOnly: true
    - name: mnemo-cache
      mountPath: /mnemo-cache
    - name: mnemo-work
      mountPath: /tmp/mnemo-warmup
    {{- if .store.pullSecret }}
    - name: mnemo-registry-pull
      mountPath: /var/run/mnemo-warmup/registry
      readOnly: true
    {{- end }}
{{- end }}
{{- end -}}

{{/* For the application container: mount the tools and the fetched cache. With an image volume this container also does the fetch,
so it writes the cache and has the scratch volume. */}}
{{- define "mnemo-warmup.volumeMounts" -}}
- name: mnemo-tools
  mountPath: /mnemo-tools
  readOnly: true
{{- if include "mnemo-warmup.imageVolume" . }}
- name: mnemo-cache
  mountPath: /mnemo-cache
- name: mnemo-work
  mountPath: /tmp/mnemo-warmup
{{- else }}
- name: mnemo-cache
  mountPath: /mnemo-cache
  readOnly: true
{{- end }}
{{- end -}}

{{/* For the application container: where run finds the cache, and the digest it must match. */}}
{{- define "mnemo-warmup.env" -}}
- name: MNEMO_WARMUP_CACHE_DIR
  value: /mnemo-cache
{{- if .app.image.digest }}
- name: MNEMO_WARMUP_IMAGE_DIGEST
  value: {{ .app.image.digest | quote }}
{{- end }}
{{- end -}}

{{/* For the application container: the exec wrapper (the launcher, with an image volume). Put the application's real command in
`args` (java -jar ...), after argsPrefix. */}}
{{- define "mnemo-warmup.command" -}}
{{- if include "mnemo-warmup.imageVolume" . -}}
["{{ include "mnemo-warmup.toolsRoot" . }}/bin/mnemo-launcher"]
{{- else -}}
["/mnemo-tools/bin/mnemo-warmup-exec"]
{{- end -}}
{{- end -}}

{{/* For the application container's `args`, before the application's own command: with an image volume, the launcher fetches the
archive and then starts the command that follows. Renders nothing in the default mode. */}}
{{- define "mnemo-warmup.argsPrefix" -}}
{{- if include "mnemo-warmup.imageVolume" . }}
- exec
- --out
- /mnemo-cache
{{- include "mnemo-warmup.launchArgs" . | nindent 0 }}
- --
{{- end }}
{{- end -}}
