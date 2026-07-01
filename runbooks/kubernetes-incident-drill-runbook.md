# Kubernetes Incident Drill Runbook

## Purpose

This runbook summarizes Kubernetes incident drills for `application-service`.

It covers:

```text
Service selector failure
wrong targetPort
readiness failure
OOMKilled
FailedScheduling / Pending Pod
DNS namespace issue
incident evidence collection
mini-RCA structure
final validation
```

The goal is to diagnose incidents by evidence, not by guessing.

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

Monitoring namespace:

```text
monitoring
```

Expected Service DNS:

```text
application-service.sre-lab.svc.cluster.local
```

Expected application port:

```text
8080
```

---

## Incident Diagnosis Principle

Use this order:

```text
1. Status
2. Events
3. Logs
4. Metrics
5. Config diff
6. Fix
7. Validation
8. RCA
```

Do not start with assumptions like:

```text
network is broken
Kubernetes is broken
Prometheus is broken
application is dead
```

Start with evidence.

---

## Baseline Health Check

Run before and after every incident drill:

```bash
kubectl get pods -n sre-lab -o wide
kubectl get deployment application-service -n sre-lab
kubectl get service application-service -n sre-lab
kubectl describe service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get endpointslice -n sre-lab -l kubernetes.io/service-name=application-service
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Expected healthy state:

```text
Deployment READY 2/2
Pods Running 1/1
Service exists
Service selector app=application-service
Endpoints contain two PodIP:8080 entries
EndpointSlice exists
```

---

## Debug Pod

Use a temporary curl Pod inside the application namespace:

```bash
kubectl run curl-test \
  -n sre-lab \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- sh
```

Inside:

```sh
curl -i http://application-service:8080/actuator/health
curl -i http://application-service:8080/api/v1/applications/443
curl -i http://application-service.sre-lab.svc.cluster.local:8080/actuator/health
```

Expected:

```text
HTTP 200
```

---

## Local Port-forward Check

Run:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Then from another terminal:

```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/api/v1/applications/443
```

Important:

```text
port-forward is a local debugging tunnel.
It is not the same as in-cluster Service networking.
```

---

## Observability Checks

Prometheus:

```bash
kubectl port-forward service/prometheus 9090:9090 -n monitoring
```

Useful PromQL:

```promql
up{job="application-service-pods"}
```

```promql
sum(rate(http_server_requests_seconds_count{job="application-service-pods", uri!~"/actuator.*"}[1m]))
```

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{job="application-service-pods", uri!~"/actuator.*"}[1m])
  )
)
```

Loki:

```logql
{namespace="sre-lab", container="application-service"} |= "http_request"
```

---

## Mini-RCA Template

Use this structure for each incident:

```text
Incident:
Impact:
Detection:
Evidence:
Root cause:
Fix:
Prevention:
```

Example:

```text
Incident:
Requests to application-service through Kubernetes Service failed.

Impact:
Application was not reachable through Service DNS.

Detection:
curl from debug Pod to application-service failed.

Evidence:
kubectl get endpoints application-service showed <none>.
kubectl describe service showed selector app=wrong-application-service.
kubectl get pods --show-labels showed app=application-service.

Root cause:
Service selector did not match Pod labels.

Fix:
Restored Service selector to app=application-service.

Prevention:
Validate Service selectors and Endpoints after manifest changes.
```

---

# Drill 1: Broken Service Selector

## Failure

Service selector was changed from:

```yaml
selector:
  app: application-service
```

to:

```yaml
selector:
  app: wrong-application-service
```

## Symptoms

```text
Pods Running 1/1
Deployment Ready
Service exists
DNS may resolve
Endpoints are empty
Requests through Service fail
```

## Evidence

```bash
kubectl get endpoints application-service -n sre-lab
kubectl describe service application-service -n sre-lab
kubectl get pods -n sre-lab --show-labels
```

Expected evidence:

```text
Endpoints: <none>
Service selector: app=wrong-application-service
Pod labels: app=application-service
```

## Root Cause

```text
Service selector did not match Pod labels, so Kubernetes did not create endpoints for application-service.
```

## Fix

Restore:

```yaml
selector:
  app: application-service
```

Apply:

```bash
kubectl apply -f k8s/application-service/service.yaml
```

## Validation

```bash
kubectl get endpoints application-service -n sre-lab
kubectl describe service application-service -n sre-lab
```

From debug Pod:

```sh
curl -i http://application-service:8080/actuator/health
```

Expected:

```text
HTTP 200
```

## Mini-RCA

```text
Incident:
Requests to application-service through Kubernetes Service failed.

Impact:
Application was not reachable through Service DNS.

Detection:
curl from debug Pod to application-service failed.

Evidence:
kubectl get endpoints application-service showed <none>.
kubectl describe service showed selector app=wrong-application-service.
kubectl get pods --show-labels showed app=application-service.

Root cause:
Service selector did not match Pod labels.

Fix:
Restored Service selector to app=application-service.

Prevention:
Validate Service selectors and Endpoints after Service manifest changes.
```

---

# Drill 2: Wrong targetPort

## Failure

Service targetPort was changed from:

```yaml
targetPort: http
```

to:

```yaml
targetPort: 9999
```

## Symptoms

```text
Pods Running 1/1
Deployment Ready
Service exists
Endpoints exist
Requests through Service fail
```

## Evidence

```bash
kubectl describe service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get pods -n sre-lab -o wide
```

Expected evidence:

```text
TargetPort: 9999
Endpoints: PodIP:9999
```

Direct Pod IP check from debug Pod:

```sh
curl -i http://<POD-IP>:8080/actuator/health
curl -i --max-time 5 http://<POD-IP>:9999/actuator/health
```

Expected:

```text
PodIP:8080 works.
PodIP:9999 fails.
```

## Root Cause

```text
Service targetPort was set to 9999 while application-service container listens on port 8080.
```

## Fix

Restore:

```yaml
ports:
  - name: http
    port: 8080
    targetPort: http
```

Apply:

```bash
kubectl apply -f k8s/application-service/service.yaml
```

## Validation

```bash
kubectl describe service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

From debug Pod:

```sh
curl -i http://application-service:8080/actuator/health
```

Expected:

```text
HTTP 200
```

## Mini-RCA

```text
Incident:
Requests to application-service through Kubernetes Service failed.

Impact:
Application was not reachable through Service DNS, although Pods were Running and Endpoints existed.

Detection:
curl from debug Pod to application-service failed.

Evidence:
kubectl describe service showed TargetPort 9999.
kubectl get endpoints showed PodIP:9999.
Direct curl to PodIP:8080 worked.
Direct curl to PodIP:9999 failed.

Root cause:
Service targetPort pointed to the wrong port. The application listens on 8080, but Service routed traffic to 9999.

Fix:
Restored Service targetPort to http, which maps to containerPort 8080.

Prevention:
Validate Service targetPort against container ports and test Service DNS after manifest changes.
```

---

# Drill 3: Readiness Failure

## Failure

readinessProbe was changed from:

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
```

to:

```yaml
readinessProbe:
  httpGet:
    path: /wrong-readiness
    port: http
```

## Symptoms

```text
Pod Running
READY 0/1
rollout may be stuck
new Pod does not receive Service traffic
old Pods may continue serving traffic
```

## Evidence

```bash
kubectl get pods -n sre-lab
kubectl describe pod <not-ready-pod-name> -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl describe endpointslice -n sre-lab -l kubernetes.io/service-name=application-service
```

Expected evidence:

```text
Readiness probe failed
HTTP probe failed with statuscode: 404
Endpoint ready=false
```

## Root Cause

```text
readinessProbe path was changed to /wrong-readiness, causing the new Pods to remain NotReady and preventing them from receiving Service traffic.
```

## Fix

Restore:

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
```

Apply:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

## Validation

```bash
kubectl get pods -n sre-lab
kubectl get deployment application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

From debug Pod:

```sh
curl -i http://application-service:8080/actuator/health
```

Expected:

```text
HTTP 200
```

## Mini-RCA

```text
Incident:
New application-service Pods did not become Ready during rollout.

Impact:
New release did not receive Service traffic. Rollout was blocked. Existing traffic could continue through old Ready Pods.

Detection:
kubectl rollout status did not complete.
kubectl get pods showed new Pods Running but READY 0/1.

Evidence:
kubectl describe pod showed Readiness probe failed.
Readiness probe returned 404 for /wrong-readiness.
EndpointSlice showed not-ready endpoints or Service endpoints contained only old Ready Pods.

Root cause:
readinessProbe path was configured incorrectly.

Fix:
Restored readinessProbe path to /actuator/health/readiness.

Prevention:
Validate readinessProbe endpoint before release and monitor rollout status, READY state, and EndpointSlice readiness.
```

---

# Drill 4: OOMKilled

## Failure

Memory resources were changed from:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

to:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "64Mi"
  limits:
    cpu: "500m"
    memory: "64Mi"
```

## Symptoms

```text
Pod restarts
CrashLoopBackOff may appear
RestartCount increases
rollout may be blocked
```

## Evidence

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --previous
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Expected evidence:

```text
Last State: Terminated
Reason: OOMKilled
Exit Code: 137
Memory limit: 64Mi
```

## Root Cause

```text
Memory limit was configured too low for the Spring Boot application. The container exceeded the 64Mi memory limit and was killed by the runtime.
```

## Fix

Restore:

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

## Validation

```bash
kubectl get pods -n sre-lab
kubectl get deployment application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

From debug Pod:

```sh
curl -i http://application-service:8080/actuator/health
```

Expected:

```text
HTTP 200
```

## Mini-RCA

```text
Incident:
New application-service Pods restarted repeatedly during rollout.

Impact:
New release did not become healthy. Rollout was blocked. Existing traffic could continue if old Pods remained Ready.

Detection:
kubectl get pods showed restarts / CrashLoopBackOff.
kubectl rollout status did not complete.

Evidence:
kubectl describe pod showed Last State Terminated, Reason OOMKilled, Exit Code 137.
Memory limit was set to 64Mi.
kubectl logs --previous were incomplete or showed startup interruption.

Root cause:
Memory limit was too low for the Spring Boot application, causing the container to exceed its memory limit and be killed.

Fix:
Restored memory request to 256Mi and memory limit to 512Mi.

Prevention:
Use realistic memory baselines, monitor memory usage and OOMKilled events, and validate resource changes before release.
```

---

# Drill 5: FailedScheduling / Pending Pod

## Failure

Memory resources were changed from:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

to:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "100Gi"
  limits:
    cpu: "500m"
    memory: "100Gi"
```

## Symptoms

```text
new Pod remains Pending
container does not start
application logs are absent
rollout is blocked
old Pods may continue serving traffic
```

## Evidence

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pending-pod-name> -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
kubectl describe node <node-name> | grep -A10 "Allocatable"
kubectl get deployment application-service -n sre-lab
kubectl get replicaset -n sre-lab
```

Expected evidence:

```text
FailedScheduling
Insufficient memory
0/1 nodes are available
Pod requested 100Gi memory
Node allocatable memory is lower
```

## Root Cause

```text
Memory request was configured higher than available Node allocatable memory. Kubernetes scheduler could not place the new Pod.
```

## Fix

Restore:

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

## Validation

```bash
kubectl get pods -n sre-lab
kubectl get deployment application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

From debug Pod:

```sh
curl -i http://application-service:8080/actuator/health
```

Expected:

```text
HTTP 200
```

## Mini-RCA

```text
Incident:
New application-service Pod remained Pending during rollout.

Impact:
New release could not be scheduled. Rollout was blocked. Existing traffic could continue if old Pods remained Ready.

Detection:
kubectl rollout status did not complete.
kubectl get pods showed a new Pod in Pending state.

Evidence:
kubectl describe pod showed FailedScheduling.
Events showed insufficient memory.
Deployment requested 100Gi memory for the new Pod.
Node allocatable memory was lower than the requested memory.

Root cause:
Memory request exceeded available Node allocatable capacity, so the scheduler could not place the Pod.

Fix:
Restored memory request to 256Mi and memory limit to 512Mi.

Prevention:
Validate resource requests against cluster capacity and account for rollout surge capacity before release.
```

---

# Drill 6: DNS Namespace Issue

## Failure

Client Pod in namespace `default` used short DNS name:

```text
application-service
```

while Service exists in namespace:

```text
sre-lab
```

## Symptoms

```text
Service is healthy
Endpoints are healthy
CoreDNS works
short DNS name fails from default namespace
FQDN works
```

## Evidence

From `default` namespace debug Pod:

```sh
curl -i --max-time 5 http://application-service:8080/actuator/health
```

Expected:

```text
Could not resolve host
```

Then:

```sh
curl -i http://application-service.sre-lab.svc.cluster.local:8080/actuator/health
```

Expected:

```text
HTTP 200
```

Check services:

```bash
kubectl get service -n sre-lab
kubectl get service -n default
```

Expected:

```text
application-service exists in sre-lab
application-service does not exist in default
```

## Root Cause

```text
Client used short Service DNS name from the wrong namespace. Kubernetes tried to resolve application-service.default.svc.cluster.local, but the Service exists in sre-lab namespace.
```

## Fix

Use FQDN:

```text
application-service.sre-lab.svc.cluster.local
```

or namespace-qualified form:

```text
application-service.sre-lab
```

or run the client in the correct namespace:

```text
sre-lab
```

## Mini-RCA

```text
Incident:
Client Pod could not call application-service using short DNS name.

Impact:
Application-to-application call failed from a Pod running in another namespace.

Detection:
curl from debug Pod in default namespace to http://application-service:8080 failed.

Evidence:
curl to short name application-service failed from default namespace.
curl to application-service.sre-lab.svc.cluster.local succeeded.
kubectl get service -n sre-lab showed application-service.
kubectl get service -n default did not show application-service.

Root cause:
Short Service DNS name was used from the wrong namespace. Kubernetes searched for application-service.default.svc.cluster.local, while the Service exists in sre-lab.

Fix:
Used namespace-qualified DNS name application-service.sre-lab.svc.cluster.local.

Prevention:
Use FQDN or namespace-qualified Service names for cross-namespace calls.
```

---

## Incident Matrix

```text
Incident                    Pod status        Service/Endpoint status       Main evidence
----------------------------------------------------------------------------------------------------------
Broken selector             Running 1/1       Endpoints <none>              selector != pod labels
Wrong targetPort            Running 1/1       Endpoints PodIP:9999          TargetPort wrong
Readiness failure           Running 0/1       endpoints empty/partial       Readiness probe failed
OOMKilled                   Restarting        may be old endpoints only     Reason OOMKilled, Exit 137
FailedScheduling            Pending           old endpoints may remain      FailedScheduling, insufficient memory
DNS namespace issue         Running 1/1       endpoints healthy             short name fails, FQDN works
```

---

## Source of Truth Matrix

```text
Problem type              Main source of truth
---------------------------------------------------------------
ImagePullBackOff          kubectl describe pod / events
CrashLoopBackOff          logs + describe pod
OOMKilled                 describe pod + logs --previous
Readiness failed          describe pod + READY 0/1 + EndpointSlice
Service selector issue    Service selector + Pod labels + Endpoints
Wrong targetPort          describe service + direct Pod IP curl
Pending                   describe pod + FailedScheduling events
DNS namespace issue       curl from debug Pod + FQDN test
CPU pressure              latency metrics + resource config
```

---

## Final Validation

Check manifests:

```bash
grep -n -A15 "resources:" k8s/application-service/deployment.yaml
grep -n -A6 "readinessProbe:" k8s/application-service/deployment.yaml
cat k8s/application-service/service.yaml
```

Expected resources:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

Expected readiness:

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
```

Expected Service:

```yaml
selector:
  app: application-service
ports:
  - name: http
    port: 8080
    targetPort: http
```

Check live Kubernetes state:

```bash
kubectl get pods -n sre-lab -o wide
kubectl get deployment application-service -n sre-lab
kubectl get service application-service -n sre-lab
kubectl describe service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get endpointslice -n sre-lab -l kubernetes.io/service-name=application-service
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Expected:

```text
Deployment READY 2/2
Pods Running 1/1
Service selector app=application-service
TargetPort http / 8080
Endpoints contain two PodIP:8080 entries
```

Final curl:

```bash
kubectl run curl-test \
  -n sre-lab \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- sh
```

Inside:

```sh
curl -i http://application-service:8080/actuator/health
curl -i http://application-service:8080/api/v1/applications/443
curl -i http://application-service.sre-lab.svc.cluster.local:8080/actuator/health
```

Expected:

```text
HTTP 200
```

---

## Key Lessons

```text
1. Do not guess root cause; collect evidence.
2. Pod Running does not prove Service is working.
3. Deployment Ready does not prove Service selector is correct.
4. Endpoints are critical for Service traffic diagnosis.
5. Wrong targetPort can break traffic even with healthy Endpoints.
6. Running is not the same as Ready.
7. OOMKilled is confirmed through describe pod and Exit Code 137.
8. Pending Pod usually has no application logs.
9. FailedScheduling is a scheduler-level problem.
10. DNS errors are often namespace/name issues, not CoreDNS outages.
11. Always validate after fix.
12. Every incident should end with RCA and prevention.
```