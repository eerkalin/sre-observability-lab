# Kubernetes Service Networking Troubleshooting Runbook

## Purpose

This runbook describes how to troubleshoot Kubernetes Service networking for `application-service`.

It covers:

```text
Service
ClusterIP
port
targetPort
containerPort
selector
Pod labels
Endpoints
EndpointSlice
readiness and Service routing
Kubernetes DNS
namespace DNS behavior
debug Pods
port-forward troubleshooting
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

Expected Service:

```text
application-service
```

Expected Service type:

```text
ClusterIP
```

Expected application port:

```text
8080
```

Expected full DNS name:

```text
application-service.sre-lab.svc.cluster.local
```

---

## Service Networking Mental Model

Traffic path inside the cluster:

```text
Client Pod
  ↓
Kubernetes DNS
  ↓
Service ClusterIP
  ↓
Endpoints / EndpointSlice
  ↓
Pod IP:8080
  ↓
Container port 8080
  ↓
Spring Boot application
```

Service provides a stable entry point.

Pods are dynamic backends.

---

## Service Types

Common Service types:

```text
ClusterIP:
internal cluster address.

NodePort:
opens a port on the Node.

LoadBalancer:
creates an external load balancer in cloud environments.

ExternalName:
DNS alias to an external name.
```

Current lab uses:

```text
ClusterIP
```

External local access is usually done through:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Important:

```text
port-forward is a local debugging tunnel.
It is not the same as a production traffic path.
```

---

## port / targetPort / containerPort

Service example:

```yaml
ports:
  - name: http
    port: 8080
    targetPort: http
```

Deployment example:

```yaml
ports:
  - name: http
    containerPort: 8080
```

Meaning:

```text
port:
Service port.

targetPort:
Pod/container port where Service sends traffic.

containerPort:
Port exposed by the container.
```

Traffic mapping:

```text
Service port 8080
  ↓
targetPort http
  ↓
containerPort 8080
```

---

## Selector and Labels

Service selects Pods using labels.

Expected Service selector:

```yaml
selector:
  app: application-service
```

Expected Pod labels:

```yaml
labels:
  app: application-service
  component: backend
```

If selector and labels match:

```text
Endpoints are created.
Traffic can reach Pods.
```

If they do not match:

```text
Endpoints are empty.
Service has no backends.
```

---

## Baseline Checks

Check Pods:

```bash
kubectl get pods -n sre-lab -o wide
```

Check Pod labels:

```bash
kubectl get pods -n sre-lab --show-labels
```

Check Service:

```bash
kubectl get service application-service -n sre-lab
kubectl describe service application-service -n sre-lab
```

Check Endpoints:

```bash
kubectl get endpoints application-service -n sre-lab
```

Check EndpointSlice:

```bash
kubectl get endpointslice -n sre-lab -l kubernetes.io/service-name=application-service
kubectl describe endpointslice -n sre-lab -l kubernetes.io/service-name=application-service
```

Expected healthy state:

```text
Pods Running 1/1
Deployment Ready
Service exists
Endpoints contain PodIP:8080 entries
EndpointSlice exists
```

---

## Debug Pod

Use a temporary curl Pod in the same namespace:

```bash
kubectl run curl-test \
  -n sre-lab \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- sh
```

From inside the debug Pod:

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

## DNS Names

For Service `application-service` in namespace `sre-lab`, valid DNS names include:

```text
application-service
application-service.sre-lab
application-service.sre-lab.svc
application-service.sre-lab.svc.cluster.local
```

Short name works from the same namespace:

```text
application-service
```

Full name works from other namespaces:

```text
application-service.sre-lab.svc.cluster.local
```

---

## DNS Troubleshooting

From namespace `sre-lab`:

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
```

Expected:

```text
HTTP 200
```

From namespace `default`:

```bash
kubectl run curl-test-default \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- sh
```

Inside:

```sh
curl -i --max-time 5 http://application-service:8080/actuator/health
```

Expected:

```text
Could not resolve host
```

Because Kubernetes searches for:

```text
application-service.default.svc.cluster.local
```

Correct call from another namespace:

```sh
curl -i http://application-service.sre-lab.svc.cluster.local:8080/actuator/health
```

Expected:

```text
HTTP 200
```

Diagnosis:

```text
If short name fails from another namespace but FQDN works, this is not a CoreDNS outage.
It is a wrong namespace / wrong DNS name issue.
```

---

## Optional DNS Tooling

Use netshoot for DNS tools:

```bash
kubectl run netshoot \
  --rm -it \
  --restart=Never \
  --image=nicolaka/netshoot \
  -- bash
```

Inside:

```bash
nslookup application-service
nslookup application-service.sre-lab.svc.cluster.local
dig application-service.sre-lab.svc.cluster.local
```

---

## ClusterIP and Pod IP Checks

Check Service ClusterIP:

```bash
kubectl get service application-service -n sre-lab
```

Check Pod IPs:

```bash
kubectl get pods -n sre-lab -o wide
```

From debug Pod:

```sh
curl -i http://<CLUSTER-IP>:8080/actuator/health
curl -i http://<POD-IP>:8080/actuator/health
```

Interpretation:

```text
Pod IP works but Service fails:
Service selector, Endpoints, or targetPort problem.

ClusterIP works but DNS fails:
DNS/CoreDNS or wrong DNS name problem.

DNS works inside cluster but localhost fails:
port-forward or external access problem.
```

---

## Incident 1: Broken Service Selector

### Symptoms

```text
Pods are Running and Ready.
Deployment is Ready.
Service exists.
DNS may resolve.
Requests to Service fail.
Endpoints are empty.
```

### Evidence

```bash
kubectl get endpoints application-service -n sre-lab
kubectl describe service application-service -n sre-lab
kubectl get pods -n sre-lab --show-labels
```

Look for:

```text
Service selector does not match Pod labels.
```

Example broken selector:

```yaml
selector:
  app: wrong-application-service
```

Expected Pod label:

```yaml
app: application-service
```

### Diagnosis

```text
Service does not select any Pods because selector does not match Pod labels.
```

### Fix

Restore selector:

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
curl -i http://application-service:8080/actuator/health
```

---

## Incident 2: Wrong targetPort

### Symptoms

```text
Pods are Running and Ready.
Deployment is Ready.
Service exists.
Endpoints are not empty.
Requests to Service fail.
```

### Evidence

```bash
kubectl describe service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get pods -n sre-lab -o wide
```

Look for:

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

### Diagnosis

```text
Service targetPort points to a port where the application is not listening.
```

### Fix

Restore targetPort:

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

---

## Incident 3: Readiness Failed

### Symptoms

```text
Pod is Running but READY 0/1.
Deployment rollout may be stuck.
Service exists.
Selector is correct.
Pod may not receive Service traffic.
```

### Evidence

```bash
kubectl get pods -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl describe endpointslice -n sre-lab -l kubernetes.io/service-name=application-service
```

Look for:

```text
Readiness probe failed.
Endpoint ready=false.
```

### Diagnosis

```text
Readiness probe is failing, so Kubernetes removes the Pod from Service endpoints.
```

### Fix

Restore readinessProbe:

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

---

## Incident 4: Wrong Namespace DNS Name

### Symptoms

```text
Short Service name fails from another namespace.
FQDN works.
Service and Endpoints are healthy.
```

### Evidence

From namespace `default`:

```sh
curl -i --max-time 5 http://application-service:8080/actuator/health
```

fails.

But:

```sh
curl -i http://application-service.sre-lab.svc.cluster.local:8080/actuator/health
```

works.

### Diagnosis

```text
Client used short Service name from the wrong namespace.
```

### Fix

Use namespace-qualified DNS name:

```text
application-service.sre-lab.svc.cluster.local
```

or run the client in the correct namespace.

---

## Incident 5: Port-forward Issue

### Symptoms

```text
Service works from inside the cluster.
localhost access fails from local machine.
```

### Evidence

Inside cluster:

```sh
curl -i http://application-service:8080/actuator/health
```

works.

On local machine:

```bash
curl -i http://localhost:8080/actuator/health
```

fails.

### Diagnosis

```text
Cluster Service is healthy.
The issue is local tunnel or external access path.
```

### Fix

Start correct port-forward:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Then:

```bash
curl -i http://localhost:8080/actuator/health
```

---

## Service Networking Diagnosis Matrix

```text
Problem                 Pods            Endpoints             DNS              Main evidence
-----------------------------------------------------------------------------------------------------
Broken selector         Running 1/1     <none>                may resolve      selector != pod labels
Wrong targetPort        Running 1/1     PodIP:wrongPort       resolves         TargetPort wrong
Readiness failed        Running 0/1     empty/partial         resolves         Readiness probe failed
Wrong namespace DNS     Running 1/1     healthy              short name fail  FQDN works
CoreDNS issue           Running 1/1     healthy              DNS fail         ClusterIP works
Port-forward issue      Running 1/1     healthy              cluster works    localhost fails
```

---

## Recommended Troubleshooting Order

```text
1. Check Pods.
2. Check Pod readiness.
3. Check Pod labels.
4. Check Service selector.
5. Check Service ports and targetPort.
6. Check Endpoints.
7. Check EndpointSlice.
8. Test Service DNS from a debug Pod.
9. Test ClusterIP from a debug Pod.
10. Test direct Pod IP from a debug Pod.
11. Check DNS namespace/FQDN.
12. Check port-forward or external access.
13. Only then investigate CoreDNS, CNI, NetworkPolicy, or ingress.
```

Commands:

```bash
kubectl get pods -n sre-lab -o wide
kubectl get pods -n sre-lab --show-labels
kubectl get service application-service -n sre-lab
kubectl describe service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl get endpointslice -n sre-lab -l kubernetes.io/service-name=application-service
kubectl describe endpointslice -n sre-lab -l kubernetes.io/service-name=application-service
```

Debug Pod:

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
curl -i http://application-service.sre-lab.svc.cluster.local:8080/actuator/health
curl -i http://<CLUSTER-IP>:8080/actuator/health
curl -i http://<POD-IP>:8080/actuator/health
```

---

## Final Validation

Check Service manifest:

```bash
cat k8s/application-service/service.yaml
```

Check Deployment labels and ports:

```bash
grep -n -A20 "labels:" k8s/application-service/deployment.yaml
grep -n -A10 "ports:" k8s/application-service/deployment.yaml
```

Check live state:

```bash
kubectl get pods -n sre-lab -o wide
kubectl get service application-service -n sre-lab
kubectl describe service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Check Service from inside cluster:

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
```

Expected:

```text
HTTP 200
```

Check local port-forward:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Then:

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
1. Service is a stable frontend for dynamic Pods.
2. Service selects Pods through labels.
3. Endpoints are the real backend list for Service traffic.
4. EndpointSlice is the modern backend representation.
5. Running Pod is not enough; Service traffic requires Ready endpoints.
6. Broken selector produces empty Endpoints.
7. Wrong targetPort produces Endpoints with the wrong port.
8. Readiness failure removes Pods from Service endpoints.
9. Short DNS names depend on namespace.
10. FQDN works across namespaces.
11. port-forward is a local debugging tunnel, not production networking.
12. Always test from inside the cluster before blaming external access.
```