{{- define "mnemo-nodecache.name" -}}
{{- printf "%s-nodecache" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mnemo-nodecache.clusterName" -}}
{{- printf "%s/%s" .Release.Namespace .Release.Name | sha256sum | trunc 16 | printf "mnemo-nodecache-%s" -}}
{{- end -}}

{{- define "mnemo-nodecache.labels" -}}
app.kubernetes.io/name: mnemo-nodecache
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}
