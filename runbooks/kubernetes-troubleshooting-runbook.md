# Kubernetes Troubleshooting Runbook

## Purpose

This runbook describes how to diagnose common Kubernetes incidents for `application-service` in the local SRE observability lab.

It covers:

```text
ImagePullBackOff
CrashLoopBackOff
Readiness probe failure
Liveness probe failure
Service selector / Endpoints failure
ConfigMap / environment issue
OOMKilled
```

The goal is to diagnose incidents using:

```text
kubectl get
kubectl describe
kubectl logs
kubectl logs --previous
kubectl get events
kubectl rollout
kubectl get endpoints
Grafana
Prometheus
Loki
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

Application:

```text
application-service
```

Main Service:

```text
application-service
```

Expected normal state:

```text
Pod: Running 1/1
Deployment: READY 1/1
Service: exists
Endpoints: not empty
Application health: HTTP 200
```

---

## Baseline Health Check

Run:

```bash
kubectl config current-context
kubectl get nodes

kubectl get all -n sre-lab
kubectl get pods -n sre-lab -o wide
kubectl get deployment application-service -n sre-lab
kubectl get replicaset -n sre-lab
kubectl get service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp

kubectl get all -n monitoring
kubectl get daemonset -n monitoring
```

Expected:

```text
Node is Ready
application-service Pod is Running 1/1
Deployment is READY 1/1
Service exists
Endpoints contain Pod IP and port
Monitoring stack is running
```

---

## Core Diagnostic Model

Kubernetes traffic path:

```text
Deployment
  ↓ creates
ReplicaSet
  ↓ creates
Pod
  ↓ has labels
Service
  ↓ selects Pods by labels
Endpoints
  ↓ contain Pod IPs
Traffic reaches container
```

Important rules:

```text
Pod Running does not always mean Service works.
Service exists does not mean it has backends.
Service without Endpoints means traffic has nowhere to go.
Pod Running 0/1 means container is running but not Ready.
RestartCount > 0 means previous logs must be checked.
If container never started, application logs may not exist.
If application logs are empty, Kubernetes Events may contain the evidence.
```

---

## Minimum Kubernetes Incident Commands

Use this first during application incidents:

```bash
kubectl get pods -n sre-lab -o wide
kubectl get deployment application-service -n sre-lab
kubectl get replicaset -n sre-lab
kubectl get service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
kubectl logs -l app=application-service -n sre-lab --since=10m
```

If a Pod restarted:

```bash
kubectl logs <pod-name> -n sre-lab --previous
```

Describe the affected Pod:

```bash
kubectl describe pod <pod-name> -n sre-lab
```

---

## Pod Status Interpretation

```text
STATUS              First place to check
------------------------------------------------------------
Pending             describe pod + events
ContainerCreating   describe pod + events + mounts
ImagePullBackOff    describe pod + events + image name
ErrImagePull        describe pod + events + image name
CrashLoopBackOff    logs --previous + describe pod
Running 0/1         readiness probe + describe pod + endpoints
Running 1/1         service path, logs, metrics, dependencies
OOMKilled           describe pod + previous logs + resources
```

---

## ImagePullBackOff / ErrImagePull

### Meaning

```text
Kubernetes cannot pull the container image.
The container does not start.
Application logs may not exist.
```

### Common causes

```text
wrong image name
wrong image tag
image does not exist
registry unavailable
missing imagePullSecret
permission denied
```

### Diagnosis

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Look for:

```text
Failed to pull image
manifest unknown
pull access denied
Back-off pulling image
ErrImagePull
ImagePullBackOff
```

### Evidence

```text
Pod status is ImagePullBackOff or ErrImagePull.
Events show failed image pull.
describe pod shows the wrong image/tag.
```

### Fix

Rollback:

```bash
kubectl rollout undo deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Or re-apply the correct manifest:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

---

## CrashLoopBackOff

### Meaning

```text
Image was pulled successfully.
Container starts.
Process inside container exits with error.
Kubernetes restarts it repeatedly.
```

### Common causes

```text
bad application config
invalid environment variable
missing dependency
bad command or args
runtime startup error
failed DB/API connection
```

### Diagnosis

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --previous
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Look for:

```text
CrashLoopBackOff
Last State: Terminated
Reason: Error
Exit Code: 1
Back-off restarting failed container
```

### Evidence

```text
Pod status is CrashLoopBackOff.
RestartCount is increasing.
describe pod shows Last State Terminated.
logs --previous shows startup failure or runtime error.
```

### Fix

Remove bad config or rollback:

```bash
kubectl rollout undo deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Or apply corrected deployment:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

---

## Readiness Probe Failure

### Meaning

```text
Container is running, but Kubernetes does not consider it ready for traffic.
Pod may show Running but READY 0/1.
Service removes the Pod from Endpoints.
```

### Common causes

```text
wrong readiness path
health endpoint returns non-200
application still starting
dependency unavailable
probe timeout too aggressive
wrong port
```

### Diagnosis

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Look for:

```text
READY 0/1
Readiness probe failed
HTTP probe failed with statuscode
Endpoints: <none>
```

### Evidence

```text
Pod is Running but not Ready.
describe pod shows Readiness probe failed.
Events show failed readiness probe.
Service Endpoints do not include the not-ready Pod.
```

### Fix

Restore correct readiness probe path and port in:

```text
k8s/application-service/deployment.yaml
```

Then apply:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
curl -i http://localhost:8080/actuator/health
```

---

## Liveness Probe Failure

### Meaning

```text
Kubernetes thinks the container is unhealthy and restarts it.
RestartCount increases.
```

### Common causes

```text
wrong liveness path
wrong liveness port
probe timeout too aggressive
application startup longer than initialDelaySeconds
health endpoint temporarily unavailable
```

### Diagnosis

```bash
kubectl get pods -n sre-lab -w
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --previous
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Look for:

```text
Liveness probe failed
Killing container
RestartCount increasing
Container failed liveness probe, will be restarted
```

### Evidence

```text
RestartCount is increasing.
Events show Liveness probe failed.
Kubernetes is killing the container.
Application logs may not show a crash because Kubernetes caused the restart.
```

### Fix

Restore correct liveness probe path and port in:

```text
k8s/application-service/deployment.yaml
```

Then apply:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

---

## Service Selector / Endpoints Failure

### Meaning

```text
Pod is healthy, but Service does not route traffic to it.
Service selector does not match Pod labels.
Endpoints are empty.
```

### Common causes

```text
wrong Service selector
wrong Pod labels
Deployment template labels changed
targetPort mismatch
Pod not Ready
```

### Diagnosis

Check Pod labels:

```bash
kubectl get pods -n sre-lab --show-labels
```

Check Service selector:

```bash
kubectl describe service application-service -n sre-lab
```

Check Endpoints:

```bash
kubectl get endpoints application-service -n sre-lab
```

Check direct Pod access:

```bash
kubectl port-forward pod/<pod-name> 8081:8080 -n sre-lab
curl -i http://localhost:8081/actuator/health
```

Check Service access:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
curl -i http://localhost:8080/actuator/health
```

### Evidence

```text
Pod is Running 1/1.
Direct Pod port-forward returns HTTP 200.
Service Endpoints are empty.
Service selector does not match Pod label.
Service path fails.
```

### Fix

Restore Service selector in:

```text
k8s/application-service/service.yaml
```

Expected selector:

```yaml
selector:
  app: application-service
```

Apply:

```bash
kubectl apply -f k8s/application-service/service.yaml
```

Validate:

```bash
kubectl get endpoints application-service -n sre-lab
kubectl describe service application-service -n sre-lab
```

---

## ConfigMap / Environment Incident

### Meaning

```text
Application is running but uses unexpected configuration.
Infrastructure may look healthy, but behavior may be wrong.
```

### Common causes

```text
wrong ConfigMap value
wrong Secret value
wrong key name
ConfigMap changed but Pod was not restarted
wrong environment/profile
wrong feature flag
```

### Diagnosis

Check ConfigMaps and Secrets:

```bash
kubectl get configmap -n sre-lab
kubectl get secret -n sre-lab
```

Check ConfigMap:

```bash
kubectl get configmap application-service-config -n sre-lab -o yaml
```

Check Deployment env:

```bash
kubectl describe deployment application-service -n sre-lab | grep -A40 -i "Environment"
```

Check actual env inside Pod:

```bash
kubectl exec -it <pod-name> -n sre-lab -- env | sort | grep -E "APP|ENV|SPRING|JAVA"
```

Check application config endpoint:

```bash
curl -s http://localhost:8080/api/v1/config
```

### Important rule

```text
If ConfigMap is injected as environment variables, changing ConfigMap does not update already running Pods.
A rollout restart is required.
```

### Evidence

```text
ConfigMap contains unexpected value.
Pod env confirms unexpected value after restart.
Application /api/v1/config returns unexpected value.
Pod is still Running 1/1, so this is a configuration issue, not necessarily infrastructure downtime.
```

### Fix

Restore correct ConfigMap value:

```bash
kubectl apply -f k8s/application-service/configmap.yaml
kubectl rollout restart deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
curl -s http://localhost:8080/api/v1/config
```

---

## Secret Troubleshooting

Check Secret structure:

```bash
kubectl get secret application-service-secret -n sre-lab -o yaml
```

Decode one key only when necessary:

```bash
kubectl get secret application-service-secret -n sre-lab \
  -o jsonpath='{.data.<KEY_NAME>}' | base64 -d
```

Important production rule:

```text
Do not print secrets into screenshots, tickets, shared terminals, pull requests, or runbooks.
```

---

## OOMKilled

### Meaning

```text
Container exceeded its memory limit and was killed.
```

Typical signal:

```text
Last State: Terminated
Reason: OOMKilled
Exit Code: 137
RestartCount increasing
```

### Common causes

```text
memory limit too low
JVM heap too large for container limit
memory leak
large payload
expensive query
bad caching behavior
traffic spike
```

### Diagnosis

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --previous
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Check resources:

```bash
kubectl get deployment application-service -n sre-lab -o yaml | grep -A20 "resources:"
```

Look for:

```text
Reason: OOMKilled
Exit Code: 137
Restart Count
Limits
Requests
```

### Evidence

```text
describe pod shows Last State Terminated with Reason OOMKilled.
Exit Code is 137.
Memory limit is too low or memory usage exceeded the limit.
RestartCount is increasing.
```

### Fix

Restore realistic resources in:

```text
k8s/application-service/deployment.yaml
```

Example:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

Apply:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
curl -i http://localhost:8080/actuator/health
```

---

## Observability Correlation

Use Kubernetes and observability together.

### Prometheus

Check application target:

```promql
up{job="application-service-pods"}
```

Check service-level scrape:

```promql
up{job="application-service-service"}
```

Check latency:

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{job="application-service-pods"}[1m])
  )
)
```

Check error rate:

```promql
sum(rate(http_server_requests_seconds_count{job="application-service-pods", status=~"5.."}[1m]))
/
sum(rate(http_server_requests_seconds_count{job="application-service-pods"}[1m]))
```

### Loki

Check application logs:

```logql
{namespace="sre-lab", container="application-service"}
```

Check request logs:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

Check 5xx:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |~ "status=5.."
```

Check slow endpoint:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/failure/slow"
```

### Important interpretation

```text
If container never starts, Loki may not have application logs.
If Service selector is broken, application logs may be empty because traffic never reaches the Pod.
If liveness probe kills the app, application logs may look normal.
If OOMKilled occurs, previous logs may be incomplete because process was killed externally.
```

---

## Incident Diagnosis Templates

### ImagePullBackOff

```text
Symptom:
Deployment rollout is not progressing.

Kubernetes status:
New Pod is in ImagePullBackOff / ErrImagePull.

Evidence:
kubectl describe pod shows Failed to pull image.
Events show Back-off pulling image.

Root cause:
Deployment references a non-existing or inaccessible image.

Impact:
If old Pod is still Ready, traffic may continue.
If no Ready Pod exists, Service has no healthy backend.

Fix:
Rollback or set a valid image.
```

### CrashLoopBackOff

```text
Symptom:
Pod restarts repeatedly.

Kubernetes status:
Pod is in CrashLoopBackOff.

Evidence:
describe pod shows Last State Terminated.
logs --previous shows startup/runtime error.

Root cause:
Application process exits after start due to invalid runtime/configuration/dependency issue.

Impact:
Rollout blocked or service unavailable if no Ready Pods exist.

Fix:
Correct the runtime/configuration issue or rollback.
```

### Readiness Failure

```text
Symptom:
Pod is Running but not Ready.

Kubernetes status:
READY 0/1.

Evidence:
describe pod shows Readiness probe failed.
Endpoints do not include the not-ready Pod.

Root cause:
Readiness probe points to an invalid/unhealthy endpoint.

Impact:
Service will not send traffic to the Pod. If no Ready Pods exist, application is unreachable.

Fix:
Restore valid readiness probe.
```

### Liveness Failure

```text
Symptom:
Pod restarts repeatedly.

Kubernetes status:
RestartCount increases.

Evidence:
Events show Liveness probe failed and Kubernetes killing container.

Root cause:
Liveness probe is misconfigured or too aggressive.

Impact:
Application may flap or become unavailable due to repeated restarts.

Fix:
Restore valid liveness probe and tune timing.
```

### Service Selector Failure

```text
Symptom:
Application is not reachable through Service.

Kubernetes status:
Pod is Running 1/1 but Endpoints are empty.

Evidence:
Service selector does not match Pod labels.
Direct Pod access works.
Service access fails.

Root cause:
Service selector is incorrect.

Impact:
Application is healthy but unreachable through Kubernetes Service.

Fix:
Restore Service selector to match Pod labels.
```

### ConfigMap Incident

```text
Symptom:
Application uses unexpected configuration.

Kubernetes status:
Pod is Running and Ready.

Evidence:
ConfigMap contains wrong value.
Pod env confirms wrong value.
Application config endpoint returns wrong value.

Root cause:
Wrong configuration value was applied.

Impact:
Application may behave incorrectly without infrastructure downtime.

Fix:
Restore ConfigMap and restart Deployment if env injection is used.
```

### OOMKilled

```text
Symptom:
Pod restarts or rollout fails.

Kubernetes status:
Container terminated with OOMKilled.

Evidence:
describe pod shows Reason OOMKilled and Exit Code 137.
Memory limit is too low or memory usage exceeded limit.

Root cause:
Container exceeded memory limit.

Impact:
Application instability or downtime.

Fix:
Increase memory resources and investigate memory usage.
```

---

## Final Validation

After any incident recovery, run:

```bash
kubectl get pods -n sre-lab
kubectl get deployment application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Check health:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
curl -i http://localhost:8080/actuator/health
```

Check Grafana dashboard:

```text
Application Service Overview
```

Check Loki logs:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

Check Prometheus:

```promql
up{job="application-service-pods"}
```

Expected final state:

```text
Pod Running 1/1
Deployment READY 1/1
Endpoints not empty
Health endpoint returns HTTP 200
Prometheus target up = 1
No unexpected firing alerts
```

---

## Key Lessons

```text
1. Kubernetes Events are critical for infrastructure-level issues.
2. Application logs are not always available or sufficient.
3. describe pod often gives the root evidence.
4. Service without Endpoints means traffic has nowhere to go.
5. Running 0/1 is usually readiness-related.
6. RestartCount growth requires previous logs.
7. ImagePullBackOff is not an application problem.
8. CrashLoopBackOff is usually application/runtime/config related.
9. Liveness misconfiguration can create artificial outages.
10. ConfigMap changes injected as env require Pod restart.
11. OOMKilled usually requires resource tuning and memory investigation.
12. Metrics show symptoms; Kubernetes state explains platform causes; logs provide application evidence.
```