{{/*
Expand the name of the chart.
*/}}
{{- define "cortex.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "cortex.fullname" -}}
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

{{/*
Chart name and version as used by the chart label.
*/}}
{{- define "cortex.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "cortex.labels" -}}
helm.sh/chart: {{ include "cortex.chart" . }}
{{ include "cortex.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: metaloom
{{- end }}

{{/*
Selector labels
*/}}
{{- define "cortex.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cortex.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Service account name
*/}}
{{- define "cortex.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "cortex.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Name of the Secret holding the Loom token.
*/}}
{{- define "cortex.tokenSecretName" -}}
{{- if .Values.loom.existingSecret }}
{{- .Values.loom.existingSecret }}
{{- else }}
{{- printf "%s-token" (include "cortex.fullname" .) }}
{{- end }}
{{- end }}

{{/*
Image reference (repository:tag), tag defaulting to the chart appVersion.
*/}}
{{- define "cortex.image" -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end }}
