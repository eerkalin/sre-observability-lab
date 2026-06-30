# Kubernetes Rollout and Release Runbook

## Purpose

This runbook describes production-like rollout and release management for `application-service`.

It covers:

```text
rollout status
rollout history
rollout undo
failed rollout diagnosis
pause/resume rollout
image tag discipline
mutable vs immutable tags
ConfigMap drift
Secret rotation
release checklist
observability validation
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

Expected baseline:

```text
replicas: 2
strategy: RollingUpdate
maxUnavailable: 0
maxSurge: 1
image: application-service:0.1.0-local
release.version: 0.1.0-local
startupProbe configured
readinessProbe configured
livenessProbe configured
preStop configured
terminationGracePeriodSeconds configured
Spring Boot graceful shutdown enabled
```

---

## Rollout Concept

A Kubernetes Deployment rollout is a transition from one Pod template revision to another.

Changing the following usually creates a new rollout revision:

```text
image
env
resources
probes
labels
annotations
container command/args
volume mounts
ConfigMap/Secret references
```

Deployment creates a new ReplicaSet for the new Pod template and gradually scales down the old ReplicaSet.

```text
Deployment
  ↓ new Pod template
New ReplicaSet
  ↓ creates new Pods
Old ReplicaSet
  ↓ scales down old Pods
```

Important:

```text
Deployment rollout history tracks Pod template revisions.
It does not automatically version or rollback every related object.
```

---

## Baseline Health Check

```bash
kubectl get deployment application-service -n sre-lab
kubectl get pods -n sre-lab -o wide
kubectl get replicaset -n sre-lab
kubectl get service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Expected:

```text
Deployment READY 2/2
2 Pods Running 1/1
Service exists
Endpoints contain 2 Pod IPs
```

---

## Rollout Status

Check current rollout:

```bash
kubectl rollout status deployment/application-service -n sre-lab
```

Expected successful result:

```text
deployment "application-service" successfully rolled out
```

If it waits indefinitely, investigate:

```text
ImagePullBackOff
CrashLoopBackOff
Readiness probe failure
OOMKilled
resource pressure
invalid config
```

---

## Rollout History

Check rollout history:

```bash
kubectl rollout history deployment/application-service -n sre-lab
```

Check specific revision:

```bash
kubectl rollout history deployment/application-service -n sre-lab --revision=<revision>
```

Check ReplicaSets:

```bash
kubectl get replicaset -n sre-lab
```

Describe Deployment:

```bash
kubectl describe deployment application-service -n sre-lab
```

Useful fields:

```text
StrategyType
RollingUpdateStrategy
Replicas
OldReplicaSets
NewReplicaSet
Events
```

---

## Rollout Undo

Rollback to previous revision:

```bash
kubectl rollout undo deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Rollback to a specific revision:

```bash
kubectl rollout undo deployment/application-service -n sre-lab --to-revision=<revision>
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get deployment application-service -n sre-lab
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Important:

```text
rollout undo rolls back the Deployment Pod template.
It does not automatically rollback ConfigMap data, Secret data, database migrations, feature flags, external config, or data changes.
```

---

## What Rollout Undo Does Not Roll Back

`kubectl rollout undo` does not rollback:

```text
ConfigMap content
Secret content
database schema migration
external API state
feature flag state outside Deployment
data changes
Kafka messages
Redis/cache changes
Ingress objects if changed separately
```

Senior-level rule:

```text
Rollback Deployment does not mean rollback the whole release.
```

Production rollback must consider the whole release unit:

```text
image
Deployment template
ConfigMap
Secret
database migration
feature flags
Ingress/routing
external config
Kafka schema
cache state
```

---

## Failed Rollout Diagnosis

### Symptom

```text
Deployment rollout does not complete.
```

### Example status

```bash
kubectl rollout status deployment/application-service -n sre-lab
```

May wait indefinitely.

### Diagnosis commands

```bash
kubectl get deployment application-service -n sre-lab
kubectl get replicaset -n sre-lab
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Describe broken Pod:

```bash
kubectl describe pod <broken-pod-name> -n sre-lab
```

### Common evidence

```text
ImagePullBackOff
ErrImagePull
CrashLoopBackOff
Readiness probe failed
OOMKilled
Back-off restarting failed container
Failed to pull image
```

### Failed image rollout example

Evidence:

```text
kubectl rollout status waits indefinitely.
kubectl get pods shows new Pod in ImagePullBackOff.
kubectl describe pod shows Failed to pull image application-service:broken-rollout.
Events show Back-off pulling image.
Old ReplicaSet remains available.
```

Root cause:

```text
Deployment was updated with a non-existing image tag.
```

Impact:

```text
No immediate customer impact if old Pods remain Ready due to RollingUpdate with maxUnavailable=0.
Release is blocked.
```

Fix:

```bash
kubectl rollout undo deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

---

## Safe RollingUpdate Strategy

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

This helps prevent customer impact during failed rollout.

---

## Pause and Resume Rollout

Pause Deployment:

```bash
kubectl rollout pause deployment/application-service -n sre-lab
```

Check paused state:

```bash
kubectl get deployment application-service -n sre-lab -o yaml | grep -i paused
```

Expected:

```yaml
paused: true
```

Make Pod template changes while paused:

```bash
kubectl patch deployment application-service -n sre-lab \
  -p '{"spec":{"template":{"metadata":{"annotations":{"release.test/pause-demo":"true"}}}}}'
```

Resume rollout:

```bash
kubectl rollout resume deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get pods -n sre-lab
kubectl get replicaset -n sre-lab
kubectl rollout history deployment/application-service -n sre-lab
```

Use cases:

```text
prepare several Pod template changes
release them as one rollout
avoid multiple intermediate revisions
manual/emergency release control
```

Production note:

```text
In GitOps/CI/CD environments, prefer changing Git and letting the delivery system reconcile.
Manual kubectl patch can create drift.
```

---

## Image Tag Discipline

Bad image tag:

```yaml
image: application-service:latest
```

Why `latest` is dangerous:

```text
does not identify code version
does not identify Git commit
can be overwritten
makes rollback unreliable
Git manifest may not change while running code changes
```

Better:

```yaml
image: application-service:1.0.0
```

Better with Git SHA:

```yaml
image: application-service:1.0.0-git-a1b2c3d
```

Most reproducible:

```yaml
image: registry.example.com/team/application-service@sha256:<digest>
```

Lab image tag:

```yaml
image: application-service:0.1.0-local
```

---

## Mutable vs Immutable Tags

Mutable tag:

```text
same tag can point to different image content over time
```

Example:

```text
application-service:latest
```

Immutable tag:

```text
tag is never reused for different image content
```

Examples:

```text
application-service:0.1.0
application-service:0.1.0-local
application-service:1.3.7-git-a1b2c3d
application-service:2026-06-30-001
```

Important rollback problem:

```text
If Revision 10 and Revision 11 both use application-service:latest,
rollback may still run the new broken image if latest was overwritten.
```

---

## Build and Load Versioned Local Image

Build:

```bash
docker build -t application-service:0.1.0-local app/application-service
```

Load into kind:

```bash
kind load docker-image application-service:0.1.0-local --name sre-lab
```

Apply Deployment:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

Check Deployment image:

```bash
kubectl get deployment application-service -n sre-lab \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

Expected:

```text
application-service:0.1.0-local
```

Check Pod images:

```bash
kubectl get pods -n sre-lab \
  -o jsonpath='{range .items[*]}{.metadata.name}{" -> "}{.spec.containers[0].image}{"\n"}{end}'
```

---

## Release Annotations

Expected Pod template annotations:

```yaml
template:
  metadata:
    annotations:
      release.version: "0.1.0-local"
      release.description: "Production-like probes and rollout baseline"
```

Check release version:

```bash
kubectl get deployment application-service -n sre-lab \
  -o jsonpath='{.spec.template.metadata.annotations.release\.version}{"\n"}'
```

Expected:

```text
0.1.0-local
```

Check release description:

```bash
kubectl get deployment application-service -n sre-lab \
  -o jsonpath='{.spec.template.metadata.annotations.release\.description}{"\n"}'
```

Purpose:

```text
make running release easier to identify
connect Deployment template to release context
force a new rollout revision when needed
```

---

## ConfigMap Drift

ConfigMap is a separate Kubernetes object.

Changing ConfigMap data does not necessarily create a Deployment revision.

If ConfigMap is injected as environment variables:

```text
running Pods do not automatically receive new values
rollout restart is required
```

### Check ConfigMap

```bash
kubectl get configmap application-service-config -n sre-lab -o yaml
```

### Check application config

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

```bash
curl -s http://localhost:8080/api/v1/config
```

### Apply ConfigMap change

```bash
kubectl apply -f k8s/application-service/configmap.yaml
```

### Restart Pods to pick up env changes

```bash
kubectl rollout restart deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

### Important drift rule

```text
rollout undo does not automatically restore ConfigMap object data.
```

If bad config was applied, restore the ConfigMap from Git:

```bash
kubectl apply -f k8s/application-service/configmap.yaml
kubectl rollout restart deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

---

## Secret Rotation

Secret is used for sensitive values:

```text
password
token
API key
private key
database credential
client secret
```

Important:

```text
Kubernetes Secret values in YAML are base64-encoded by default, not inherently safe to expose.
Do not print real secret values in screenshots, tickets, chats, logs, or runbooks.
```

### Check Secret exists

```bash
kubectl get secret application-service-secret -n sre-lab
```

### Check Secret structure without decoding

```bash
kubectl get secret application-service-secret -n sre-lab -o yaml
```

### Check keys only

```bash
kubectl get secret application-service-secret -n sre-lab -o jsonpath='{.data}'
```

### Check Secret env names inside Pod

```bash
kubectl get pods -n sre-lab
```

```bash
kubectl exec -it <pod-name> -n sre-lab -- sh -c 'env | sort | grep -E "SECRET|PASSWORD|TOKEN|OWNER|APP" | cut -d= -f1'
```

### Rotate Secret in lab

Edit:

```text
k8s/application-service/secret.yaml
```

Apply:

```bash
kubectl apply -f k8s/application-service/secret.yaml
```

Restart Deployment if Secret is injected as env:

```bash
kubectl rollout restart deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Validate:

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Production Secret rotation flow:

```text
1. Create or update Secret.
2. Ensure application can accept new credential.
3. Restart Pods or use reload mechanism.
4. Validate readiness and health.
5. Validate metrics and logs.
6. Revoke old credential only after confirmation.
```

---

## Final Release Validation

### Kubernetes state

```bash
kubectl get deployment application-service -n sre-lab
kubectl get pods -n sre-lab -o wide
kubectl get replicaset -n sre-lab
kubectl get service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Expected:

```text
Deployment READY 2/2
2 Pods Running 1/1
Endpoints contain 2 Pod IPs
```

### Image and release annotations

```bash
kubectl get deployment application-service -n sre-lab \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

```bash
kubectl get deployment application-service -n sre-lab \
  -o jsonpath='{.spec.template.metadata.annotations.release\.version}{"\n"}'
```

Expected:

```text
application-service:0.1.0-local
0.1.0-local
```

### Strategy

```bash
kubectl get deployment application-service -n sre-lab -o yaml | grep -A10 "strategy:"
```

Expected:

```yaml
strategy:
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
  type: RollingUpdate
```

### Probes and graceful shutdown

```bash
kubectl describe pod <pod-name> -n sre-lab
```

Check:

```text
Startup
Liveness
Readiness
PreStop
```

Check termination grace period:

```bash
kubectl get pod <pod-name> -n sre-lab \
  -o jsonpath='{.spec.terminationGracePeriodSeconds}{"\n"}'
```

Expected:

```text
30
```

### Health endpoints

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

---

## Rollout Under Traffic Test

Port-forward Service:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Generate continuous traffic:

```bash
while true; do
  date +"%H:%M:%S"
  curl -s -o /dev/null -w "status=%{http_code} time=%{time_total}\n" http://localhost:8080/api/v1/applications/443
  sleep 1
done
```

Restart Deployment:

```bash
kubectl rollout restart deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Expected:

```text
status=200 during rollout
Deployment returns to READY 2/2
Endpoints do not become empty
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
2 series, both equal 1
```

Check Service scrape:

```promql
up{job="application-service-service"}
```

Expected:

```text
1
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

---

## Loki Validation

Grafana Explore:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

Specific endpoint:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request" |= "/api/v1/applications/443"
```

Expected:

```text
request logs are visible
status=200
durationMs present
requestId present
```

---

## Release Checklist

Before production deployment, check:

```text
1. Image tag is immutable and meaningful.
2. Release version annotation exists.
3. Deployment strategy is safe.
4. replicas >= 2 for production workloads.
5. startupProbe is configured.
6. readinessProbe is configured.
7. livenessProbe is configured and conservative.
8. graceful shutdown is configured.
9. ConfigMap values are reviewed.
10. Secret changes are reviewed.
11. Secret rotation plan exists if credentials changed.
12. rollout status is checked.
13. Endpoints are not empty.
14. Prometheus targets are up.
15. Grafana dashboard is healthy.
16. Loki logs are visible.
17. Rollback plan exists.
18. ConfigMap/Secret/database rollback impact is understood.
19. Feature flag state is known.
20. Release owner and change reason are clear.
```

---

## Senior-Level Lessons

```text
1. Rollout is a transition between Pod template revisions.
2. ReplicaSet represents a Deployment template version.
3. rollout undo does not rollback the whole release.
4. ConfigMap and Secret are separate release objects.
5. Mutable image tags make rollback unreliable.
6. latest is dangerous for production.
7. Failed rollout does not always mean customer impact if old Pods remain Ready.
8. maxUnavailable: 0 protects availability during rollout.
9. pause/resume can help control manual releases.
10. Manual kubectl patch can create drift from Git.
11. Production release requires image, config, secret, probes, strategy, and observability validation.
```