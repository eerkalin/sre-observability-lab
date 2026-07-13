# Helm Advanced Patterns Runbook

## Purpose

This runbook explains advanced Helm patterns for production-like Kubernetes application deployments.

It covers:

```text
environment values
helm upgrade --install
--wait
--timeout
--atomic
required values
default values
with blocks
range blocks
podAnnotations
extraEnv
nodeSelector
tolerations
affinity
NetworkPolicy template
ServiceMonitor template
Helm anti-patterns
```

---

## Mental model

Basic Helm chart:

```text
Deployment
Service
Ingress
ConfigMap
values.yaml
```

Production-grade Helm chart:

```text
environment values
safe deploy flags
required critical values
safe defaults
optional production blocks
scheduling controls
observability integration
network policy support
clear templates
predictable release behavior
```

Main rule:

```text
Helm chart should be flexible, but not chaotic.
```

---

## helm upgrade --install

Instead of separate install and upgrade commands:

```bash
helm install ...
helm upgrade ...
```

Use:

```bash
helm upgrade --install application-service-helm helm/application-service \
  --namespace sre-lab \
  --create-namespace \
  -f helm/application-service/values/local.yaml
```

Meaning:

```text
If release does not exist, install it.
If release already exists, upgrade it.
```

This is a common CI/CD deploy pattern.

---

## Safe deploy flags

Use:

```bash
helm upgrade --install application-service-helm helm/application-service \
  --namespace sre-lab \
  --create-namespace \
  -f helm/application-service/values/local.yaml \
  --wait \
  --timeout 2m \
  --atomic
```

Meaning:

```text
--wait
  wait until Kubernetes resources become ready

--timeout 2m
  wait maximum 2 minutes

--atomic
  rollback automatically if upgrade fails
```

Production meaning:

```text
A deploy should not be marked successful only because manifests were submitted.
A deploy should be successful only if workload becomes ready.
```

---

## Environment values

Structure:

```text
helm/application-service/values/
├── local.yaml
├── dev.yaml
├── stage.yaml
└── prod.yaml
```

Purpose:

```text
local
  local kind/lab deployment

dev
  small replica count, debug logging, lower resources

stage
  production-like validation environment

prod
  higher replicas, stricter resources, production settings
```

---

## dev.yaml

File:

```text
helm/application-service/values/dev.yaml
```

Example:

```yaml
replicaCount: 1

image:
  tag: "0.1.0-local"

config:
  appMode: "dev"
  logLevel: "DEBUG"

podAnnotations:
  prometheus.io/scrape: "true"
  prometheus.io/path: "/actuator/prometheus"
  prometheus.io/port: "8080"

extraEnv:
  - name: FEATURE_X_ENABLED
    value: "true"

resources:
  requests:
    cpu: "50m"
    memory: "128Mi"
  limits:
    cpu: "300m"
    memory: "384Mi"

ingress:
  host: dev-app.localhost
  tls:
    secretName: app-localhost-certmanager-tls

networkPolicy:
  enabled: false

serviceMonitor:
  enabled: false
```

---

## stage.yaml

File:

```text
helm/application-service/values/stage.yaml
```

Example:

```yaml
replicaCount: 2

image:
  tag: "0.1.0-local"

config:
  appMode: "stage"
  logLevel: "INFO"

podAnnotations:
  prometheus.io/scrape: "true"
  prometheus.io/path: "/actuator/prometheus"
  prometheus.io/port: "8080"

extraEnv:
  - name: FEATURE_X_ENABLED
    value: "false"

resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"

ingress:
  host: stage-app.localhost
  tls:
    secretName: app-localhost-certmanager-tls

networkPolicy:
  enabled: true

serviceMonitor:
  enabled: false
```

---

## prod.yaml

File:

```text
helm/application-service/values/prod.yaml
```

Example:

```yaml
replicaCount: 3

image:
  tag: "0.1.0-local"

config:
  appMode: "prod"
  logLevel: "WARN"

podAnnotations:
  prometheus.io/scrape: "true"
  prometheus.io/path: "/actuator/prometheus"
  prometheus.io/port: "8080"

extraEnv:
  - name: FEATURE_X_ENABLED
    value: "false"

resources:
  requests:
    cpu: "300m"
    memory: "512Mi"
  limits:
    cpu: "1000m"
    memory: "1024Mi"

ingress:
  host: prod-app.localhost
  tls:
    secretName: app-localhost-certmanager-tls

networkPolicy:
  enabled: true

serviceMonitor:
  enabled: false

affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: application-service
          topologyKey: kubernetes.io/hostname
```

Production note:

```text
In real production, image.tag should be immutable:
git SHA, semver, or build number.
Do not use latest.
```

---

## Render environments

Render dev:

```bash
helm template application-service-dev helm/application-service \
  --namespace sre-lab \
  -f helm/application-service/values/dev.yaml > /tmp/app-dev.yaml
```

Render stage:

```bash
helm template application-service-stage helm/application-service \
  --namespace sre-lab \
  -f helm/application-service/values/stage.yaml > /tmp/app-stage.yaml
```

Render prod:

```bash
helm template application-service-prod helm/application-service \
  --namespace sre-lab \
  -f helm/application-service/values/prod.yaml > /tmp/app-prod.yaml
```

Compare:

```bash
grep -n "replicas:" /tmp/app-dev.yaml /tmp/app-stage.yaml /tmp/app-prod.yaml
grep -n "APP_MODE" -A2 /tmp/app-dev.yaml /tmp/app-stage.yaml /tmp/app-prod.yaml
grep -n "host:" /tmp/app-dev.yaml /tmp/app-stage.yaml /tmp/app-prod.yaml
```

Expected:

```text
dev    replicas=1, appMode=dev, host=dev-app.localhost
stage  replicas=2, appMode=stage, host=stage-app.localhost
prod   replicas=3, appMode=prod, host=prod-app.localhost
```

---

## required values

Use `required` for critical values.

Deployment image example:

```yaml
image: '{{ required "image.repository is required" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}'
```

Test:

```bash
helm template test helm/application-service \
  --set image.repository="" \
  --set image.tag="" \
  --namespace sre-lab
```

Expected:

```text
image.repository is required
```

Purpose:

```text
Fail early during render instead of creating broken Kubernetes manifests.
```

Rule:

```text
Use required for critical values.
```

---

## default values

Use `default` for safe fallback.

ConfigMap example:

```yaml
LOG_LEVEL: {{ default "INFO" .Values.config.logLevel | quote }}
```

Test:

```bash
helm template test helm/application-service \
  --set config.logLevel="" \
  --namespace sre-lab | grep -A4 "kind: ConfigMap"
```

Expected:

```text
LOG_LEVEL: "INFO"
```

Rule:

```text
Use default for safe fallback values.
```

---

## podAnnotations with with block

values.yaml:

```yaml
podAnnotations: {}
```

Deployment:

```yaml
annotations:
  checksum/config: {{ .Values.config | toYaml | sha256sum }}
  {{- with .Values.podAnnotations }}
  {{- toYaml . | nindent 8 }}
  {{- end }}
```

Meaning:

```text
with .Values.podAnnotations
  render this block only if podAnnotations is non-empty

toYaml .
  render current object as YAML

nindent 8
  indent correctly under annotations
```

Test:

```bash
helm template test helm/application-service \
  --set podAnnotations."prometheus\.io/scrape"="true" \
  --set podAnnotations."prometheus\.io/path"="/actuator/prometheus" \
  --set podAnnotations."prometheus\.io/port"="8080" \
  --namespace sre-lab | grep -A10 "annotations:"
```

---

## extraEnv with range block

values.yaml:

```yaml
extraEnv: []
```

Example values:

```yaml
extraEnv:
  - name: FEATURE_X_ENABLED
    value: "true"
  - name: BUSINESS_REGION
    value: "KZ"
```

Deployment:

```yaml
{{- range .Values.extraEnv }}
- name: {{ .name | quote }}
  value: {{ .value | quote }}
{{- end }}
```

Meaning:

```text
range
  iterate through a list

.name
  current item name

.value
  current item value
```

Test:

```bash
helm template test helm/application-service \
  --set extraEnv[0].name=FEATURE_X_ENABLED \
  --set extraEnv[0].value=true \
  --set extraEnv[1].name=BUSINESS_REGION \
  --set extraEnv[1].value=KZ \
  --namespace sre-lab | grep -A20 "env:"
```

Warning:

```text
extraEnv is useful, but do not turn it into an uncontrolled dumping ground.
Important standardized settings should have explicit values.
```

---

## nodeSelector, tolerations, affinity

values.yaml:

```yaml
nodeSelector: {}

tolerations: []

affinity: {}
```

Deployment under `spec.template.spec`, same level as `containers`:

```yaml
{{- with .Values.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 8 }}
{{- end }}

{{- with .Values.tolerations }}
tolerations:
  {{- toYaml . | nindent 8 }}
{{- end }}

{{- with .Values.affinity }}
affinity:
  {{- toYaml . | nindent 8 }}
{{- end }}
```

Purpose:

```text
nodeSelector
  simple scheduling by node labels

tolerations
  allow Pods to run on tainted nodes

affinity
  advanced scheduling rules
```

Test:

```bash
helm template test helm/application-service \
  --set nodeSelector.workload-type=application \
  --namespace sre-lab | grep -A5 "nodeSelector:"
```

---

## NetworkPolicy template

values.yaml:

```yaml
networkPolicy:
  enabled: false
  ingress:
    enabled: true
  egress:
    enabled: false
```

Template:

```text
helm/application-service/templates/networkpolicy.yaml
```

Content:

```yaml
{{- if .Values.networkPolicy.enabled }}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "application-service.fullname" . }}
  labels:
    {{- include "application-service.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "application-service.selectorLabels" . | nindent 6 }}
  policyTypes:
    {{- if .Values.networkPolicy.ingress.enabled }}
    - Ingress
    {{- end }}
    {{- if .Values.networkPolicy.egress.enabled }}
    - Egress
    {{- end }}
  {{- if .Values.networkPolicy.ingress.enabled }}
  ingress:
    - from:
        - namespaceSelector: {}
      ports:
        - protocol: TCP
          port: {{ .Values.service.port }}
  {{- end }}
  {{- if .Values.networkPolicy.egress.enabled }}
  egress:
    - to:
        - namespaceSelector: {}
  {{- end }}
{{- end }}
```

Check disabled:

```bash
helm template test helm/application-service \
  --namespace sre-lab | grep -n "NetworkPolicy" || true
```

Check enabled:

```bash
helm template test helm/application-service \
  --set networkPolicy.enabled=true \
  --namespace sre-lab | grep -n "NetworkPolicy"
```

Warning:

```text
NetworkPolicy design must be careful.
Too broad rules give weak protection.
Too strict rules can break ingress, DNS, monitoring, and dependencies.
```

---

## ServiceMonitor template

ServiceMonitor is used by Prometheus Operator.

values.yaml:

```yaml
serviceMonitor:
  enabled: false
  interval: 30s
  path: /actuator/prometheus
  labels: {}
```

Template:

```text
helm/application-service/templates/servicemonitor.yaml
```

Content:

```yaml
{{- if .Values.serviceMonitor.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "application-service.fullname" . }}
  labels:
    {{- include "application-service.labels" . | nindent 4 }}
    {{- with .Values.serviceMonitor.labels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  selector:
    matchLabels:
      {{- include "application-service.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: http
      path: {{ .Values.serviceMonitor.path }}
      interval: {{ .Values.serviceMonitor.interval }}
{{- end }}
```

Check disabled:

```bash
helm template test helm/application-service \
  --namespace sre-lab | grep -n "ServiceMonitor" || true
```

Check enabled:

```bash
helm template test helm/application-service \
  --set serviceMonitor.enabled=true \
  --set serviceMonitor.labels.release=prometheus \
  --namespace sre-lab | grep -n "ServiceMonitor"
```

Important:

```text
If the cluster does not have the ServiceMonitor CRD, installing with serviceMonitor.enabled=true fails.
Therefore, default should be false.
```

---

## Validate all values

Run:

```bash
helm lint helm/application-service
```

Render:

```bash
helm template application-service-dev helm/application-service \
  --namespace sre-lab \
  -f helm/application-service/values/dev.yaml > /tmp/app-dev.yaml

helm template application-service-stage helm/application-service \
  --namespace sre-lab \
  -f helm/application-service/values/stage.yaml > /tmp/app-stage.yaml

helm template application-service-prod helm/application-service \
  --namespace sre-lab \
  -f helm/application-service/values/prod.yaml > /tmp/app-prod.yaml
```

Check resources:

```bash
grep -n "kind:" /tmp/app-dev.yaml
grep -n "kind:" /tmp/app-stage.yaml
grep -n "kind:" /tmp/app-prod.yaml
```

Check NetworkPolicy:

```bash
grep -n "NetworkPolicy" /tmp/app-dev.yaml || true
grep -n "NetworkPolicy" /tmp/app-stage.yaml || true
grep -n "NetworkPolicy" /tmp/app-prod.yaml || true
```

Expected:

```text
dev: no NetworkPolicy
stage: NetworkPolicy exists
prod: NetworkPolicy exists
```

Check prod affinity:

```bash
grep -n "affinity:" -A20 /tmp/app-prod.yaml
```

---

## Deploy with advanced flags

Use local values:

```bash
helm upgrade --install application-service-helm helm/application-service \
  --namespace sre-lab \
  --create-namespace \
  -f helm/application-service/values/local.yaml \
  --wait \
  --timeout 2m \
  --atomic
```

Check:

```bash
helm history application-service-helm -n sre-lab
kubectl rollout status deployment/application-service-helm -n sre-lab
kubectl get pods -n sre-lab -l app.kubernetes.io/instance=application-service-helm
```

---

## Helm anti-patterns

### Anti-pattern 1: too much logic in templates

Bad:

```text
Templates are impossible to read without long analysis.
Chart becomes a mini-program.
```

Better:

```text
simple templates
clear values
limited conditional logic
platform decisions outside templates where possible
```

---

### Anti-pattern 2: everything through --set

Bad:

```bash
helm upgrade app chart \
  --set a=b \
  --set c=d \
  --set x.y.z=123 \
  --set ingress.host=... \
  --set resources.requests.cpu=...
```

Better:

```bash
helm upgrade app chart -f values/prod.yaml
```

---

### Anti-pattern 3: mutable image tags

Bad:

```yaml
image:
  tag: latest
```

Problems:

```text
rollback is unpredictable
unclear running version
cache can produce unexpected behavior
incident analysis becomes harder
```

Better:

```yaml
image:
  tag: "2026.07.12-a1b2c3d"
```

---

### Anti-pattern 4: secrets in values.yaml plain text

Bad:

```yaml
secret:
  dbPassword: "real-password"
```

Better:

```text
External Secrets
Sealed Secrets
SOPS
Vault/cloud secret manager
CI-injected secret references
```

---

### Anti-pattern 5: enabling CRD resources by default

Bad:

```yaml
serviceMonitor:
  enabled: true
```

If CRD is absent:

```text
install fails
```

Better:

```yaml
serviceMonitor:
  enabled: false
```

Enable only in clusters where Prometheus Operator and CRDs exist.

---

## Lab structure after Day 39

```text
helm/application-service/
├── Chart.yaml
├── values.yaml
├── values/
│   ├── local.yaml
│   ├── dev.yaml
│   ├── stage.yaml
│   └── prod.yaml
└── templates/
    ├── _helpers.tpl
    ├── configmap.yaml
    ├── deployment.yaml
    ├── ingress.yaml
    ├── networkpolicy.yaml
    ├── service.yaml
    └── servicemonitor.yaml
```

---

## Small production pattern

```text
CI:
  helm lint
  helm template
  optional kubeconform/kubeval
  helm upgrade --install --wait --timeout --atomic
  manual approval for prod
```

Values:

```text
values/dev.yaml
values/stage.yaml
values/prod.yaml
```

Deploy:

```bash
helm upgrade --install application-service helm/application-service \
  --namespace application-service-prod \
  -f helm/application-service/values/prod.yaml \
  --wait \
  --timeout 5m \
  --atomic
```

---

## Enterprise pattern

Common platform chart includes optional blocks:

```text
ingress
hpa
pdb
networkPolicy
serviceMonitor
externalSecret
podAnnotations
extraEnv
nodeSelector
tolerations
affinity
resources
probes
securityContext
podSecurityContext
```

Platform rules:

```text
secure defaults
observable by default
NetworkPolicy support
ServiceMonitor support
immutable image tags
no plaintext secrets
CI validation
GitOps compatibility
escape hatches
```

Main risk:

```text
Universal chart can become too complex.
Keep values clear and templates readable.
```

---

## Key lessons

```text
1. helm upgrade --install is the standard idempotent deployment command.
2. --wait ensures Helm waits for readiness.
3. --timeout limits deploy waiting time.
4. --atomic rolls back failed upgrades.
5. Use values files for environments.
6. Use required for critical values.
7. Use default for safe fallback values.
8. Use with for optional objects.
9. Use range for lists.
10. podAnnotations, extraEnv, affinity, tolerations, and nodeSelector make charts more reusable.
11. NetworkPolicy should usually be optional and carefully designed.
12. ServiceMonitor should be optional because it depends on CRDs.
13. Do not put secrets in plaintext values files.
14. Do not use mutable image tags in production.
15. Keep Helm templates readable.
```