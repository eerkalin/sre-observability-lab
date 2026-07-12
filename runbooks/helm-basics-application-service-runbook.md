# Helm Basics for Kubernetes Applications Runbook

## Purpose

This runbook explains Helm basics for packaging and deploying Kubernetes applications.

It covers:

```text
Helm chart
Chart.yaml
values.yaml
templates
release
install
upgrade
rollback
helm lint
helm template
values override
checksum rollout
lab vs production Helm structure
```

---

## What Helm is

Helm is a package manager for Kubernetes.

Mental model:

```text
Docker image
  → package for application runtime

Helm chart
  → package for Kubernetes deployment manifests
```

A Helm chart can include:

```text
Deployment
Service
Ingress
ConfigMap
Secret
ServiceAccount
HPA
NetworkPolicy
ServiceMonitor
PodDisruptionBudget
```

---

## Why Helm instead of raw YAML

Raw YAML is good for learning:

```text
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
kubectl apply -f ingress.yaml
```

But in production raw YAML becomes hard to manage:

```text
copy-paste between environments
duplicated manifests
different image tags per environment
different resources per environment
different ingress/TLS/config per environment
harder release history
harder rollback
```

Helm solves this by using:

```text
one reusable chart
  ↓
different values files
  ↓
dev / stage / prod releases
```

Important:

```text
Helm does not replace Kubernetes knowledge.
Helm renders Kubernetes YAML.
```

---

## Helm concepts

### Chart

A chart is a Kubernetes application package.

```text
chart = templates + default values + metadata
```

### Release

A release is an installed instance of a chart.

Example:

```text
chart: application-service

releases:
  application-service-dev
  application-service-stage
  application-service-prod
```

### Values

Values are parameters used by templates.

Example:

```yaml
replicaCount: 2

image:
  repository: application-service
  tag: "0.1.0-local"

service:
  port: 8080
```

### Templates

Templates are Kubernetes manifests with Helm templating syntax.

Example:

```yaml
replicas: {{ .Values.replicaCount }}
```

---

## Install Helm

Check:

```bash
helm version
```

Install on Mac:

```bash
brew install helm
```

Verify:

```bash
helm version
```

---

## Create chart

From repo root:

```bash
cd ~/Documents/GitHub/sre-observability-lab/
mkdir -p helm
helm create helm/application-service
```

Initial structure:

```text
helm/application-service/
├── Chart.yaml
├── values.yaml
├── charts/
└── templates/
    ├── NOTES.txt
    ├── _helpers.tpl
    ├── deployment.yaml
    ├── hpa.yaml
    ├── ingress.yaml
    ├── service.yaml
    ├── serviceaccount.yaml
    └── tests/
```

---

## Simplify chart for lab

Remove unused files:

```bash
rm -f helm/application-service/templates/hpa.yaml
rm -f helm/application-service/templates/serviceaccount.yaml
rm -f helm/application-service/templates/NOTES.txt
rm -rf helm/application-service/templates/tests
```

Target structure:

```text
helm/application-service/
├── Chart.yaml
├── values.yaml
└── templates/
    ├── _helpers.tpl
    ├── configmap.yaml
    ├── deployment.yaml
    ├── ingress.yaml
    └── service.yaml
```

---

## Chart.yaml

File:

```text
helm/application-service/Chart.yaml
```

Content:

```yaml
apiVersion: v2
name: application-service
description: Helm chart for application-service lab
type: application
version: 0.1.0
appVersion: "0.1.0-local"
```

Important:

```text
chart version != application version
```

Example:

```text
chart version: 1.4.2
app version: 2026.07.09-a1b2c3d
```

---

## values.yaml

File:

```text
helm/application-service/values.yaml
```

Content:

```yaml
replicaCount: 2

image:
  repository: application-service
  tag: "0.1.0-local"
  pullPolicy: IfNotPresent

nameOverride: ""
fullnameOverride: ""

service:
  type: ClusterIP
  port: 8080
  targetPort: http

container:
  port: 8080

resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"

config:
  appMode: "helm-local"
  logLevel: "INFO"

ingress:
  enabled: true
  className: nginx
  host: app.localhost
  path: /
  pathType: Prefix
  tls:
    enabled: true
    secretName: app-localhost-certmanager-tls
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
```

---

## _helpers.tpl

File:

```text
helm/application-service/templates/_helpers.tpl
```

Content:

```gotemplate
{{/*
Return chart name.
*/}}
{{- define "application-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Return full release name.
*/}}
{{- define "application-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Common labels.
*/}}
{{- define "application-service.labels" -}}
app.kubernetes.io/name: {{ include "application-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "application-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "application-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
```

Key functions:

```text
define
  create reusable named template

include
  insert named template

.Release.Name
  Helm release name

.Chart.Name
  chart name from Chart.yaml

.Values.*
  values from values.yaml

trunc 63
  Kubernetes name length safety

trimSuffix "-"
  remove trailing dash
```

---

## ConfigMap template

File:

```text
helm/application-service/templates/configmap.yaml
```

Content:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "application-service.fullname" . }}
  labels:
    {{- include "application-service.labels" . | nindent 4 }}
data:
  APP_MODE: {{ .Values.config.appMode | quote }}
  LOG_LEVEL: {{ .Values.config.logLevel | quote }}
```

---

## Deployment template

File:

```text
helm/application-service/templates/deployment.yaml
```

Content:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "application-service.fullname" . }}
  labels:
    {{- include "application-service.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      {{- include "application-service.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "application-service.selectorLabels" . | nindent 8 }}
      annotations:
        checksum/config: {{ .Values.config | toYaml | sha256sum }}
    spec:
      containers:
        - name: application-service
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.container.port }}
          env:
            - name: APP_MODE
              valueFrom:
                configMapKeyRef:
                  name: {{ include "application-service.fullname" . }}
                  key: APP_MODE
            - name: LOG_LEVEL
              valueFrom:
                configMapKeyRef:
                  name: {{ include "application-service.fullname" . }}
                  key: LOG_LEVEL
          startupProbe:
            httpGet:
              path: /actuator/health
              port: http
            failureThreshold: 30
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 20
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 3
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
```

Important functions:

```text
toYaml
  convert object to YAML

nindent
  newline + indentation

sha256sum
  calculate hash

include
  include named template
```

Checksum rollout pattern:

```yaml
checksum/config: {{ .Values.config | toYaml | sha256sum }}
```

Meaning:

```text
config values changed
  ↓
checksum changed
  ↓
Pod template changed
  ↓
Deployment rollout started
```

---

## Service template

File:

```text
helm/application-service/templates/service.yaml
```

Content:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "application-service.fullname" . }}
  labels:
    {{- include "application-service.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector:
    {{- include "application-service.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.targetPort }}
```

Important:

```text
Service selector must match Pod labels.
Using the same helper reduces selector mismatch risk.
```

---

## Ingress template

File:

```text
helm/application-service/templates/ingress.yaml
```

Content:

```yaml
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "application-service.fullname" . }}
  labels:
    {{- include "application-service.labels" . | nindent 4 }}
  annotations:
    {{- toYaml .Values.ingress.annotations | nindent 4 }}
spec:
  ingressClassName: {{ .Values.ingress.className }}
  {{- if .Values.ingress.tls.enabled }}
  tls:
    - hosts:
        - {{ .Values.ingress.host }}
      secretName: {{ .Values.ingress.tls.secretName }}
  {{- end }}
  rules:
    - host: {{ .Values.ingress.host }}
      http:
        paths:
          - path: {{ .Values.ingress.path }}
            pathType: {{ .Values.ingress.pathType }}
            backend:
              service:
                name: {{ include "application-service.fullname" . }}
                port:
                  number: {{ .Values.service.port }}
{{- end }}
```

Meaning:

```text
if .Values.ingress.enabled
  render Ingress only when enabled=true

if .Values.ingress.tls.enabled
  render TLS block only when tls.enabled=true

toYaml .Values.ingress.annotations
  render annotations from values.yaml
```

---

## Helm lint

Run:

```bash
cd ~/Documents/GitHub/sre-observability-lab/
helm lint helm/application-service
```

Expected:

```text
1 chart(s) linted, 0 chart(s) failed
```

---

## Helm template

Render manifests without deploying:

```bash
helm template application-service helm/application-service \
  --namespace sre-lab
```

Save to file:

```bash
helm template application-service helm/application-service \
  --namespace sre-lab > /tmp/application-service-rendered.yaml
```

Check:

```bash
grep -n "kind:" /tmp/application-service-rendered.yaml
grep -n "checksum/config" /tmp/application-service-rendered.yaml
grep -n "image:" /tmp/application-service-rendered.yaml
```

Expected resources:

```text
ConfigMap
Service
Deployment
Ingress
```

---

## Dry-run install

```bash
helm install application-service helm/application-service \
  --namespace sre-lab \
  --dry-run \
  --debug
```

This validates:

```text
computed values
rendered manifests
template errors
```

---

## Install release

Check namespace:

```bash
kubectl get ns sre-lab
```

Create if missing:

```bash
kubectl create namespace sre-lab
```

Install:

```bash
helm install application-service-helm helm/application-service \
  --namespace sre-lab \
  --set ingress.host=helm-app.localhost \
  --set ingress.tls.secretName=app-localhost-certmanager-tls
```

Using `application-service-helm` avoids conflicts with raw YAML resources named `application-service`.

Check:

```bash
helm list -n sre-lab
kubectl get deploy,svc,ingress,cm -n sre-lab
kubectl get pods -n sre-lab
```

---

## Existing raw YAML resource conflict

If Helm install fails with:

```text
rendered manifests contain a resource that already exists
invalid ownership metadata
```

Meaning:

```text
Resource already exists but is not owned by this Helm release.
```

Options:

```text
A. Delete raw YAML resources and install Helm release with same names.
B. Use a different release name.
```

Recommended lab option:

```text
Use a different release name: application-service-helm
```

---

## Verify external access

```bash
curl -k -i \
  --resolve helm-app.localhost:8443:127.0.0.1 \
  https://helm-app.localhost:8443/actuator/health
```

Expected:

```text
HTTP/2 200 or HTTP/1.1 200
application health UP
```

---

## Helm upgrade

Change replica count:

```bash
helm upgrade application-service-helm helm/application-service \
  --namespace sre-lab \
  --set replicaCount=3 \
  --set ingress.host=helm-app.localhost \
  --set ingress.tls.secretName=app-localhost-certmanager-tls
```

Check Pods:

```bash
kubectl get pods -n sre-lab -l app.kubernetes.io/instance=application-service-helm
```

Expected:

```text
3 Pods
```

Check history:

```bash
helm history application-service-helm -n sre-lab
```

---

## Upgrade config and checksum rollout

Change log level:

```bash
helm upgrade application-service-helm helm/application-service \
  --namespace sre-lab \
  --set replicaCount=3 \
  --set config.logLevel=DEBUG \
  --set ingress.host=helm-app.localhost \
  --set ingress.tls.secretName=app-localhost-certmanager-tls
```

Check checksum:

```bash
kubectl get deployment application-service-helm -n sre-lab \
  -o jsonpath='{.spec.template.metadata.annotations.checksum/config}{"\n"}'
```

Check rollout:

```bash
kubectl rollout status deployment/application-service-helm -n sre-lab
kubectl get pods -n sre-lab -l app.kubernetes.io/instance=application-service-helm
```

Meaning:

```text
config.logLevel changed
  ↓
checksum/config changed
  ↓
Deployment created new ReplicaSet
  ↓
Pods restarted through rollout
```

---

## Helm rollback

Check history:

```bash
helm history application-service-helm -n sre-lab
```

Rollback to revision 1:

```bash
helm rollback application-service-helm 1 -n sre-lab
```

Check:

```bash
helm history application-service-helm -n sre-lab
kubectl get deployment application-service-helm -n sre-lab -o yaml | grep -A5 "checksum/config"
```

Important:

```text
helm rollback rolls back Helm release manifests and values.
If image tag is mutable, rollback may not restore the expected application version.
```

Production rule:

```text
Do not use latest.
Use immutable image tags such as git SHA, build number, or semver.
```

---

## Values override file

Create:

```bash
mkdir -p helm/application-service/values
```

File:

```text
helm/application-service/values/local.yaml
```

Content:

```yaml
replicaCount: 2

config:
  appMode: "helm-local-values-file"
  logLevel: "INFO"

ingress:
  host: helm-app.localhost
  tls:
    secretName: app-localhost-certmanager-tls
```

Upgrade through file:

```bash
helm upgrade application-service-helm helm/application-service \
  --namespace sre-lab \
  -f helm/application-service/values/local.yaml
```

Check user-supplied values:

```bash
helm get values application-service-helm -n sre-lab
```

Check all values:

```bash
helm get values application-service-helm -n sre-lab --all
```

Production rule:

```text
Use values files for environments.
Avoid long --set commands for normal deployments.
```

---

## Useful Helm commands

Lint chart:

```bash
helm lint helm/application-service
```

Render manifests:

```bash
helm template application-service helm/application-service --namespace sre-lab
```

Install release:

```bash
helm install application-service-helm helm/application-service -n sre-lab
```

Upgrade release:

```bash
helm upgrade application-service-helm helm/application-service -n sre-lab -f helm/application-service/values/local.yaml
```

Install or upgrade:

```bash
helm upgrade --install application-service-helm helm/application-service -n sre-lab -f helm/application-service/values/local.yaml
```

Release history:

```bash
helm history application-service-helm -n sre-lab
```

Rollback:

```bash
helm rollback application-service-helm 1 -n sre-lab
```

Get values:

```bash
helm get values application-service-helm -n sre-lab
helm get values application-service-helm -n sre-lab --all
```

Get rendered release manifest:

```bash
helm get manifest application-service-helm -n sre-lab
```

Uninstall release:

```bash
helm uninstall application-service-helm -n sre-lab
```

---

## Lab structure

```text
helm/application-service/
├── Chart.yaml
├── values.yaml
├── values/
│   └── local.yaml
└── templates/
    ├── _helpers.tpl
    ├── configmap.yaml
    ├── deployment.yaml
    ├── ingress.yaml
    └── service.yaml
```

Lab pattern:

```text
local chart in repo
kind cluster
local Docker image
values/local.yaml
manual helm install/upgrade/rollback
```

---

## Small production structure

```text
helm/application-service/
├── Chart.yaml
├── values.yaml
├── values/
│   ├── dev.yaml
│   ├── stage.yaml
│   └── prod.yaml
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    └── configmap.yaml
```

Small production pattern:

```text
app repo contains Helm chart
CI runs helm lint/template
CI deploys with helm upgrade --install
dev/stage/prod values files
manual approval for prod
immutable image tags
```

---

## Enterprise pattern

### Option 1: Chart per service

```text
service-a/helm/
service-b/helm/
service-c/helm/
```

Pros:

```text
flexible
each team owns its chart
```

Cons:

```text
harder to standardize
duplicated templates
inconsistent security/observability defaults
```

### Option 2: Standard platform chart

```text
platform-charts/
└── standard-spring-service/
```

Each service provides values:

```yaml
image:
  repository: registry.company.com/payment-service
  tag: "a1b2c3d"

replicaCount: 4

resources:
  requests:
    cpu: 500m
    memory: 512Mi

ingress:
  host: payment.company.com
```

Pros:

```text
standardized
less copy-paste
easier security baseline
easier observability baseline
```

Cons:

```text
harder chart design
teams depend on platform team
chart can become too complex
```

Mature platform pattern:

```text
standard chart + escape hatches
```

Optional blocks:

```text
ingress
hpa
pdb
networkPolicy
serviceMonitor
externalSecret
affinity
tolerations
nodeSelector
podAnnotations
```

---

## Troubleshooting

### Template error

Check:

```bash
helm lint helm/application-service
helm template application-service helm/application-service --namespace sre-lab --debug
```

### Resource already exists

Meaning:

```text
Resource exists but is not owned by Helm release.
```

Fix:

```text
Use different release name
or delete/adopt existing resources carefully
```

### Pods not ready

Check:

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab
```

### Service has no endpoints

Check:

```bash
kubectl get svc,endpoints,endpointslice -n sre-lab
kubectl get pods -n sre-lab --show-labels
```

Usually:

```text
Service selector does not match Pod labels
or Pods are not Ready
```

### Ingress returns 404

Check:

```bash
kubectl get ingress -n sre-lab
kubectl describe ingress application-service-helm -n sre-lab
```

Usually:

```text
wrong host
wrong path
wrong ingressClassName
```

### Ingress returns 503

Check:

```bash
kubectl get svc,endpoints -n sre-lab
kubectl get pods -n sre-lab
```

Usually:

```text
Ingress matched route but backend has no healthy endpoints
```

---

## Key lessons

```text
1. Helm packages Kubernetes manifests into reusable charts.
2. Chart.yaml describes the chart.
3. values.yaml contains default configuration.
4. templates/ contains Kubernetes YAML templates.
5. Release is an installed chart instance.
6. helm lint checks chart quality.
7. helm template renders manifests without deploying.
8. helm install creates a release.
9. helm upgrade updates a release.
10. helm rollback rolls back a release revision.
11. values files are better than long --set commands.
12. checksum annotations connect config changes to Deployment rollouts.
13. Helm does not replace Kubernetes knowledge.
14. In production, use immutable image tags.
15. Enterprise platforms often use standard charts with controlled escape hatches.
```