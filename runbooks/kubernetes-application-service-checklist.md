# Kubernetes Application Service Checklist

## Purpose

This runbook describes how to validate, apply, and troubleshoot Kubernetes manifests for `application-service`.

The goal is to deploy `application-service` in a production-like Kubernetes structure using:

* Namespace
* ConfigMap
* Secret
* Deployment
* Service
* Probes
* Resource requests and limits

Educational raw Pod examples are stored separately and should not be applied as part of the normal deployment flow.

---

## 1. Expected structure

```text
sre-observability-lab/
└── k8s/
    ├── namespace.yaml
    ├── application-service/
    │   ├── configmap.yaml
    │   ├── secret.yaml
    │   ├── deployment.yaml
    │   └── service.yaml
    └── examples/
        ├── pod.yaml
        └── README.md
```

Normal deployment manifests:

```text
k8s/application-service/
```

Educational examples:

```text
k8s/examples/
```

Do not apply `k8s/examples/` as part of the normal application deployment flow.

---

## 2. Kubernetes objects

### Namespace

```text
k8s/namespace.yaml
```

Creates:

```text
namespace: sre-lab
```

The namespace is used to isolate the lab application resources.

---

### ConfigMap

```text
k8s/application-service/configmap.yaml
```

Stores non-secret runtime configuration:

```text
SPRING_PROFILES_ACTIVE
APP_ENVIRONMENT
APP_OWNER
APP_DEFAULT_CUSTOMER_SEGMENT
APP_SLOW_ENDPOINT_ENABLED
APP_DEFAULT_SLOW_DELAY_MS
```

---

### Secret

```text
k8s/application-service/secret.yaml
```

Stores sensitive runtime configuration.

Current value is a demo-only secret:

```text
EXTERNAL_PARTNER_API_TOKEN
```

Do not commit real production secrets to Git.

---

### Deployment

```text
k8s/application-service/deployment.yaml
```

Defines:

* application image
* replicas
* labels and selectors
* environment configuration from ConfigMap and Secret
* startupProbe
* readinessProbe
* livenessProbe
* CPU and memory requests/limits

---

### Service

```text
k8s/application-service/service.yaml
```

Creates internal ClusterIP access to application Pods.

Expected internal DNS name inside the same namespace:

```text
application-service
```

Full DNS name:

```text
application-service.sre-lab.svc.cluster.local
```

---

## 3. Safe apply order

Apply namespace first:

```bash
kubectl apply -f k8s/namespace.yaml
```

Apply application manifests:

```bash
kubectl apply -f k8s/application-service/
```

Do not apply:

```bash
kubectl apply -f k8s/
```

Reason:

`k8s/` also contains educational examples under `k8s/examples/`.

---

## 4. Recommended validation before apply

Check current Kubernetes context:

```bash
kubectl config current-context
```

Check namespaces:

```bash
kubectl get ns
```

Client-side validation:

```bash
kubectl apply --dry-run=client -f k8s/namespace.yaml
kubectl apply --dry-run=client -f k8s/application-service/
```

Server-side validation:

```bash
kubectl apply --dry-run=server -f k8s/namespace.yaml
kubectl apply --dry-run=server -f k8s/application-service/
```

Check diff before applying changes to an existing cluster:

```bash
kubectl diff -f k8s/application-service/
```

---

## 5. Apply manifests

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/application-service/
```

Expected objects:

```text
namespace/sre-lab
configmap/application-service-config
secret/application-service-secret
deployment/application-service
service/application-service
```

---

## 6. Validate runtime state

Check all resources:

```bash
kubectl get all -n sre-lab
```

Check ConfigMap:

```bash
kubectl get configmap -n sre-lab
kubectl describe configmap application-service-config -n sre-lab
```

Check Secret:

```bash
kubectl get secret -n sre-lab
kubectl describe secret application-service-secret -n sre-lab
```

Check Deployment:

```bash
kubectl get deployment application-service -n sre-lab
kubectl describe deployment application-service -n sre-lab
```

Check Pods:

```bash
kubectl get pods -l app=application-service -n sre-lab
```

Check Service:

```bash
kubectl get service application-service -n sre-lab
kubectl describe service application-service -n sre-lab
```

Check endpoints:

```bash
kubectl get endpoints application-service -n sre-lab
```

---

## 7. Expected healthy state

Deployment:

```text
READY: 2/2
AVAILABLE: 2
```

Pods:

```text
READY: 1/1
STATUS: Running
RESTARTS: 0
```

Service:

```text
TYPE: ClusterIP
PORT: 8080
```

Endpoints:

```text
application-service has ready backend Pod IPs
```

---

## 8. Troubleshooting flow

Start with high-level status:

```bash
kubectl get all -n sre-lab
```

Check Pods:

```bash
kubectl get pods -n sre-lab
```

Check Deployment:

```bash
kubectl describe deployment application-service -n sre-lab
```

Check specific Pod:

```bash
kubectl describe pod <pod-name> -n sre-lab
```

Check logs:

```bash
kubectl logs <pod-name> -n sre-lab
```

Check previous logs after restart:

```bash
kubectl logs <pod-name> -n sre-lab --previous
```

Check runtime environment:

```bash
kubectl exec -it <pod-name> -n sre-lab -- printenv
```

Check Service endpoints:

```bash
kubectl get endpoints application-service -n sre-lab
```

---

## 9. Common issue: wrong namespace

Symptoms:

* Deployment cannot find ConfigMap.
* Deployment cannot find Secret.
* Service has no endpoints.
* `kubectl get pods` shows nothing.

Checks:

```bash
kubectl get pods -A | grep application-service
kubectl get configmap -A | grep application-service
kubectl get secret -A | grep application-service
kubectl get service -A | grep application-service
```

Expected namespace:

```text
sre-lab
```

---

## 10. Common issue: ConfigMap not found

Symptoms:

```text
Pod does not start
```

Check:

```bash
kubectl describe pod <pod-name> -n sre-lab
```

Look for Events like:

```text
configmap "application-service-config" not found
```

Fix:

```bash
kubectl apply -f k8s/application-service/configmap.yaml
kubectl rollout restart deployment/application-service -n sre-lab
```

---

## 11. Common issue: Secret not found

Symptoms:

```text
Pod does not start
```

Check:

```bash
kubectl describe pod <pod-name> -n sre-lab
```

Look for Events like:

```text
secret "application-service-secret" not found
```

Fix:

```bash
kubectl apply -f k8s/application-service/secret.yaml
kubectl rollout restart deployment/application-service -n sre-lab
```

Do not print real secret values in logs, tickets, dashboards, or chat messages.

---

## 12. Common issue: ImagePullBackOff

Symptoms:

```text
Pod STATUS: ImagePullBackOff
```

Check:

```bash
kubectl describe pod <pod-name> -n sre-lab
```

Likely reason:

```text
image: application-service:local
```

This image is local and may not exist on Kubernetes worker nodes.

Production fix:

* Build image in CI/CD.
* Push image to container registry.
* Use registry image in Deployment.

Example:

```text
${ACCOUNT_ID}.dkr.ecr.eu-central-1.amazonaws.com/application-service:1.0.0
```

---

## 13. Common issue: Service has no endpoints

Symptoms:

```text
Service exists, but traffic does not reach Pods.
```

Checks:

```bash
kubectl get service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get pods -l app=application-service -n sre-lab --show-labels
kubectl describe service application-service -n sre-lab
```

Likely causes:

* Service selector does not match Pod labels.
* Pods are not Ready.
* Pods are in another namespace.
* Readiness probe is failing.

Expected selector:

```text
app=application-service
```

Expected Pod label:

```text
app=application-service
```

---

## 14. Common issue: Pod Running but READY 0/1

Symptoms:

```text
STATUS: Running
READY: 0/1
```

Likely cause:

```text
readinessProbe failed
```

Check:

```bash
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Important:

A Pod can be Running but not receive traffic if readinessProbe fails.

---

## 15. Common issue: CrashLoopBackOff

Symptoms:

```text
STATUS: CrashLoopBackOff
RESTARTS increasing
```

Checks:

```bash
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --previous
```

Likely causes:

* Application startup failure.
* Invalid configuration.
* Missing dependency.
* Liveness probe too aggressive.
* Memory limit too low.
* JVM memory issue.

---

## 16. Common issue: OOMKilled

Symptoms:

```text
RESTARTS increasing
Last State: Terminated
Reason: OOMKilled
Exit Code: 137
```

Check:

```bash
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --previous
kubectl top pod -n sre-lab
```

Likely causes:

* Memory limit too low.
* Memory leak.
* JVM heap/native memory not sized properly.
* Traffic/load spike.

Current memory limit:

```text
512Mi
```

---

## 17. Common issue: CPU throttling

Symptoms:

* Pod is Running.
* Pod is Ready.
* Logs may look normal.
* Latency increases.
* Throughput decreases.

Check metrics:

```text
container CPU usage
container CPU throttling
application latency
HTTP request duration
```

Current CPU limit:

```text
500m
```

Important:

CPU throttling may cause performance degradation without obvious application errors.

---

## 18. Probes

Current probe endpoint:

```text
/actuator/health
```

Current probes:

* startupProbe
* readinessProbe
* livenessProbe

Production recommendation:

Use separate health endpoints when possible:

```text
/actuator/health/readiness
/actuator/health/liveness
```

Important rule:

* readiness can be strict
* liveness must be careful
* startupProbe protects slow-starting applications

---

## 19. Resource requests and limits

Current resources per Pod:

```text
requests:
  cpu: 250m
  memory: 256Mi

limits:
  cpu: 500m
  memory: 512Mi
```

With 2 replicas, total requested resources:

```text
CPU: 500m
Memory: 512Mi
```

With 2 replicas, total maximum limits:

```text
CPU: 1000m
Memory: 1024Mi
```

---

## 20. Safe delete

Delete application resources:

```bash
kubectl delete -f k8s/application-service/
```

Delete namespace only when intentionally removing all resources in it:

```bash
kubectl delete -f k8s/namespace.yaml
```

Warning:

Deleting namespace deletes all namespaced resources inside it.

---

## 21. Completion criteria

Kubernetes manifests are ready when:

* `namespace.yaml` defines `sre-lab`.
* Application manifests use `namespace: sre-lab`.
* Raw Pod example is stored under `k8s/examples/`.
* Normal application folder contains only ConfigMap, Secret, Deployment, and Service.
* Deployment uses ConfigMap via `configMapRef`.
* Deployment uses Secret via `secretRef`.
* Deployment has startup, readiness, and liveness probes.
* Deployment has CPU and memory requests/limits.
* Service selector matches Pod labels.
* Runbook documents validation and troubleshooting flow.
