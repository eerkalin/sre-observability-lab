# Kubernetes Resources and Capacity Runbook

## Purpose

This runbook describes how to reason about Kubernetes resources for `application-service`.

It covers:

```text
CPU requests
CPU limits
memory requests
memory limits
QoS classes
OOMKilled
CPU throttling
Pending Pods
FailedScheduling
capacity planning
rollout surge capacity
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

Expected current baseline:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

Expected replicas and strategy:

```yaml
replicas: 2
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

Expected QoS class:

```text
Burstable
```

---

## Resource Concepts

### Requests

Requests are used by the Kubernetes scheduler.

They answer:

```text
How much CPU and memory should Kubernetes reserve for this container when placing it on a Node?
```

Requests affect:

```text
scheduling
cluster capacity
bin packing
autoscaling decisions
eviction behavior
QoS class
```

Requests are not hard limits.

### Limits

Limits are upper boundaries.

CPU and memory behave differently.

Memory limit:

```text
If the container exceeds memory limit, it may be killed.
```

CPU limit:

```text
If the container wants more CPU than the limit, it is throttled.
The container usually is not killed, but request latency may increase.
```

---

## CPU Units

CPU is measured in cores.

```text
1 CPU = 1 CPU core
500m = 0.5 CPU
100m = 0.1 CPU
50m = 0.05 CPU
```

Example:

```yaml
cpu: "100m"
```

means 10% of one CPU core.

---

## Memory Units

Memory is usually expressed as:

```text
Mi
Gi
```

Examples:

```yaml
memory: "128Mi"
memory: "256Mi"
memory: "512Mi"
memory: "1Gi"
```

For Spring Boot applications, very low memory limits such as `64Mi` are usually unrealistic.

---

## Current Baseline

Application resources:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

Interpretation:

```text
CPU request:
100m reserved for scheduling.

CPU limit:
application can burst up to 500m.

Memory request:
256Mi reserved for scheduling.

Memory limit:
container can use up to 512Mi before it risks being OOMKilled.
```

---

## Check Resources

Check manifest:

```bash
grep -n -A15 "resources:" k8s/application-service/deployment.yaml
```

Check live Deployment:

```bash
kubectl get deployment application-service -n sre-lab -o yaml | grep -A15 "resources:"
```

Check Pod resources:

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
```

Look for:

```text
Limits
Requests
```

---

## Check Node Capacity

List Nodes:

```bash
kubectl get nodes
```

Describe Node:

```bash
kubectl describe node <node-name>
```

Look for:

```text
Capacity
Allocatable
Allocated resources
```

Meaning:

```text
Capacity:
physical or configured resource capacity of the Node.

Allocatable:
resources available for Pods after system reservations.

Allocated resources:
sum of requests and limits from scheduled Pods.
```

---

## kubectl top

`kubectl top` requires Metrics Server.

Try:

```bash
kubectl top pods -n sre-lab
kubectl top nodes
```

If Metrics Server is not installed, this may fail.

This is expected in a minimal kind lab.

Important distinction:

```text
Application metrics are not the same as Kubernetes resource metrics.
```

Future improvements:

```text
metrics-server
kube-state-metrics
node-exporter
cAdvisor/container metrics
resource dashboards
```

---

## QoS Classes

Kubernetes assigns each Pod a QoS class:

```text
Guaranteed
Burstable
BestEffort
```

QoS affects eviction priority under resource pressure.

### BestEffort

No requests and no limits.

```yaml
resources: {}
```

Risk:

```text
weakest class
poor capacity planning
first candidate for eviction under pressure
```

Not recommended for production backend services.

### Burstable

Requests/limits exist, but requests do not equal limits.

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

This is the current `application-service` class.

### Guaranteed

Requests equal limits for CPU and memory for all containers.

Example:

```yaml
resources:
  requests:
    cpu: "500m"
    memory: "512Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

More predictable, but less flexible.

---

## Check QoS

```bash
kubectl get pods -n sre-lab
```

```bash
kubectl get pod <pod-name> -n sre-lab \
  -o jsonpath='{.status.qosClass}{"\n"}'
```

Expected:

```text
Burstable
```

Alternative:

```bash
kubectl describe pod <pod-name> -n sre-lab | grep -i "QoS"
```

---

## CPU Limit Too Low

### Meaning

Container has too little CPU budget.

It usually does not crash.

Instead, it may be throttled.

### Symptoms

```text
Pod Running 1/1
RestartCount does not increase
OOMKilled is absent
HTTP 5xx may remain low
p95/p99 latency increases
startup may become slower
GC may become slower
```

### Evidence

```text
CPU limit is very low.
Grafana shows increased HTTP p95 latency.
Prometheus shows request latency increase.
Application logs may show slower requests.
Pod status remains healthy.
```

### Prometheus latency query

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{job="application-service-pods", uri!~"/actuator.*"}[1m])
  )
)
```

### Request rate query

```promql
sum(rate(http_server_requests_seconds_count{job="application-service-pods", uri!~"/actuator.*"}[1m]))
```

### 5xx query

```promql
sum(rate(http_server_requests_seconds_count{job="application-service-pods", status=~"5.."}[1m]))
```

### Diagnosis

```text
Application latency increased while Pods remain Running and Ready.
CPU limit is too restrictive, causing CPU pressure or throttling.
```

### Fix

Restore or increase CPU request/limit based on measured usage and latency targets.

Current baseline:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

---

## Memory Limit Too Low / OOMKilled

### Meaning

Container exceeded its memory limit and was killed.

### Symptoms

```text
Pod may show CrashLoopBackOff
RestartCount increases
Last State is Terminated
Reason is OOMKilled
Exit Code is 137
```

### Diagnosis commands

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --previous
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Look for:

```text
Last State: Terminated
Reason: OOMKilled
Exit Code: 137
Restart Count
Limits
Requests
```

### Important note

Application logs may be incomplete because the process was killed externally.

### Diagnosis

```text
Container exceeded its memory limit and was killed by the runtime.
```

### Fix

Increase memory request/limit to a realistic baseline and investigate memory usage if unexpected.

Current baseline:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

---

## Memory Request Too High / Pending Pod

### Meaning

Scheduler cannot place the Pod because the requested resources exceed Node allocatable resources.

### Symptoms

```text
new Pod remains Pending
rollout does not complete
application logs do not exist for the Pending Pod
old Pods may remain Running
```

### Diagnosis commands

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pending-pod-name> -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
kubectl get deployment application-service -n sre-lab
kubectl get replicaset -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Look for:

```text
FailedScheduling
Insufficient memory
0/1 nodes are available
```

### Diagnosis

```text
Memory request is higher than available Node allocatable capacity.
```

### Fix

Reduce resource requests to realistic values or add cluster capacity.

---

## Resource Failure Matrix

```text
Problem                     Pod status         Main evidence
---------------------------------------------------------------------------
CPU limit too low           Running 1/1        high latency, no restarts
Memory limit too low        CrashLoop/OOM      OOMKilled, Exit Code 137
Memory request too high     Pending            FailedScheduling, insufficient memory
No requests/limits          Running maybe      BestEffort, poor capacity control
Requests too low            Running maybe      overpacking, contention under load
Requests too high           Pending/low usage  inefficient cluster utilization
```

Sources of truth:

```text
CPU pressure:
Prometheus/Grafana latency and throttling metrics.

OOMKilled:
kubectl describe pod
kubectl logs --previous
RestartCount.

Pending:
kubectl describe pod
scheduler Events
FailedScheduling.

QoS:
kubectl get pod -o jsonpath='{.status.qosClass}'.
```

---

## Capacity Planning

Capacity must be calculated for more than normal state.

Current normal state:

```text
replicas: 2
cpu request: 100m
memory request: 256Mi
```

Normal reserved capacity:

```text
2 Pods × 100m CPU = 200m CPU request
2 Pods × 256Mi memory = 512Mi memory request
```

Rollout strategy:

```text
maxSurge: 1
```

During rollout, there may be 3 Pods.

Rollout surge reserved capacity:

```text
3 Pods × 100m CPU = 300m CPU request
3 Pods × 256Mi memory = 768Mi memory request
```

Important:

```text
Capacity must account for rollout, failover, and traffic spikes.
```

---

## Production Resource Selection Process

Recommended process:

```text
1. Start with a reasonable baseline.
2. Collect real usage metrics.
3. Review p50, p95, p99 CPU and memory usage.
4. Compare usage to requests and limits.
5. Review latency, error rate, restarts, OOMKilled, throttling.
6. Adjust requests and limits.
7. Validate with load testing.
8. Repeat regularly.
```

---

## What to Monitor in Production

Resource monitoring should include:

```text
CPU usage
CPU throttling
memory working set
memory RSS
memory limit utilization
OOMKilled count
Pod restarts
Pod Pending state
FailedScheduling events
Node allocatable resources
Node pressure
container filesystem usage
```

Typical Prometheus metrics:

```text
container_cpu_usage_seconds_total
container_cpu_cfs_throttled_seconds_total
container_cpu_cfs_throttled_periods_total
container_memory_working_set_bytes
kube_pod_container_status_restarts_total
kube_pod_container_status_last_terminated_reason
kube_pod_status_phase
kube_node_status_allocatable
```

Usually provided by:

```text
kubelet/cAdvisor metrics
kube-state-metrics
node-exporter
metrics-server
```

---

## Final Validation

Check resources in manifest:

```bash
grep -n -A15 "resources:" k8s/application-service/deployment.yaml
```

Check live Deployment:

```bash
kubectl get deployment application-service -n sre-lab -o yaml | grep -A15 "resources:"
```

Check Pods:

```bash
kubectl get pods -n sre-lab
```

Check QoS:

```bash
kubectl get pod <pod-name> -n sre-lab \
  -o jsonpath='{.status.qosClass}{"\n"}'
```

Expected:

```text
Burstable
```

Check Deployment:

```bash
kubectl get deployment application-service -n sre-lab
```

Expected:

```text
READY 2/2
```

Check Endpoints:

```bash
kubectl get endpoints application-service -n sre-lab
```

Expected:

```text
2 endpoints
```

Check health:

```bash
curl -i http://localhost:8080/actuator/health
```

Expected:

```text
HTTP 200
```

---

## Key Lessons

```text
1. Requests are used for scheduling.
2. Limits are runtime boundaries.
3. Memory limit breach can kill the container.
4. CPU limit breach usually throttles the container.
5. CPU throttling often appears as latency, not as a Pod failure.
6. OOMKilled is confirmed mainly through describe pod.
7. Pending due to high requests is confirmed through scheduler events.
8. QoS Class depends on requests and limits.
9. Burstable is common for application workloads.
10. Capacity must include normal state and rollout surge state.
11. Without resource metrics, application latency may be the first signal of CPU pressure.
12. Production resource tuning is iterative and metric-driven.
```