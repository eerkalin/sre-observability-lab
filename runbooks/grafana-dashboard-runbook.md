# Grafana Dashboard Runbook

## Purpose

This runbook describes how to operate and troubleshoot Grafana dashboards in the local Kubernetes SRE observability lab.

Grafana is used as the visualization layer for:

- application availability
- HTTP traffic
- HTTP 5xx error rate
- HTTP p95 latency
- business application creation metrics
- Prometheus alert state
- alert severity and category overview

Prometheus remains the source of metrics and PromQL queries. Grafana visualizes those metrics and helps engineers understand service health and incident impact.

---

## Architecture

```text
application-service
  ↓ exposes metrics
/actuator/prometheus
  ↓ scraped by
Prometheus
  ↓ queried by
Grafana
  ↓ shows
dashboards

Prometheus alert rules
  ↓
ALERTS metric
  ↓ queried by
Grafana alert panels
```

---

## Environment

Monitoring namespace:

```bash
monitoring
```

Grafana Deployment:

```bash
grafana
```

Grafana Service:

```bash
grafana
```

Grafana local UI:

```text
http://localhost:3000
```

Default lab credentials:

```text
admin / admin
```

Prometheus datasource URL inside Kubernetes:

```text
http://prometheus.monitoring.svc.cluster.local:9090
```

---

## Kubernetes Manifests

Grafana manifests are stored in:

```text
k8s/observability/grafana/
```

Expected files:

```text
k8s/observability/grafana/secret.yaml
k8s/observability/grafana/datasource-configmap.yaml
k8s/observability/grafana/deployment.yaml
k8s/observability/grafana/service.yaml
```

Dashboard JSON is stored in:

```text
k8s/observability/grafana/dashboards/application-service-overview.json
```

---

## Quick Health Check

Check Grafana Pod:

```bash
kubectl get pods -n monitoring -l app=grafana
```

Check Grafana Service:

```bash
kubectl get svc -n monitoring grafana
```

Check Grafana logs:

```bash
kubectl logs -l app=grafana -n monitoring --tail=100
```

Open Grafana UI:

```bash
kubectl port-forward service/grafana 3000:3000 -n monitoring
```

Then open:

```text
http://localhost:3000
```

Login:

```text
admin / admin
```

---

## Prometheus Datasource

Prometheus datasource is provisioned through:

```text
k8s/observability/grafana/datasource-configmap.yaml
```

Expected datasource config:

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus.monitoring.svc.cluster.local:9090
    isDefault: true
    editable: true
```

Grafana reads datasource provisioning files from:

```text
/etc/grafana/provisioning/datasources
```

Check datasource in Grafana UI:

```text
Connections → Data sources → Prometheus → Save & test
```

Expected result:

```text
Successfully queried the Prometheus API.
```

---

## Grafana to Prometheus Connectivity Check

Check Prometheus Service:

```bash
kubectl get svc -n monitoring prometheus
```

Check Prometheus Pod:

```bash
kubectl get pods -n monitoring -l app=prometheus
```

Check Prometheus readiness from inside Kubernetes:

```bash
kubectl run curl-test \
  -n monitoring \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- curl -s http://prometheus.monitoring.svc.cluster.local:9090/-/ready
```

Expected result:

```text
Prometheus Server is Ready.
```

---

## Dashboard

Dashboard name:

```text
Application Service Overview
```

Dashboard JSON file:

```text
k8s/observability/grafana/dashboards/application-service-overview.json
```

Expected panels:

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

---

## Panel Queries

### Application Service UP

```promql
min(up{job="application-service-pods"})
```

Meaning:

```text
1 = all application-service pod targets are scraped successfully
0 = at least one application-service pod target is not scraped successfully
```

Recommended panel type:

```text
Stat
```

---

### HTTP Request Rate

```promql
sum(rate(http_server_requests_seconds_count{job="application-service-pods", uri!~"/actuator.*"}[1m]))
```

Meaning:

```text
Application HTTP request rate, excluding actuator endpoints.
```

Recommended panel type:

```text
Time series
```

Recommended unit:

```text
requests/sec
```

---

### HTTP 5xx Error Rate

```promql
(
  sum(rate(http_server_requests_seconds_count{job="application-service-pods", status=~"5..", uri!~"/actuator.*"}[1m]))
  /
  sum(rate(http_server_requests_seconds_count{job="application-service-pods", uri!~"/actuator.*"}[1m]))
)
```

Meaning:

```text
Ratio of server-side HTTP 5xx responses to all application HTTP responses.
```

Recommended panel type:

```text
Time series
```

Recommended unit:

```text
percentunit
```

---

### HTTP p95 Latency

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{job="application-service-pods", uri!~"/actuator.*"}[1m])
  )
)
```

Meaning:

```text
95th percentile HTTP latency for application endpoints.
```

Recommended panel type:

```text
Time series
```

Recommended unit:

```text
seconds
```

---

### Business Applications Created

```promql
sum(increase(business_applications_created_total{job="application-service-pods"}[5m]))
```

Meaning:

```text
Number of business applications created during the last 5 minutes.
```

Recommended panel type:

```text
Stat
```

---

### Active Firing Alerts

```promql
ALERTS{alertstate="firing"}
```

Meaning:

```text
All currently firing Prometheus alerts.
```

Recommended panel type:

```text
Table
```

---

### Number of Firing Alerts

```promql
count(ALERTS{alertstate="firing"})
```

Meaning:

```text
Total number of currently firing Prometheus alerts.
```

Recommended panel type:

```text
Stat
```

---

### Firing Alerts by Severity

```promql
count by (severity) (
  ALERTS{alertstate="firing"}
)
```

Meaning:

```text
Number of currently firing alerts grouped by severity.
```

Recommended panel type:

```text
Bar gauge
```

---

### Firing Alerts by Category

```promql
count by (category) (
  ALERTS{alertstate="firing"}
)
```

Meaning:

```text
Number of currently firing alerts grouped by category.
```

Recommended panel type:

```text
Bar gauge
```

---

## Dashboard Layout

Recommended dashboard layout:

```text
Top row:
- Application Service UP
- Number of Firing Alerts
- Business Applications Created

Middle row:
- HTTP Request Rate
- HTTP 5xx Error Rate
- HTTP p95 Latency

Bottom row:
- Active Firing Alerts
- Firing Alerts by Severity
- Firing Alerts by Category
```

This layout puts the most important operational signals at the top and supporting details below.

---

## Dashboard Quality Principles

A good dashboard answers operational questions:

```text
Is the service observable?
Is the service receiving traffic?
Are users getting errors?
Are users waiting too long?
Is the business process moving?
Are there active alerts?
```

A poor dashboard only shows low-level technical metrics without impact context:

```text
CPU
memory
disk
threads
JVM internals
container uptime
```

These metrics can be useful for drill-down dashboards, but they should not replace service reliability and business-impact panels.

Main principle:

```text
Impact first. Cause second.
```

---

## Dashboard Anti-Patterns

Avoid dashboards with too many panels.

Prefer:

```text
overview dashboard
drill-down dashboards
```

Avoid dashboards that only show infrastructure metrics.

A service reliability dashboard should include:

```text
traffic
errors
latency
availability
business metrics
alerts
```

Avoid dashboards that are disconnected from alerting.

If an alert fires, the dashboard should help explain the impact.

Avoid dashboards without business context.

A service can be technically healthy while the business process is stopped.

Example:

```text
UP = 1
5xx = 0
latency = normal
applications created = 0
```

This can indicate a business-flow issue, upstream issue, frontend issue, validation issue, scoring issue, or instrumentation issue.

---

## Traffic Generation for Dashboard Testing

Open application-service locally:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Generate successful traffic:

```bash
for i in {1..30}; do
  curl -s -o /dev/null http://localhost:8080/api/v1/applications/123
  sleep 1
done
```

Generate 5xx traffic:

```bash
for i in {1..20}; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/failure/500
  sleep 1
done
```

Generate slow traffic:

```bash
for i in {1..20}; do
  curl -s -o /dev/null -w "%{time_total} %{http_code}\n" http://localhost:8080/api/v1/failure/slow
  sleep 1
done
```

Generate business events:

```bash
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/v1/applications
  echo
done
```

---

## Controlled Alert Panel Test

To test alert panels, trigger `ApplicationServiceTargetDown`.

Temporarily change the metrics path in:

```text
k8s/observability/prometheus/configmap.yaml
```

In job:

```yaml
job_name: "application-service-pods"
```

Change:

```yaml
replacement: /actuator/prometheus
```

to:

```yaml
replacement: /actuator/prometheus-wrong
```

Apply and restart Prometheus:

```bash
kubectl apply -f k8s/observability/prometheus/configmap.yaml
kubectl rollout restart deployment/prometheus -n monitoring
kubectl rollout status deployment/prometheus -n monitoring
```

Wait until Prometheus shows:

```text
ApplicationServiceTargetDown → firing
```

Expected Grafana panel changes:

```text
Number of Firing Alerts > 0
Firing Alerts by Severity shows critical
Firing Alerts by Category shows availability
Active Firing Alerts contains ApplicationServiceTargetDown
```

Restore metrics path:

```yaml
replacement: /actuator/prometheus
```

Apply and restart Prometheus:

```bash
kubectl apply -f k8s/observability/prometheus/configmap.yaml
kubectl rollout restart deployment/prometheus -n monitoring
kubectl rollout status deployment/prometheus -n monitoring
```

Check:

```promql
up{job="application-service-pods"}
```

Expected result:

```text
1
```

---

## Dashboard Persistence

A dashboard created only through Grafana UI can be lost if Grafana is recreated without persistent storage.

The dashboard JSON must be stored in Git.

Current dashboard JSON path:

```text
k8s/observability/grafana/dashboards/application-service-overview.json
```

When exporting dashboard JSON:

- set `id` to `null`
- use stable `uid`, for example `application-service-overview`
- keep title as `Application Service Overview`

Example:

```json
{
  "id": null,
  "uid": "application-service-overview",
  "title": "Application Service Overview"
}
```

Dashboard-as-code benefits:

```text
version control
reviewability
restore capability
future provisioning support
```

---

## Troubleshooting: Grafana Pod Not Running

Check Pod:

```bash
kubectl get pods -n monitoring -l app=grafana
```

Describe Pod:

```bash
kubectl describe pod <grafana-pod-name> -n monitoring
```

Check logs:

```bash
kubectl logs <grafana-pod-name> -n monitoring --tail=100
```

Common causes:

```text
invalid manifest
image pull issue
missing Secret
missing ConfigMap
resource limits too low
```

---

## Troubleshooting: Prometheus Datasource Not Working

Check Grafana datasource config:

```bash
kubectl get configmap grafana-datasources -n monitoring -o yaml
```

Check Grafana logs:

```bash
kubectl logs -l app=grafana -n monitoring --tail=100
```

Check Prometheus Service:

```bash
kubectl get svc -n monitoring prometheus
```

Check Prometheus readiness:

```bash
kubectl run curl-test \
  -n monitoring \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- curl -s http://prometheus.monitoring.svc.cluster.local:9090/-/ready
```

Expected result:

```text
Prometheus Server is Ready.
```

Common causes:

```text
wrong Prometheus datasource URL
Prometheus Pod not running
Prometheus Service missing
network/DNS issue inside Kubernetes
datasource provisioning file not mounted
```

---

## Troubleshooting: Dashboard Shows No Data

Check time range in Grafana.

Use a recent time range:

```text
Last 15 minutes
```

Check Prometheus directly:

```promql
up{job="application-service-pods"}
```

Check application traffic:

```promql
sum(rate(http_server_requests_seconds_count{job="application-service-pods"}[1m]))
```

Check that application-service is running:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Generate test traffic if needed:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

```bash
for i in {1..20}; do
  curl -s -o /dev/null http://localhost:8080/api/v1/applications/123
  sleep 1
done
```

---

## Final Health Check

Check Grafana:

```bash
kubectl get pods -n monitoring -l app=grafana
kubectl get svc -n monitoring grafana
kubectl logs -l app=grafana -n monitoring --tail=100
```

Check Prometheus:

```bash
kubectl get pods -n monitoring -l app=prometheus
kubectl logs -l app=prometheus -n monitoring --tail=100
```

Check application-service:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Check dashboard base query:

```promql
up{job="application-service-pods"}
```

Expected result:

```text
1
```

---

## Operational Workflow

A normal SRE workflow should look like this:

```text
Alert received
  ↓
Open runbook
  ↓
Open Grafana dashboard
  ↓
Check service UP / traffic / errors / latency / business metrics
  ↓
Use PromQL drill-down
  ↓
Check Kubernetes state and logs
  ↓
Escalate or mitigate
```

Grafana is not only a visualization tool.

Grafana is an incident context tool.

A good dashboard helps engineers move from signal to diagnosis faster.