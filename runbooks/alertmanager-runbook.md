# Alertmanager Runbook

## Purpose

This runbook describes how to operate and troubleshoot Alertmanager in the local Kubernetes SRE observability lab.

Alertmanager is responsible for:

- receiving firing alerts from Prometheus
- grouping alerts
- routing alerts to receivers
- deduplicating repeated alerts
- silencing alerts during maintenance
- inhibiting secondary alerts

Prometheus detects alert conditions. Alertmanager manages alert delivery and notification behavior.

---

## Architecture

```text
application-service
  ↓ exposes metrics
/actuator/prometheus
  ↓ scraped by
Prometheus
  ↓ evaluates alert rules
Prometheus firing alert
  ↓ sends alert to
Alertmanager
  ↓ applies
routing / grouping / silence / inhibition
  ↓ sends notification or suppresses it
receiver
```

---

## Environment

Monitoring namespace:

```bash
monitoring
```

Alertmanager Deployment:

```bash
alertmanager
```

Alertmanager Service:

```bash
alertmanager
```

Alertmanager internal Kubernetes DNS:

```text
alertmanager.monitoring.svc.cluster.local:9093
```

Alertmanager local UI:

```text
http://localhost:9093
```

Prometheus local UI:

```text
http://localhost:9090
```

---

## Quick Health Check

Check Alertmanager Pod:

```bash
kubectl get pods -n monitoring -l app=alertmanager
```

Check Alertmanager Service:

```bash
kubectl get svc -n monitoring alertmanager
```

Check Alertmanager logs:

```bash
kubectl logs -l app=alertmanager -n monitoring --tail=100
```

Open Alertmanager UI:

```bash
kubectl port-forward service/alertmanager 9093:9093 -n monitoring
```

Then open:

```text
http://localhost:9093
```

Check Alertmanager readiness from inside Kubernetes:

```bash
kubectl run curl-test \
  -n monitoring \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- curl -s http://alertmanager.monitoring.svc.cluster.local:9093/-/ready
```

Expected result:

```text
OK
```

---

## Configuration Location

Alertmanager Kubernetes manifests are stored in:

```text
k8s/observability/alertmanager/
```

Expected files:

```text
k8s/observability/alertmanager/configmap.yaml
k8s/observability/alertmanager/deployment.yaml
k8s/observability/alertmanager/service.yaml
```

Alertmanager configuration is stored in:

```text
k8s/observability/alertmanager/configmap.yaml
```

Inside the Alertmanager Pod, the config is mounted as:

```text
/etc/alertmanager/alertmanager.yml
```

Check effective config inside the Pod:

```bash
kubectl get pods -n monitoring -l app=alertmanager
kubectl exec -it <alertmanager-pod-name> -n monitoring -- cat /etc/alertmanager/alertmanager.yml
```

---

## Current Alertmanager Configuration Model

The current lab configuration includes:

- default receiver
- critical receiver
- business receiver
- warning receiver
- grouping by alert name, service, and category
- short lab timing values
- inhibition rule for secondary warning alerts

Expected route model:

```yaml
route:
  receiver: "default-receiver"
  group_by:
    - alertname
    - service
    - category
  group_wait: 10s
  group_interval: 1m
  repeat_interval: 5m

  routes:
    - matchers:
        - severity="critical"
      receiver: "critical-receiver"

    - matchers:
        - category="business"
      receiver: "business-receiver"

    - matchers:
        - severity="warning"
      receiver: "warning-receiver"
```

Expected inhibition model:

```yaml
inhibit_rules:
  - source_matchers:
      - alertname="ApplicationServiceTargetDown"
      - severity="critical"
    target_matchers:
      - severity="warning"
    equal:
      - service
```

Expected receivers:

```yaml
receivers:
  - name: "default-receiver"
  - name: "critical-receiver"
  - name: "business-receiver"
  - name: "warning-receiver"
```

---

## Prometheus to Alertmanager Integration

Prometheus must contain the following block in `prometheus.yml`:

```yaml
alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - "alertmanager.monitoring.svc.cluster.local:9093"
```

This config is stored in:

```text
k8s/observability/prometheus/configmap.yaml
```

Inside the Prometheus Pod, the config is mounted as:

```text
/etc/prometheus/prometheus.yml
```

Check Prometheus config inside the Pod:

```bash
kubectl get pods -n monitoring -l app=prometheus
kubectl exec -it <prometheus-pod-name> -n monitoring -- cat /etc/prometheus/prometheus.yml
```

Check that Prometheus can reach Alertmanager:

```bash
kubectl run curl-test \
  -n monitoring \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- curl -s http://alertmanager.monitoring.svc.cluster.local:9093/-/ready
```

Expected result:

```text
OK
```

---

## Alertmanager API Checks

Open Alertmanager port-forward:

```bash
kubectl port-forward service/alertmanager 9093:9093 -n monitoring
```

Check active alerts:

```bash
curl -s http://localhost:9093/api/v2/alerts | python3 -m json.tool
```

Check alert groups:

```bash
curl -s http://localhost:9093/api/v2/alerts/groups | python3 -m json.tool
```

Check silences:

```bash
curl -s http://localhost:9093/api/v2/silences | python3 -m json.tool
```

---

## Alert Delivery Check

Use this check to confirm that Prometheus sends firing alerts to Alertmanager.

### 1. Open Prometheus UI

```bash
kubectl port-forward service/prometheus 9090:9090 -n monitoring
```

Open:

```text
http://localhost:9090
```

### 2. Open Alertmanager UI

```bash
kubectl port-forward service/alertmanager 9093:9093 -n monitoring
```

Open:

```text
http://localhost:9093
```

### 3. Trigger a controlled TargetDown alert

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

Then check Alertmanager UI or API:

```bash
curl -s http://localhost:9093/api/v2/alerts | python3 -m json.tool
```

Expected alert:

```text
ApplicationServiceTargetDown
```

### 4. Restore metrics path

Change back:

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

## Silence

Silence temporarily suppresses notifications for matching alerts.

Silence does not:

- fix the problem
- make the Prometheus alert inactive
- disable the alert rule
- stop Alertmanager from receiving the alert

A good silence should have:

- precise matchers
- short duration
- clear comment
- owner / created by

Example matcher:

```text
alertname = ApplicationServiceTargetDown
```

Example duration:

```text
30 minutes
```

Example created by:

```text
sre-lab
```

Example comment:

```text
Lab test: suppress TargetDown alert during Alertmanager silence practice
```

Avoid broad matchers unless intentional and time-limited:

```text
severity = critical
```

```text
service = application-service
```

### Create Silence from UI

Open Alertmanager:

```text
http://localhost:9093
```

Go to:

```text
Silences → New Silence
```

Use matcher:

```text
alertname = ApplicationServiceTargetDown
```

After testing, expire the silence:

```text
Silences → select silence → Expire
```

Operational rule:

```text
Every silence must have owner, duration, and comment.
```

---

## Grouping

Grouping combines related alerts into one notification group.

Current grouping config:

```yaml
group_by:
  - alertname
  - service
  - category
```

This means alerts with the same `alertname`, `service`, and `category` are grouped together.

Pod-level labels such as `pod` are not included in `group_by`, so multiple affected Pods can appear in one alert group.

Example:

```text
ApplicationServiceTargetDown pod=a
ApplicationServiceTargetDown pod=b
```

Both alerts can be grouped together if they share:

```text
alertname=ApplicationServiceTargetDown
service=application-service
category=availability
```

This reduces alert noise.

### Grouping Practice

Scale application-service to 2 replicas:

```bash
kubectl scale deployment/application-service --replicas=2 -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Check that Prometheus sees two pod targets:

```promql
up{job="application-service-pods"}
```

Trigger TargetDown by temporarily setting:

```yaml
replacement: /actuator/prometheus-wrong
```

Apply and restart Prometheus:

```bash
kubectl apply -f k8s/observability/prometheus/configmap.yaml
kubectl rollout restart deployment/prometheus -n monitoring
kubectl rollout status deployment/prometheus -n monitoring
```

Check Alertmanager groups:

```bash
curl -s http://localhost:9093/api/v2/alerts/groups | python3 -m json.tool
```

Expected result:

```text
Two pod-level firing alerts appear inside one Alertmanager group.
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

Return to 1 replica:

```bash
kubectl scale deployment/application-service --replicas=1 -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

---

## Timing Controls

Current lab values:

```yaml
group_wait: 10s
group_interval: 1m
repeat_interval: 5m
```

Meaning:

- `group_wait`: wait before sending the first notification for a new group
- `group_interval`: wait before sending updates for an existing group
- `repeat_interval`: wait before repeating the same unresolved alert

In lab, these values are intentionally short.

Production values are usually longer, for example:

```yaml
group_wait: 30s
group_interval: 5m
repeat_interval: 1h
```

---

## Inhibition

Inhibition automatically suppresses secondary alerts when a more important source alert is firing.

Current inhibition rule:

```yaml
inhibit_rules:
  - source_matchers:
      - alertname="ApplicationServiceTargetDown"
      - severity="critical"
    target_matchers:
      - severity="warning"
    equal:
      - service
```

Meaning:

```text
If ApplicationServiceTargetDown with severity=critical is firing,
suppress warning alerts for the same service.
```

Silence is manual.

Inhibition is automatic.

### Source Alert

The source alert is the alert that suppresses others:

```text
ApplicationServiceTargetDown
severity=critical
service=application-service
```

### Target Alert

The target alert is the secondary alert that can be suppressed:

```text
ApplicationServiceHighLatency
severity=warning
service=application-service
```

or:

```text
ApplicationServiceNoApplicationsCreated
severity=warning
service=application-service
```

### Why `equal` Matters

The `equal` block prevents overly broad suppression.

Current config:

```yaml
equal:
  - service
```

This means:

```text
Suppress warning alerts only for the same service.
```

Without `equal`, one critical alert could suppress warnings for unrelated services.

---

## Routing

Alertmanager routes alerts to receivers based on labels.

Current route model:

```yaml
routes:
  - matchers:
      - severity="critical"
    receiver: "critical-receiver"

  - matchers:
      - category="business"
    receiver: "business-receiver"

  - matchers:
      - severity="warning"
    receiver: "warning-receiver"
```

Routing depends on labels from Prometheus alert rules.

Important labels:

```yaml
severity: critical
service: application-service
category: reliability
```

or:

```yaml
severity: warning
service: application-service
category: business
```

Good labels make routing predictable.

### Route Order

Route order matters.

Current order:

```text
1. severity="critical"
2. category="business"
3. severity="warning"
```

If an alert has:

```text
severity=warning
category=business
```

it matches both:

```text
category="business"
severity="warning"
```

Because `category="business"` appears first, the alert is routed to:

```text
business-receiver
```

instead of:

```text
warning-receiver
```

### Continue Behavior

By default, Alertmanager stops at the first matching child route.

If an alert must be sent to multiple receivers, use:

```yaml
continue: true
```

Example:

```yaml
- matchers:
    - category="business"
  receiver: "business-receiver"
  continue: true
```

In this lab, `continue: true` is not used.

---

## Troubleshooting: Alert Not Visible in Alertmanager

### 1. Check whether alert is firing in Prometheus

Open Prometheus:

```bash
kubectl port-forward service/prometheus 9090:9090 -n monitoring
```

Then check:

```text
http://localhost:9090/alerts
```

If alert is only `pending`, Alertmanager will not receive it yet.

### 2. Check Prometheus Alertmanager config

```bash
kubectl get pods -n monitoring -l app=prometheus
kubectl exec -it <prometheus-pod-name> -n monitoring -- cat /etc/prometheus/prometheus.yml
```

Look for:

```yaml
alerting:
  alertmanagers:
```

### 3. Check network connectivity

```bash
kubectl run curl-test \
  -n monitoring \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- curl -s http://alertmanager.monitoring.svc.cluster.local:9093/-/ready
```

Expected result:

```text
OK
```

### 4. Check Prometheus logs

```bash
kubectl logs -l app=prometheus -n monitoring --tail=100
```

### 5. Check Alertmanager logs

```bash
kubectl logs -l app=alertmanager -n monitoring --tail=100
```

---

## Troubleshooting: Alertmanager Pod Not Running

Check Pod status:

```bash
kubectl get pods -n monitoring -l app=alertmanager
```

Describe Pod:

```bash
kubectl describe pod <alertmanager-pod-name> -n monitoring
```

Check logs:

```bash
kubectl logs <alertmanager-pod-name> -n monitoring --tail=100
```

Check ConfigMap:

```bash
kubectl get configmap alertmanager-config -n monitoring -o yaml
```

Common causes:

- invalid Alertmanager YAML
- wrong indentation
- missing receiver
- invalid matcher syntax
- ConfigMap not mounted correctly
- image pull problem
- resource limits too low

---

## Troubleshooting: Silence Does Not Apply

Check active silences:

```bash
curl -s http://localhost:9093/api/v2/silences | python3 -m json.tool
```

Check active alerts:

```bash
curl -s http://localhost:9093/api/v2/alerts | python3 -m json.tool
```

Verify that silence matchers match alert labels exactly.

Example alert label:

```text
alertname="ApplicationServiceTargetDown"
```

Example silence matcher:

```text
alertname = ApplicationServiceTargetDown
```

Common causes:

- wrong label name
- wrong alertname
- silence expired
- silence created for too narrow matcher
- alert has different labels than expected

---

## Troubleshooting: Inhibition Does Not Apply

Check source alert is firing:

```text
ApplicationServiceTargetDown
severity=critical
```

Check target alert is firing:

```text
severity=warning
```

Check both alerts have the same service label:

```text
service=application-service
```

Check Alertmanager config:

```bash
kubectl exec -it <alertmanager-pod-name> -n monitoring -- cat /etc/alertmanager/alertmanager.yml
```

Expected inhibition config:

```yaml
inhibit_rules:
  - source_matchers:
      - alertname="ApplicationServiceTargetDown"
      - severity="critical"
    target_matchers:
      - severity="warning"
    equal:
      - service
```

Common causes:

- source alert is not firing
- target alert is not firing
- severity label mismatch
- service label mismatch
- wrong matcher syntax
- Alertmanager was not restarted after ConfigMap update

---

## Troubleshooting: Routing Does Not Work as Expected

Check alert labels in Alertmanager API:

```bash
curl -s http://localhost:9093/api/v2/alerts | python3 -m json.tool
```

Check route order in Alertmanager config:

```bash
kubectl exec -it <alertmanager-pod-name> -n monitoring -- cat /etc/alertmanager/alertmanager.yml
```

Important labels:

```text
severity
category
service
alertname
```

Common causes:

- route order is wrong
- label is missing from Prometheus alert rule
- matcher does not match exact label value
- receiver name is not defined
- Alertmanager config was not reloaded or Pod was not restarted

---

## Final Health Check After Tests

After any controlled failure, restore the lab to healthy state.

Check application-service:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Check Prometheus:

```bash
kubectl get pods -n monitoring -l app=prometheus
kubectl logs -l app=prometheus -n monitoring --tail=100
```

Check Alertmanager:

```bash
kubectl get pods -n monitoring -l app=alertmanager
kubectl logs -l app=alertmanager -n monitoring --tail=100
```

Check application-service scrape:

```promql
up{job="application-service-pods"}
```

Expected result:

```text
1
```

Check active Alertmanager alerts:

```bash
curl -s http://localhost:9093/api/v2/alerts | python3 -m json.tool
```

Expected result after full recovery:

```json
[]
```

---

## Operational Principles

Prometheus detects problems.

Alertmanager manages alert delivery.

Runbooks guide incident response.

A mature alerting flow should look like this:

```text
metric
  ↓
PromQL rule
  ↓
Prometheus firing alert
  ↓
Alertmanager route/group/silence/inhibit
  ↓
notification
  ↓
runbook
  ↓
incident response
```

Avoid alert fatigue by using:

- actionable alerts
- clear severity labels
- ownership labels
- grouping
- deduplication
- precise silences
- inhibition rules
- ownership-based routing

Good alerting is not only about detecting problems.

Good alerting is about sending the right signal to the right team at the right time with enough context to act.лг