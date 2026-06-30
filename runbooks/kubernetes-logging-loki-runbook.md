# Kubernetes Logging and Loki Runbook

## Purpose

This runbook describes how to operate, test, and troubleshoot Kubernetes application logs and centralized logging in the local SRE observability lab.

The logging layer is used to support incident investigation after metrics, alerts, and dashboards show a problem.

The lab logging stack includes:

```text
application-service
  ↓ writes logs to stdout/stderr
Kubernetes container logs
  ↓ stored under /var/log/pods
Promtail
  ↓ collects logs from Kubernetes node filesystem
Loki
  ↓ stores and indexes log streams
Grafana
  ↓ queries logs through Loki datasource
```

Prometheus is used for metrics and alerting conditions.

Alertmanager is used for alert routing, grouping, silencing, and notification management.

Grafana is used as the main incident workspace for dashboards, metrics, alerts, and logs.

Loki is used as the centralized logging backend.

Promtail is used as the Kubernetes log collector.

---

## Observability Workflow

The expected SRE incident workflow is:

```text
Alertmanager
  ↓
Grafana dashboard
  ↓
Prometheus metrics
  ↓
Loki logs
  ↓
Mini-RCA
```

Metrics answer:

```text
What happened?
How often did it happen?
How severe is it?
When did it start?
```

Logs answer:

```text
What did the application report?
Which endpoint was involved?
Which operation failed or slowed down?
What status code was returned?
What duration was observed?
What requestId can be used for correlation?
What evidence supports the diagnosis?
```

Traces are not covered in this runbook yet, but they would answer:

```text
Where exactly did a single request spend time across services and dependencies?
```

---

## Environment

Application namespace:

```text
sre-lab
```

Monitoring namespace:

```text
monitoring
```

Main application:

```text
application-service
```

Grafana UI:

```text
http://localhost:3000
```

Prometheus UI:

```text
http://localhost:9090
```

Loki local API:

```text
http://localhost:3100
```

Promtail local UI:

```text
http://localhost:9080
```

Grafana credentials for local lab:

```text
admin / admin
```

---

## Kubernetes Components

Expected application component:

```text
application-service
```

Expected monitoring components:

```text
Prometheus
Alertmanager
Grafana
Loki
Promtail
```

Promtail is deployed as a DaemonSet.

Loki is deployed as a Deployment.

---

## Expected Files

Expected Loki manifests:

```text
k8s/observability/loki/loki-configmap.yaml
k8s/observability/loki/loki-deployment.yaml
k8s/observability/loki/loki-service.yaml
k8s/observability/loki/promtail-serviceaccount.yaml
k8s/observability/loki/promtail-clusterrole.yaml
k8s/observability/loki/promtail-clusterrolebinding.yaml
k8s/observability/loki/promtail-configmap.yaml
k8s/observability/loki/promtail-daemonset.yaml
```

Expected Grafana datasource config:

```text
k8s/observability/grafana/datasource-configmap.yaml
```

Expected Grafana dashboard JSON:

```text
k8s/observability/grafana/dashboards/application-service-overview.json
```

---

## Quick Health Check

Check application-service:

```bash
kubectl get pods -n sre-lab
kubectl get svc -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Check monitoring components:

```bash
kubectl get pods -n monitoring
kubectl get svc -n monitoring
```

Check Loki:

```bash
kubectl get pods -n monitoring -l app=loki
kubectl get svc -n monitoring
```

Check Promtail:

```bash
kubectl get daemonset promtail -n monitoring
kubectl get pods -n monitoring -l app=promtail
```

Check Grafana:

```bash
kubectl get pods -n monitoring -l app=grafana
kubectl get svc -n monitoring grafana
```

---

## Starting the Lab

If the lab was scaled down, start the main components:

```bash
kubectl scale deployment/application-service --replicas=1 -n sre-lab
kubectl scale deployment/prometheus --replicas=1 -n monitoring
kubectl scale deployment/alertmanager --replicas=1 -n monitoring
kubectl scale deployment/grafana --replicas=1 -n monitoring
kubectl scale deployment/loki --replicas=1 -n monitoring
```

Promtail is a DaemonSet, not a Deployment. Check it separately:

```bash
kubectl get daemonset promtail -n monitoring
kubectl get pods -n monitoring -l app=promtail
```

If Promtail was deleted, recreate it:

```bash
kubectl apply -f k8s/observability/loki/promtail-serviceaccount.yaml
kubectl apply -f k8s/observability/loki/promtail-clusterrole.yaml
kubectl apply -f k8s/observability/loki/promtail-clusterrolebinding.yaml
kubectl apply -f k8s/observability/loki/promtail-configmap.yaml
kubectl apply -f k8s/observability/loki/promtail-daemonset.yaml
```

---

## Basic Kubernetes Logs

Kubernetes applications should write logs to:

```text
stdout
stderr
```

The cloud-native logging model is:

```text
application writes to stdout/stderr
  ↓
container runtime captures logs
  ↓
Kubernetes stores logs on the Node
  ↓
kubectl logs can read recent logs
  ↓
log collector sends logs to centralized backend
```

Show recent application logs:

```bash
kubectl logs -l app=application-service -n sre-lab --tail=100
```

Follow application logs in real time:

```bash
kubectl logs -l app=application-service -n sre-lab -f
```

Show logs from the last 5 minutes:

```bash
kubectl logs -l app=application-service -n sre-lab --since=5m
```

Show logs from the last hour:

```bash
kubectl logs -l app=application-service -n sre-lab --since=1h
```

Get a concrete Pod name:

```bash
kubectl get pods -n sre-lab
```

Show logs for a specific Pod:

```bash
kubectl logs <pod-name> -n sre-lab
```

Show previous container logs after a restart:

```bash
kubectl logs <pod-name> -n sre-lab --previous
```

This is useful for:

```text
CrashLoopBackOff
OOMKilled
startup failure
container restart investigation
```

---

## Kubernetes Logs with Describe and Events

Logs show what the application writes.

`describe` shows Kubernetes state and events around the Pod.

Describe a Pod:

```bash
kubectl describe pod <pod-name> -n sre-lab
```

Show recent namespace events:

```bash
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Use logs, describe, and events together:

```text
kubectl logs:
application error or exception

kubectl describe:
readiness/liveness/container state

kubectl events:
scheduling, image pull, restart, back-off, probe failures
```

---

## Structured Application Logs

The application writes structured request logs in key-value style.

Example log line:

```text
time=2026-06-30T07:19:25.036Z level=INFO service=application-service env=local-k8s thread=http-nio-8080-exec-8 logger=c.s.a.RequestLoggingFilter requestId=29df611f-537c-4d4f-9ace-aaee830c9ff8 message="http_request method=GET path=/api/v1/applications/443 status=200 durationMs=2 requestId=29df611f-537c-4d4f-9ace-aaee830c9ff8"
```

Useful fields:

```text
time
level
service
env
thread
logger
requestId
message
method
path
status
durationMs
```

The important request-level pattern is:

```text
http_request
```

This allows filtering application request logs in Loki.

---

## Testing Application Logs with kubectl

Open application-service locally:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

In another terminal, follow logs:

```bash
kubectl logs -l app=application-service -n sre-lab -f
```

Generate successful read:

```bash
curl -i http://localhost:8080/api/v1/applications/443
```

Generate controlled 500:

```bash
curl -i http://localhost:8080/api/v1/failure/500
```

Generate controlled slow request:

```bash
curl -i http://localhost:8080/api/v1/failure/slow
```

Search request logs through kubectl:

```bash
kubectl logs -l app=application-service -n sre-lab --since=10m | grep "http_request"
```

Search 500 logs:

```bash
kubectl logs -l app=application-service -n sre-lab --since=10m | grep "status=500"
```

Search slow endpoint logs:

```bash
kubectl logs -l app=application-service -n sre-lab --since=10m | grep "/api/v1/failure/slow"
```

Search by requestId:

```bash
kubectl logs -l app=application-service -n sre-lab --since=10m | grep "<requestId>"
```

---

## Limitations of kubectl logs

`kubectl logs` is useful for local troubleshooting, but it is not enough for production.

Main limitations:

```text
logs can disappear when Pods or Nodes are deleted
search across many Pods is inconvenient
there is no proper long-term retention
there is no good UI for historical investigation
there is no strong analytics experience
correlation across services is difficult
```

For production incident response, centralized logging is required.

---

## Centralized Logging Architecture

The centralized logging model is:

```text
application-service
  ↓ stdout/stderr
container runtime log files
  ↓ stored under /var/log/pods
Promtail DaemonSet
  ↓ sends to
Loki
  ↓ queried by
Grafana
```

Promtail runs as a DaemonSet because logs physically exist on Kubernetes Nodes.

The pattern is:

```text
one Node = one Promtail Pod
```

Promtail reads container logs from:

```text
/var/log/pods
```

Promtail sends logs to Loki:

```text
http://loki-service.monitoring.svc.cluster.local:3100/loki/api/v1/push
```

Grafana queries Loki through the Loki datasource:

```text
http://loki-service.monitoring.svc.cluster.local:3100
```

or, depending on the Service name used in the cluster:

```text
http://loki.monitoring.svc.cluster.local:3100
```

---

## Loki

Loki is the centralized logging backend.

Loki is different from Elasticsearch/OpenSearch because Loki indexes labels, not the full text of every log line.

Good Loki labels:

```text
namespace
app
pod
container
node
environment
level
```

Bad Loki labels:

```text
request_id
user_id
order_id
trace_id
application_id
duration_ms
```

Bad labels usually have high cardinality.

High cardinality means too many unique values.

High-cardinality labels can create too many streams and hurt Loki performance.

Store high-cardinality values inside the log line, not as labels.

---

## Promtail

Promtail is the log collector.

Promtail responsibilities:

```text
read Kubernetes container logs
parse CRI log format
add Kubernetes metadata
attach labels
send logs to Loki
```

Promtail uses Kubernetes service discovery:

```yaml
kubernetes_sd_configs:
  - role: pod
```

Promtail adds labels such as:

```text
namespace
pod
container
node
app
```

This allows LogQL queries like:

```logql
{namespace="sre-lab", container="application-service"}
```

---

## Correct Promtail ConfigMap

Promtail config path:

```text
k8s/observability/loki/promtail-configmap.yaml
```

Expected config:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: promtail-config
  namespace: monitoring
  labels:
    app: promtail
    component: observability
data:
  promtail.yaml: |
    server:
      http_listen_port: 9080
      grpc_listen_port: 0

    positions:
      filename: /tmp/positions.yaml

    clients:
      - url: http://loki-service.monitoring.svc.cluster.local:3100/loki/api/v1/push

    scrape_configs:
      - job_name: kubernetes-pods
        kubernetes_sd_configs:
          - role: pod

        pipeline_stages:
          - cri: {}

        relabel_configs:
          - source_labels:
              - __meta_kubernetes_pod_node_name
            target_label: __host__

          - source_labels:
              - __meta_kubernetes_namespace
            target_label: namespace

          - source_labels:
              - __meta_kubernetes_pod_name
            target_label: pod

          - source_labels:
              - __meta_kubernetes_pod_container_name
            target_label: container

          - source_labels:
              - __meta_kubernetes_pod_node_name
            target_label: node

          - source_labels:
              - __meta_kubernetes_pod_label_app
            target_label: app

          - source_labels:
              - __meta_kubernetes_pod_uid
              - __meta_kubernetes_pod_container_name
            separator: /
            regex: (.+)/(.+)
            replacement: /var/log/pods/*$1/$2/*.log
            target_label: __path__
```

Important details:

```text
__host__ must contain the Kubernetes Node name.
__path__ must point to the real container log files under /var/log/pods.
```

The actual kind log path looks like:

```text
/var/log/pods/<namespace>_<pod-name>_<pod-uid>/<container-name>/<number>.log
```

Example:

```text
/var/log/pods/sre-lab_application-service-6665cf467-r6s2d_73b925f2-be4f-4b90-aeb2-4632307d8b1e/application-service/2.log
```

The relabeling rule:

```yaml
replacement: /var/log/pods/*$1/$2/*.log
```

matches:

```text
/var/log/pods/*<pod-uid>/<container-name>/*.log
```

---

## Correct Promtail DaemonSet

Promtail DaemonSet path:

```text
k8s/observability/loki/promtail-daemonset.yaml
```

Expected DaemonSet:

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: promtail
  namespace: monitoring
  labels:
    app: promtail
    component: observability
spec:
  selector:
    matchLabels:
      app: promtail
  template:
    metadata:
      labels:
        app: promtail
        component: observability
    spec:
      serviceAccountName: promtail
      containers:
        - name: promtail
          image: grafana/promtail:2.9.8
          imagePullPolicy: IfNotPresent
          securityContext:
            runAsUser: 0
            runAsGroup: 0
          args:
            - "-config.file=/etc/promtail/promtail.yaml"
          env:
            - name: HOSTNAME
              valueFrom:
                fieldRef:
                  fieldPath: spec.nodeName
          ports:
            - name: http
              containerPort: 9080
          volumeMounts:
            - name: promtail-config
              mountPath: /etc/promtail
              readOnly: true
            - name: pods
              mountPath: /var/log/pods
              readOnly: true
            - name: positions
              mountPath: /tmp
          resources:
            requests:
              cpu: "50m"
              memory: "64Mi"
            limits:
              cpu: "300m"
              memory: "256Mi"
      volumes:
        - name: promtail-config
          configMap:
            name: promtail-config
        - name: pods
          hostPath:
            path: /var/log/pods
            type: Directory
        - name: positions
          emptyDir: {}
```

Important fix:

```yaml
env:
  - name: HOSTNAME
    valueFrom:
      fieldRef:
        fieldPath: spec.nodeName
```

This ensures Promtail uses the Kubernetes Node name, not its own Pod name.

---

## Apply Promtail Changes

Apply Promtail config and DaemonSet:

```bash
kubectl apply -f k8s/observability/loki/promtail-configmap.yaml
kubectl apply -f k8s/observability/loki/promtail-daemonset.yaml
```

Restart Promtail:

```bash
kubectl rollout restart daemonset/promtail -n monitoring
kubectl rollout status daemonset/promtail -n monitoring
```

Check Promtail Pods:

```bash
kubectl get pods -n monitoring -l app=promtail
```

Check Promtail logs:

```bash
kubectl logs -l app=promtail -n monitoring --tail=100
```

---

## Promtail Health Check

Open Promtail local UI:

```bash
kubectl port-forward daemonset/promtail 9080:9080 -n monitoring
```

Open:

```text
http://localhost:9080
```

Check targets:

```text
http://localhost:9080/targets
```

Expected result:

```text
kubernetes-pods (N/N ready)
```

Bad result:

```text
kubernetes-pods (0/0 ready)
```

Check service discovery:

```text
http://localhost:9080/service-discovery
```

Check config:

```text
http://localhost:9080/config
```

Correct config should show selector similar to:

```text
spec.nodeName=sre-lab-control-plane
```

Bad config showed:

```text
spec.nodeName=promtail-xxxxx
```

---

## Validate HOSTNAME

Get Promtail Pod:

```bash
kubectl get pods -n monitoring -l app=promtail
```

Check HOSTNAME inside Promtail:

```bash
kubectl exec -it <promtail-pod-name> -n monitoring -- printenv HOSTNAME
```

Expected result:

```text
sre-lab-control-plane
```

or another real Kubernetes Node name.

Bad result:

```text
promtail-xxxxx
```

If HOSTNAME is the Promtail Pod name, Kubernetes discovery will not find application Pods correctly.

---

## Validate File Access from Promtail

Get Promtail Pod:

```bash
kubectl get pods -n monitoring -l app=promtail
```

Enter Promtail Pod:

```bash
kubectl exec -it <promtail-pod-name> -n monitoring -- sh
```

Check mounted logs:

```sh
ls -la /var/log/pods
ls -la /var/log/pods | grep sre-lab
```

Check application-service log file:

```sh
cat /var/log/pods/sre-lab_application-service-6665cf467-r6s2d_73b925f2-be4f-4b90-aeb2-4632307d8b1e/application-service/2.log | tail
```

The exact Pod name and UID will differ. Use actual directory names from:

```sh
ls -la /var/log/pods | grep application-service
```

If Promtail can read the file manually, hostPath and permissions are working.

---

## Loki Health Check

Check Loki Pod:

```bash
kubectl get pods -n monitoring -l app=loki
```

Check Loki Service:

```bash
kubectl get svc -n monitoring
```

Check Loki endpoints:

```bash
kubectl get endpoints loki-service -n monitoring
```

or:

```bash
kubectl get endpoints loki -n monitoring
```

depending on the Service name.

Port-forward Loki:

```bash
kubectl port-forward service/loki-service 3100:3100 -n monitoring
```

Check readiness:

```bash
curl -s http://localhost:3100/ready
```

Expected result:

```text
ready
```

Check Loki labels:

```bash
curl -s http://localhost:3100/loki/api/v1/labels | python3 -m json.tool
```

Check namespace values:

```bash
curl -s http://localhost:3100/loki/api/v1/label/namespace/values | python3 -m json.tool
```

Expected namespace:

```text
sre-lab
```

Check app values:

```bash
curl -s http://localhost:3100/loki/api/v1/label/app/values | python3 -m json.tool
```

Application-service may appear as:

```text
application-service
```

If `app` is missing, use the more stable `container` label in LogQL:

```logql
{namespace="sre-lab", container="application-service"}
```

---

## Grafana Datasources

Grafana should have two datasources:

```text
Prometheus
Loki
```

Prometheus datasource:

```text
http://prometheus.monitoring.svc.cluster.local:9090
```

Loki datasource:

```text
http://loki-service.monitoring.svc.cluster.local:3100
```

or:

```text
http://loki.monitoring.svc.cluster.local:3100
```

depending on the Service name used in the cluster.

Datasource provisioning file:

```text
k8s/observability/grafana/datasource-configmap.yaml
```

Expected datasource config pattern:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-datasources
  namespace: monitoring
  labels:
    app: grafana
    component: observability
data:
  prometheus-datasource.yaml: |
    apiVersion: 1

    datasources:
      - name: Prometheus
        type: prometheus
        access: proxy
        url: http://prometheus.monitoring.svc.cluster.local:9090
        isDefault: true
        editable: true

  loki-datasource.yaml: |
    apiVersion: 1

    datasources:
      - name: Loki
        type: loki
        access: proxy
        url: http://loki-service.monitoring.svc.cluster.local:3100
        isDefault: false
        editable: true
```

After changing datasource provisioning, restart Grafana:

```bash
kubectl apply -f k8s/observability/grafana/datasource-configmap.yaml
kubectl rollout restart deployment/grafana -n monitoring
kubectl rollout status deployment/grafana -n monitoring
```

Check Grafana logs:

```bash
kubectl logs -l app=grafana -n monitoring --tail=100
```

Open Grafana:

```bash
kubectl port-forward service/grafana 3000:3000 -n monitoring
```

Then open:

```text
http://localhost:3000
```

Check datasources:

```text
Connections → Data sources
```

Expected datasources:

```text
Prometheus
Loki
```

---

## Grafana Explore: Loki Queries

Open Grafana:

```text
Explore → Datasource: Loki
```

Base query:

```logql
{namespace="sre-lab", container="application-service"}
```

All application request logs:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

Application request logs without actuator noise:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" !~ "/actuator/.*"
```

Application endpoint logs:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/applications"
```

Controlled 500 endpoint logs:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/failure/500"
```

HTTP 500 logs:

```logql
{namespace="sre-lab", container="application-service"} |= "status=500"
```

HTTP 5xx logs:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |~ "status=5.."
```

Slow endpoint logs:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/failure/slow"
```

Logs for multiple endpoints:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |~ "api/v1/applications/|api/v1/failure/500"
```

Search by requestId:

```logql
{namespace="sre-lab", container="application-service"} |= "<requestId>"
```

---

## LogQL Filter Basics

Line filter `|=` keeps lines that contain exact text:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

Line filter `!=` removes lines that contain exact text:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" != "/actuator"
```

Regex filter `|~` keeps lines matching a regex:

```logql
{namespace="sre-lab", container="application-service"} |~ "status=5.."
```

Regex negative filter `!~` removes lines matching a regex:

```logql
{namespace="sre-lab", container="application-service"} !~ "/actuator/.*"
```

For OR-style text matching, use regex:

```logql
{namespace="sre-lab", container="application-service"} |~ "pattern1|pattern2"
```

Do not use plain `or` for basic text line filtering.

---

## Grafana Dashboard Log Panels

Dashboard name:

```text
Application Service Overview
```

Dashboard JSON path:

```text
k8s/observability/grafana/dashboards/application-service-overview.json
```

Expected metric and alert panels:

```text
1. Application Service UP
2. HTTP Request Rate
3. HTTP 5xx Error Rate
4. HTTP p95 Latency
5. Business Applications Created
6. Active Firing Alerts
7. Number of Firing Alerts
8. Firing Alerts by Severity
9. Firing Alerts by Category
```

Expected Loki log panels:

```text
10. Application Request Logs
11. Application 5xx Logs
12. Slow Request Logs
```

Application Request Logs query:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" !~ "/actuator/.*"
```

Application 5xx Logs query:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |~ "status=5.."
```

Slow Request Logs query:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/failure/slow"
```

The role of log panels is to provide quick evidence near metrics and alert context.

Deep log investigation should still be done in Grafana Explore.

---

## Controlled 500 Incident Test

Open application-service locally:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Generate controlled 500 traffic:

```bash
for i in {1..40}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/failure/500
  sleep 1
done
```

Expected HTTP status:

```text
500
```

Check Grafana dashboard:

```text
HTTP 5xx Error Rate increases
Number of Firing Alerts may become greater than 0
Firing Alerts by Severity shows critical
Firing Alerts by Category shows reliability
Application 5xx Logs shows status=500
Application Request Logs shows /api/v1/failure/500
```

Check Loki Explore:

```logql
{namespace="sre-lab", container="application-service"} |= "status=500"
```

or:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/failure/500"
```

Check Prometheus alert state:

```promql
ALERTS{alertname="ApplicationServiceHighErrorRate"}
```

Expected states:

```text
pending
firing
```

Mini-RCA template:

```text
Symptom:
HTTP 5xx error rate exceeded the threshold.

Detection:
Prometheus alert ApplicationServiceHighErrorRate became pending/firing.

Impact:
Requests to /api/v1/failure/500 returned HTTP 500.

Evidence:
Grafana showed increased HTTP 5xx Error Rate.
Loki showed request logs with path=/api/v1/failure/500 and status=500.

Root cause:
Controlled failure endpoint was called repeatedly for test purposes.

Resolution:
Stopped generating 500 traffic. Error rate returned to normal after the Prometheus rate window expired.
```

---

## Controlled Slow Incident Test

Open application-service locally:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Generate controlled slow traffic:

```bash
for i in {1..90}; do
  curl -s -o /dev/null -w "%{time_total} %{http_code}\n" http://localhost:8080/api/v1/failure/slow
  sleep 1
done
```

Expected HTTP status:

```text
200
```

Expected response time:

```text
around 1 second or more
```

Check Grafana dashboard:

```text
HTTP p95 Latency increases
HTTP 5xx Error Rate may stay close to 0
ApplicationServiceHighLatency may become pending/firing
Firing Alerts by Severity shows warning
Firing Alerts by Category shows latency
Slow Request Logs shows /api/v1/failure/slow
```

Check Loki Explore:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/failure/slow"
```

Check Prometheus p95 latency:

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{job="application-service-pods", uri!~"/actuator.*"}[1m])
  )
)
```

Mini-RCA template:

```text
Symptom:
HTTP p95 latency exceeded 1 second.

Detection:
Prometheus alert ApplicationServiceHighLatency became pending/firing.

Impact:
Requests to /api/v1/failure/slow returned HTTP 200 but were delayed. This represents user-experience degradation without server-side errors.

Evidence:
Grafana showed increased HTTP p95 Latency.
Prometheus histogram_quantile query showed p95 latency above 1 second.
Loki showed request logs with path=/api/v1/failure/slow, status=200 and elevated durationMs.

Root cause:
Controlled slow endpoint was called repeatedly for test purposes.

Resolution:
Stopped generating slow traffic. Latency returned to normal after the Prometheus rate window expired.
```

---

## Why HTTP 200 Is Not Always Healthy

A successful HTTP status does not always mean that the user experience is good.

Bad conclusion:

```text
No HTTP 500 means everything is fine.
```

Correct conclusion:

```text
HTTP 500 may be zero, but high latency can still degrade user experience and business conversion.
```

Reliability incident:

```text
5xx rate increases
users receive errors
```

Latency incident:

```text
5xx may remain zero
users wait too long
```

Both incident types require monitoring, alerting, dashboards, and logs.

---

## Troubleshooting: Loki Pod Not Running

Check Loki Pod:

```bash
kubectl get pods -n monitoring -l app=loki
```

Describe Loki Pod:

```bash
kubectl describe pod <loki-pod-name> -n monitoring
```

Check Loki logs:

```bash
kubectl logs <loki-pod-name> -n monitoring --tail=200
```

Common causes:

```text
invalid Loki config
image pull issue
insufficient resources
ConfigMap not mounted
filesystem path issue
```

---

## Troubleshooting: Loki Not Ready

Port-forward Loki:

```bash
kubectl port-forward service/loki-service 3100:3100 -n monitoring
```

Check readiness:

```bash
curl -s http://localhost:3100/ready
```

Expected result:

```text
ready
```

If not ready, check logs:

```bash
kubectl logs -l app=loki -n monitoring --tail=200
```

Check ConfigMap:

```bash
kubectl get configmap loki-config -n monitoring -o yaml
```

---

## Troubleshooting: Promtail Not Running

Check Promtail DaemonSet:

```bash
kubectl get daemonset promtail -n monitoring
```

Check Promtail Pods:

```bash
kubectl get pods -n monitoring -l app=promtail
```

Describe Promtail Pod:

```bash
kubectl describe pod <promtail-pod-name> -n monitoring
```

Check Promtail logs:

```bash
kubectl logs <promtail-pod-name> -n monitoring --tail=200
```

Common causes:

```text
RBAC issue
wrong Loki URL
hostPath not mounted
invalid Promtail config
Loki unavailable
wrong HOSTNAME value
wrong __path__ relabeling
```

---

## Troubleshooting: Promtail Targets 0/0 Ready

Symptom:

```text
Loki does not show application-service logs.
Grafana Explore returns no logs for application-service.
Promtail /targets shows kubernetes-pods (0/0 ready).
```

Evidence:

```text
application-service logs exist on the Kubernetes node under /var/log/pods.
Promtail Pod can manually read the application-service log file.
Loki is ready.
Promtail is running.
Promtail /config shows selector spec.nodeName=promtail-xxxxx.
```

Root cause:

```text
Promtail used the Pod hostname as HOSTNAME instead of the Kubernetes node name.
As a result, Kubernetes service discovery selected pods with spec.nodeName equal to the Promtail Pod name.
No such node existed, so Promtail discovered zero targets.
```

Fix:

```text
Set the HOSTNAME environment variable in the Promtail DaemonSet from spec.nodeName.
This makes Promtail discover pods running on the same Kubernetes node.
```

Fixed DaemonSet configuration:

```yaml
env:
  - name: HOSTNAME
    valueFrom:
      fieldRef:
        fieldPath: spec.nodeName
```

Additional fix:

```text
Correct __path__ relabeling to match Kubernetes pod log file paths under /var/log/pods.
```

Correct __path__ relabeling:

```yaml
- source_labels:
    - __meta_kubernetes_pod_uid
    - __meta_kubernetes_pod_container_name
  separator: /
  regex: (.+)/(.+)
  replacement: /var/log/pods/*$1/$2/*.log
  target_label: __path__
```

Validation:

```text
Promtail /targets changed from kubernetes-pods (0/0 ready) to active file targets.
Grafana Explore started showing application-service logs from Loki.
```

---

## Troubleshooting: Promtail Can Read Files Manually but Loki Is Empty

Check Promtail targets:

```bash
kubectl port-forward daemonset/promtail 9080:9080 -n monitoring
```

Open:

```text
http://localhost:9080/targets
```

If targets are `0/0 ready`, Promtail is not discovering targets.

If targets are ready, check Promtail metrics:

```bash
curl -s http://localhost:9080/metrics | grep promtail_read_bytes_total
curl -s http://localhost:9080/metrics | grep promtail_sent_entries_total
```

Interpretation:

```text
read_bytes grows, sent_entries grows:
Promtail reads and sends logs.

read_bytes grows, sent_entries does not grow:
Promtail reads but does not send logs.

read_bytes does not grow:
Promtail is not tailing log files.
```

Check Promtail logs:

```bash
kubectl logs -l app=promtail -n monitoring --tail=300 | grep -i -E "error|warn|failed|batch|loki|push|denied|refused|timeout"
```

Common causes:

```text
Loki Service has no endpoints
wrong Loki client URL
Loki is not ready
network/DNS issue
bad labels or bad __path__
positions file already points to end of file
```

Generate fresh logs before testing again.

---

## Troubleshooting: No Logs in Loki

Check whether application logs exist in Kubernetes:

```bash
kubectl logs -l app=application-service -n sre-lab --since=10m
```

Generate fresh logs:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

```bash
curl -i http://localhost:8080/api/v1/applications/443
curl -i http://localhost:8080/api/v1/failure/500
curl -i http://localhost:8080/api/v1/failure/slow
```

Check Promtail targets:

```text
http://localhost:9080/targets
```

Check Promtail logs:

```bash
kubectl logs -l app=promtail -n monitoring --tail=200
```

Check Loki labels:

```bash
kubectl port-forward service/loki-service 3100:3100 -n monitoring
```

```bash
curl -s http://localhost:3100/loki/api/v1/labels | python3 -m json.tool
curl -s http://localhost:3100/loki/api/v1/label/namespace/values | python3 -m json.tool
curl -s http://localhost:3100/loki/api/v1/label/container/values | python3 -m json.tool
```

Check Grafana Explore time range:

```text
Last 15 minutes
Last 1 hour
```

Try broader LogQL query:

```logql
{namespace="sre-lab"}
```

Then narrow down:

```logql
{namespace="sre-lab", container="application-service"}
```

---

## Troubleshooting: Grafana Cannot Connect to Loki

Check Loki Service:

```bash
kubectl get svc -n monitoring
kubectl get endpoints -n monitoring
```

Check Loki readiness from local port-forward:

```bash
kubectl port-forward service/loki-service 3100:3100 -n monitoring
curl -s http://localhost:3100/ready
```

Check Grafana datasource config:

```bash
kubectl get configmap grafana-datasources -n monitoring -o yaml
```

Expected Loki URL:

```text
http://loki-service.monitoring.svc.cluster.local:3100
```

or:

```text
http://loki.monitoring.svc.cluster.local:3100
```

depending on the Service name.

Restart Grafana after datasource changes:

```bash
kubectl rollout restart deployment/grafana -n monitoring
kubectl rollout status deployment/grafana -n monitoring
```

Check Grafana logs:

```bash
kubectl logs -l app=grafana -n monitoring --tail=100
```

---

## Troubleshooting: LogQL Query Returns Empty Result

Check time range first.

Use:

```text
Last 15 minutes
Last 1 hour
```

Generate fresh logs.

Try broad query:

```logql
{namespace="sre-lab"}
```

Try application query:

```logql
{namespace="sre-lab", container="application-service"}
```

Try HTTP request logs:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

Try without app label if app label is missing:

```logql
{namespace="sre-lab"} |= "http_request"
```

Check available labels:

```bash
curl -s http://localhost:3100/loki/api/v1/labels | python3 -m json.tool
curl -s http://localhost:3100/loki/api/v1/label/container/values | python3 -m json.tool
```

---

## Production Notes

The current lab setup uses simple local choices.

Loki storage uses:

```text
emptyDir
```

This is acceptable for lab, but not for production.

Production should use:

```text
persistent volume
object storage
retention policy
resource sizing
authentication
authorization
network policies
backup/restore strategy
```

Current Loki auth:

```text
auth_enabled: false
```

This is acceptable for local lab only.

Production must use appropriate access control and network isolation.

Current dashboard is stored as JSON in Git.

This is better than only saving it in Grafana UI.

Future improvements can include:

```text
dashboard provisioning
Kustomize
Helm
Grafana Operator
Terraform
```

---

## Final Health Check

Check all Pods:

```bash
kubectl get pods -n sre-lab
kubectl get pods -n monitoring
```

Check Loki:

```bash
kubectl get pods -n monitoring -l app=loki
kubectl get svc -n monitoring
```

Check Promtail:

```bash
kubectl get pods -n monitoring -l app=promtail
kubectl get daemonset promtail -n monitoring
```

Check Promtail targets:

```text
http://localhost:9080/targets
```

Expected:

```text
kubernetes-pods (N/N ready)
```

Check Grafana datasources:

```text
Grafana → Connections → Data sources → Prometheus and Loki
```

Check Loki query in Grafana Explore:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

Check metric dashboard:

```text
Application Service Overview
```

Expected dashboard includes:

```text
metrics panels
alert panels
Loki log panels
```

---

## Stopping the Lab

To reduce local resource usage:

```bash
kubectl scale deployment/application-service --replicas=0 -n sre-lab
kubectl scale deployment/prometheus --replicas=0 -n monitoring
kubectl scale deployment/alertmanager --replicas=0 -n monitoring
kubectl scale deployment/grafana --replicas=0 -n monitoring
kubectl scale deployment/loki --replicas=0 -n monitoring
```

Promtail is a DaemonSet. To stop it:

```bash
kubectl delete daemonset promtail -n monitoring
```

Then re-apply it later:

```bash
kubectl apply -f k8s/observability/loki/promtail-daemonset.yaml
```

---

## Key Principles

Metrics are best for alerting and SLO-style monitoring.

Logs are best for diagnosis and evidence.

Dashboards are best for incident context.

Alertmanager is best for routing, grouping, silencing, and notification management.

Loki makes logs available centrally.

Promtail connects Kubernetes container logs to Loki.

A healthy SRE workflow connects all of them:

```text
Alert
  ↓
Dashboard
  ↓
Metric evidence
  ↓
Log evidence
  ↓
Mini-RCA
```