{{- define "zentro.name" -}}zentro-backend{{- end -}}
{{- define "zentro.labels" -}}
app.kubernetes.io/name: {{ include "zentro.name" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
