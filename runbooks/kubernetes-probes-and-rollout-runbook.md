# Kubernetes Probes and Rollout Runbook

## Purpose

This runbook describes production-like Kubernetes probes and safe rollout behavior for `application-service`.

It covers:

```text
startupProbe
readinessProbe
livenessProbe
RollingUpdate
zero-downtime rollout
preStop hook
terminationGracePeriodSeconds
Spring Boot graceful shutdown
Prometheus/Grafana/Loki validation
```

---

## Environment

Application namespace:

```text
sre-lab
```

Application:

```text
application-service
```

Expected production-like baseline:

```text
replicas: 2
strategy: RollingUpdate
maxUnavailable: 0
maxSurge: 1
startupProbe configured
readinessProbe configured
livenessProbe configured
preStop hook configured
terminationGracePeriodSeconds configured
Spring Boot graceful shutdown enabled
```

---

## Probe Concepts

### startupProbe

`startupProbe` answers:

```text
Has the application started successfully?
```

It protects slow-starting applications from being killed too early by liveness checks.

Useful for:

```text
Spring Boot applications
Java applications
applications with cache warmup
applications with migrations
applications with slow dependency initialization
```

### readinessProbe

`readinessProbe` answers:

```text
Can this Pod receive traffic now?
```

If readiness fails:

```text
Pod stays Running
container is not restarted
Pod is removed from Service Endpoints
traffic is not sent to this Pod
```

### livenessProbe

`livenessProbe` answers:

```text
Should Kubernetes restart this container?
```

If liveness fails:

```text
Kubernetes kills and restarts the container
RestartCount increases
```

Important rule:

```text
If restart will not fix the problem, do not put that condition into liveness.
```

---

## Recommended Probe Model

For Spring Boot Actuator:

```text
startupProbe   -> /actuator/health
livenessProbe  -> /actuator/health/liveness
readinessProbe -> /actuator/health/readiness
```

Do not use the full dependency-heavy `/actuator/health` as liveness unless this is intentional.

Bad liveness dependencies:

```text
database
Kafka
Redis
external API
partner system
payment provider
government service
```

A dependency outage should usually not cause Kubernetes to restart an otherwise healthy application process.

---

## Spring Boot Actuator Probe Endpoints

Expected endpoints:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
```

Check locally:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness
```

Expected:

```text
/actuator/health            returns overall health
/actuator/health/liveness   returns UP
/actuator/health/readiness  returns UP
```

---

## Kubernetes Deployment Baseline

Expected Deployment strategy:

```yaml
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
```

Meaning:

```text
replicas: 2
Two Pods are normally available.

maxUnavailable: 0
Kubernetes should not reduce available Pods below desired replica count during rollout.

maxSurge: 1
Kubernetes may create one extra Pod during rollout.
```

This supports safer rolling updates.

---

## Expected Probe Configuration

Expected probe configuration inside the application container:

```yaml
startupProbe:
  httpGet:
    path: /actuator/health
    port: http
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 2
  failureThreshold: 24

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  initialDelaySeconds: 0
  periodSeconds: 10
  timeoutSeconds: 2
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  initialDelaySeconds: 0
  periodSeconds: 5
  timeoutSeconds: 2
  failureThreshold: 3
```

Interpretation:

```text
startupProbe allows the app around 130 seconds to start.
livenessProbe restarts the container only after repeated failures.
readinessProbe removes the Pod from Endpoints after repeated readiness failures.
```

---

## Graceful Termination Baseline

Expected Pod-level setting:

```yaml
terminationGracePeriodSeconds: 30
```

Expected container lifecycle hook:

```yaml
lifecycle:
  preStop:
    exec:
      command:
        - sh
        - -c
        - sleep 10
```

Meaning:

```text
Kubernetes starts Pod termination.
Pod is removed from Endpoints.
preStop waits for 10 seconds.
Application receives SIGTERM.
Spring Boot gracefully shuts down.
Kubernetes allows up to 30 seconds before SIGKILL.
```

---

## Spring Boot Graceful Shutdown

Expected application config:

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
```

Important rule:

```text
Spring graceful shutdown timeout should be lower than Kubernetes terminationGracePeriodSeconds.
```

Correct:

```text
Spring timeout: 20s
Kubernetes termination grace period: 30s
```

Bad:

```text
Spring timeout: 60s
Kubernetes termination grace period: 30s
```

In the bad case Kubernetes may send SIGKILL before Spring finishes graceful shutdown.

---

## Apply Deployment Changes

Apply the Deployment:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

Check Pods:

```bash
kubectl get pods -n sre-lab -o wide
```

Check Deployment:

```bash
kubectl get deployment application-service -n sre-lab
```

Check Endpoints:

```bash
kubectl get endpoints application-service -n sre-lab
```

Expected:

```text
Deployment READY 2/2
2 Pods Running 1/1
Endpoints contain 2 Pod IPs
```

---

## Verify Probe Configuration in Running Pod

Get Pod name:

```bash
kubectl get pods -n sre-lab
```

Describe Pod:

```bash
kubectl describe pod <pod-name> -n sre-lab
```

Check for:

```text
Startup
Liveness
Readiness
PreStop
```

Expected paths:

```text
Startup:   /actuator/health
Liveness:  /actuator/health/liveness
Readiness: /actuator/health/readiness
```

---

## Zero-Downtime Rollout Test

Port-forward Service:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

In another terminal, send continuous traffic:

```bash
while true; do
  date +"%H:%M:%S"
  curl -s -o /dev/null -w "status=%{http_code} time=%{time_total}\n" http://localhost:8080/api/v1/applications/443
  sleep 1
done
```

In another terminal, restart Deployment:

```bash
kubectl rollout restart deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Optional watch:

```bash
kubectl get pods -n sre-lab -w
```

Optional Endpoints watch:

```bash
watch -n 1 "kubectl get endpoints application-service -n sre-lab"
```

Expected:

```text
curl continues returning status=200
Deployment returns to READY 2/2
Endpoints do not become empty
new Pods become Ready before receiving traffic
```

---

## Prometheus Validation

Open Prometheus:

```bash
kubectl port-forward service/prometheus 9090:9090 -n monitoring
```

Check Pod scrape:

```promql
up{job="application-service-pods"}
```

Expected:

```text
2 series
both equal 1
```

Check Service scrape:

```promql
up{job="application-service-service"}
```

Expected:

```text
1
```

Interpretation:

```text
application-service-pods shows per-Pod availability.
application-service-service shows Service-level scrape path.
```

---

## Grafana Validation

Open Grafana:

```bash
kubectl port-forward service/grafana 3000:3000 -n monitoring
```

Dashboard:

```text
Application Service Overview
```

Check:

```text
Application Service UP
HTTP Request Rate
HTTP 5xx Error Rate
HTTP p95 Latency
Business Applications Created
Active Firing Alerts
Application Request Logs
Application 5xx Logs
Slow Request Logs
```

After changing to 2 replicas, dashboard queries should aggregate across Pods where needed.

Use `sum(rate(...))` patterns instead of looking at only one Pod.

---

## Loki Validation During Rollout

Generate traffic:

```bash
while true; do
  date +"%H:%M:%S"
  curl -s -o /dev/null -w "status=%{http_code} time=%{time_total}\n" http://localhost:8080/api/v1/applications/443
  sleep 1
done
```

Run rollout restart:

```bash
kubectl rollout restart deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Check logs in Grafana Explore:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/applications/443"
```

Expected:

```text
request logs continue appearing during rollout
status=200
requestId present
durationMs present
```

---

## Deployment Strategy Types

Native Kubernetes Deployment supports:

```text
RollingUpdate
Recreate
```

### RollingUpdate

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

Use for:

```text
most stateless backend services
production APIs
services where downtime should be avoided
```

### Recreate

```yaml
strategy:
  type: Recreate
```

Use for:

```text
legacy apps
apps that cannot run two versions at the same time
non-critical internal tools
special migration cases
```

Downside:

```text
old Pods are deleted before new Pods become available
downtime is expected
```

---

## Wider Release Patterns

These are not native `Deployment.spec.strategy.type`, but common production release patterns:

```text
Blue-Green
Canary
A/B Testing
Shadow / Mirror Traffic
Feature Flags
```

### Blue-Green

```text
Blue = current production version
Green = new version
Service/Ingress switches from Blue to Green
```

Pros:

```text
fast rollback
new version can be validated before switch
no mixed versions
```

Cons:

```text
requires more resources
requires careful database compatibility
```

### Canary

```text
small percentage of traffic goes to new version
metrics are monitored
traffic gradually increases
```

Useful for reducing blast radius.

Usually requires:

```text
Ingress controller
service mesh
Argo Rollouts
Flagger
progressive delivery tooling
```

### Shadow / Mirror Traffic

```text
real requests are copied to new version
user response still comes from stable version
```

Useful for:

```text
performance testing
compatibility testing
safe validation with real traffic
```

Risk:

```text
shadow version must not create real side effects
```

---

## Troubleshooting Rollout Downtime

If downtime happens during rollout, check:

```bash
kubectl get pods -n sre-lab
kubectl get deployment application-service -n sre-lab
kubectl describe deployment application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Common causes:

```text
replicas is 1
maxUnavailable allows too many Pods to be unavailable
readinessProbe is missing
readinessProbe is too optimistic
startupProbe is missing for slow app
livenessProbe kills app during startup
terminationGracePeriodSeconds is too low
application does not handle SIGTERM gracefully
preStop hook is missing
Ingress/Load Balancer draining is not configured
```

---

## Production Notes

This lab now has a good baseline:

```text
replicas: 2
RollingUpdate
maxUnavailable: 0
maxSurge: 1
startupProbe
livenessProbe
readinessProbe
preStop
terminationGracePeriodSeconds
Spring Boot graceful shutdown
Prometheus validation
Grafana validation
Loki validation
```

Further production improvements:

```text
PodDisruptionBudget
HorizontalPodAutoscaler
kube-state-metrics
blackbox monitoring
Ingress readiness checks
Load Balancer connection draining
canary rollout
blue-green rollout
Argo Rollouts
resource-based autoscaling
SLO-based alerting
```

---

## Final Validation Checklist

Run:

```bash
kubectl get pods -n sre-lab -o wide
kubectl get deployment application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Expected:

```text
2 Pods Running 1/1
Deployment READY 2/2
Endpoints contain 2 addresses
```

Check health:

```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness
```

Expected:

```text
HTTP 200
UP
UP
```

Check Prometheus:

```promql
up{job="application-service-pods"}
```

Expected:

```text
two series, both 1
```

Check Loki:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

Expected:

```text
request logs are visible
```

---

## Key Lessons

```text
1. startupProbe protects slow application startup.
2. readinessProbe controls traffic routing through Endpoints.
3. livenessProbe restarts the container and must be conservative.
4. Do not put unstable external dependencies into liveness.
5. RollingUpdate with maxUnavailable: 0 and maxSurge: 1 improves rollout safety.
6. replicas: 2 is a practical minimum for safer rollout behavior.
7. terminationGracePeriodSeconds gives the app time to stop.
8. preStop helps reduce traffic during termination.
9. Spring Boot graceful shutdown must fit inside Kubernetes termination grace period.
10. Zero-downtime rollout depends on Kubernetes, application behavior, and traffic routing.
```