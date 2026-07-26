{{/*
Expand the name of the chart.
*/}}
{{- define "loom.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "loom.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "loom.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "loom.labels" -}}
helm.sh/chart: {{ include "loom.chart" . }}
{{ include "loom.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: metaloom
{{- end }}

{{- define "loom.selectorLabels" -}}
app.kubernetes.io/name: {{ include "loom.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "loom.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "loom.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Image reference, tag defaulting to the chart appVersion.
*/}}
{{- define "loom.image" -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end }}

{{/*
Name of the bundled Postgres resources.
*/}}
{{- define "loom.postgres.fullname" -}}
{{- printf "%s-postgresql" (include "loom.fullname" .) -}}
{{- end }}

{{/*
Effective database host: the bundled Postgres Service when enabled, else the
configured external host.
*/}}
{{- define "loom.database.host" -}}
{{- if .Values.postgresql.enabled -}}
{{- include "loom.postgres.fullname" . -}}
{{- else -}}
{{- .Values.database.host -}}
{{- end -}}
{{- end }}

{{/*
Name of the Secret carrying the DB password Loom reads.
*/}}
{{- define "loom.database.secretName" -}}
{{- if .Values.postgresql.enabled -}}
{{- if .Values.postgresql.auth.existingSecret -}}
{{- .Values.postgresql.auth.existingSecret -}}
{{- else -}}
{{- include "loom.postgres.fullname" . -}}
{{- end -}}
{{- else if .Values.database.existingSecret -}}
{{- .Values.database.existingSecret -}}
{{- else -}}
{{- printf "%s-db" (include "loom.fullname" .) -}}
{{- end -}}
{{- end }}

{{/*
Key within the DB Secret that holds the password Loom reads.
Bundled Postgres uses `password`; an external DB Secret uses `db-password`.
*/}}
{{- define "loom.database.secretKey" -}}
{{- if .Values.postgresql.enabled -}}
password
{{- else -}}
db-password
{{- end -}}
{{- end }}

{{/*
Name of the Secret carrying the initial admin password.
*/}}
{{- define "loom.auth.secretName" -}}
{{- if .Values.auth.existingSecret -}}
{{- .Values.auth.existingSecret -}}
{{- else -}}
{{- printf "%s-auth" (include "loom.fullname" .) -}}
{{- end -}}
{{- end }}
