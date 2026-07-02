# Kubernetes Service Types Runbook

## Purpose

This runbook explains Kubernetes Service types and how to troubleshoot service exposure issues.

It covers:

```text
ClusterIP
NodePort
LoadBalancer
kind multi-node cluster
externalTrafficPolicy
EndpointSlices
Service selector issues
targetPort issues
readiness issues
LoadBalancer Pending behavior
```

---

## Environment

Cluster:

```text
kind: sre-lab
topology: 1 control-plane + 2 workers
```

Namespaces:

```text
sre-lab
monitoring
```

Application:

```text
Deployment: application-service
Container port: 8080
Port name: http
Health endpoint: /actuator/health
Readiness endpoint: /actuator/health/readiness
```

Services:

```text
application-service            ClusterIP
application-service-nodeport   NodePort
application-service-lb         LoadBalancer
```

---

## Multi-node kind cluster

Kind config:

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: sre-lab
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30080
        hostPort: 8080
        protocol: TCP
      - containerPort: 30443
        hostPort: 8443
        protocol: TCP
  - role: worker
  - role: worker
```

Create cluster:

```bash
kind create cluster --config kind/sre-lab-multinode.yaml
```

Check nodes:

```bash
kubectl get nodes -o wide
```

Expected:

```text
sre-lab-control-plane
sre-lab-worker
sre-lab-worker2
```

Important:

```text
hostPort 8080 maps to NodePort 30080.
hostPort 8443 maps to NodePort 30443.
```

This is useful for NodePort and future Ingress/TLS labs.

---

## Service mental model

A Kubernetes Service provides a stable virtual endpoint for a dynamic set of Pods.

Basic chain:

```text
Service selector
  ↓
Ready Pods with matching labels
  ↓
Endpoints / EndpointSlices
  ↓
kube-proxy routing
  ↓
Pod IP:targetPort
```

If Endpoints are empty, the Service has nowhere to send traffic.

---

## ClusterIP

ClusterIP is the default Service type.

Example:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: application-service
  namespace: sre-lab
spec:
  type: ClusterIP
  selector:
    app: application-service
  ports:
    - name: http
      port: 8080
      targetPort: http
```

ClusterIP is reachable only inside the Kubernetes cluster.

Internal access:

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
exit
```

Expected:

```text
HTTP/1.1 200
```

Important:

```text
ClusterIP is not directly reachable from the Mac host.
kubectl port-forward is a debug tunnel, not production exposure.
```

---

## NodePort

NodePort exposes a Service on a static port on every Kubernetes Node.

Manifest:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: application-service-nodeport
  namespace: sre-lab
  labels:
    app: application-service
spec:
  type: NodePort
  selector:
    app: application-service
  ports:
    - name: http
      port: 8080
      targetPort: http
      nodePort: 30080
```

Apply:

```bash
kubectl apply -f k8s/application-service/service-nodeport.yaml
```

Check:

```bash
kubectl get svc application-service-nodeport -n sre-lab
kubectl describe svc application-service-nodeport -n sre-lab
kubectl get endpoints application-service-nodeport -n sre-lab -o wide
```

Access from Mac through kind port mapping:

```bash
curl -i http://localhost:8080/actuator/health
```

Traffic path:

```text
Mac
  ↓
localhost:8080
  ↓
kind extraPortMapping
  ↓
sre-lab-control-plane:30080
  ↓
NodePort Service
  ↓
EndpointSlice
  ↓
application-service Pod:8080
```

Production-like NodePort path:

```text
Client
  ↓
DNS app.company.kz
  ↓
External Load Balancer VIP
  ↓
Worker Node:NodePort
  ↓
Kubernetes Service
  ↓
Ready Pods
```

---

## NodePort and replicas on different nodes

Example:

```text
Worker Node 1: application-service Pod
Worker Node 2: application-service Pod
Worker Node 3: no application-service Pod
```

NodePort still opens the port on every node:

```text
Worker Node 1:30080
Worker Node 2:30080
Worker Node 3:30080
```

With default behavior:

```yaml
externalTrafficPolicy: Cluster
```

A request entering Worker Node 3 can still be routed to a Pod on Worker Node 1 or Worker Node 2.

Path:

```text
LB
  ↓
Worker Node 3:30080
  ↓
kube-proxy
  ↓
Service endpoints
  ↓
Pod on Worker Node 1 or Worker Node 2
```

With:

```yaml
externalTrafficPolicy: Local
```

The node should only use local endpoints.

If the request lands on a node with no local Pod endpoint, the request may fail or be dropped.

Trade-off:

```text
Cluster:
  works through any node
  may add cross-node hop
  may lose original source IP

Local:
  preserves source IP
  avoids extra hop
  requires LB health checks and local endpoints
```

---

## LoadBalancer

LoadBalancer Service asks the infrastructure provider to allocate an external IP or DNS name.

Manifest:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: application-service-lb
  namespace: sre-lab
  labels:
    app: application-service
spec:
  type: LoadBalancer
  selector:
    app: application-service
  ports:
    - name: http
      port: 8080
      targetPort: http
```

Apply:

```bash
kubectl apply -f k8s/application-service/service-loadbalancer.yaml
```

Check:

```bash
kubectl get svc application-service-lb -n sre-lab
kubectl describe svc application-service-lb -n sre-lab
```

In kind without a LoadBalancer controller:

```text
EXTERNAL-IP: <pending>
```

This is expected.

Important:

```text
LoadBalancer Service still gets ClusterIP.
LoadBalancer Service usually gets NodePort internally.
External IP remains Pending without cloud provider or on-prem LB controller.
```

Internal check:

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
curl -i http://application-service-lb:8080/actuator/health
exit
```

Expected:

```text
HTTP/1.1 200
```

Production cloud path:

```text
Client
  ↓
Cloud Load Balancer external IP/DNS
  ↓
NodePort on Kubernetes nodes
  ↓
Service
  ↓
Pods
```

On-prem equivalent:

```text
Client
  ↓
DNS
  ↓
F5 / HAProxy / NGINX / MetalLB VIP
  ↓
NodePort or Ingress Controller
  ↓
Service
  ↓
Pods
```

Future lab:

```text
Install MetalLB and configure IPAddressPool/L2Advertisement so EXTERNAL-IP is allocated in kind.
```

---

## Service comparison

```text
Type           Access scope                       Common use
---------------------------------------------------------------------------
ClusterIP      Inside cluster only                internal services
NodePort       NodeIP:NodePort                    lab, LB backend, simple exposure
LoadBalancer   External IP/DNS via provider       cloud/on-prem external exposure
```

---

## Troubleshooting chain

When Service does not work, check in this order:

```bash
kubectl get svc -n sre-lab
kubectl describe svc <service-name> -n sre-lab
kubectl get endpoints <service-name> -n sre-lab -o wide
kubectl get endpointslice -n sre-lab -l kubernetes.io/service-name=<service-name>
kubectl get pods -n sre-lab --show-labels
kubectl get pods -n sre-lab -o wide
kubectl describe pod <pod-name> -n sre-lab
```

Core diagnostic chain:

```text
Service exists?
  ↓
selector correct?
  ↓
Pod labels match selector?
  ↓
Pods Ready?
  ↓
Endpoints not empty?
  ↓
targetPort correct?
  ↓
application listens on target port?
  ↓
network policy / firewall / LB path correct?
```

---

## Drill 1: Broken selector

Symptom:

```text
Service exists, but has no endpoints.
```

Break:

```yaml
selector:
  app: application-service-broken
```

Apply:

```bash
kubectl apply -f k8s/application-service/service-nodeport.yaml
```

Check:

```bash
kubectl get endpoints application-service-nodeport -n sre-lab
kubectl describe svc application-service-nodeport -n sre-lab
curl -i --max-time 5 http://localhost:8080/actuator/health
```

Expected:

```text
Endpoints: <none>
curl fails
```

Root cause:

```text
Service selector does not match Pod labels.
```

Fix:

```yaml
selector:
  app: application-service
```

Apply:

```bash
kubectl apply -f k8s/application-service/service-nodeport.yaml
```

Validate:

```bash
kubectl get endpoints application-service-nodeport -n sre-lab -o wide
curl -i http://localhost:8080/actuator/health
```

---

## Drill 2: Wrong targetPort

Symptom:

```text
Service has endpoints, but traffic cannot reach application port.
```

Break:

```yaml
targetPort: wrong-http
```

Check:

```bash
kubectl describe svc application-service-nodeport -n sre-lab
kubectl get endpoints application-service-nodeport -n sre-lab -o wide
curl -i --max-time 5 http://localhost:8080/actuator/health
```

Root cause:

```text
Service targetPort references a named port that does not exist in the Pod container ports.
```

Fix:

```yaml
targetPort: http
```

Validate:

```bash
kubectl apply -f k8s/application-service/service-nodeport.yaml
curl -i http://localhost:8080/actuator/health
```

---

## Drill 3: Broken readiness

Symptom:

```text
Pods are Running but not Ready.
Service endpoints become empty or reduced.
ClusterIP and NodePort fail or partially fail.
```

Break readinessProbe:

```yaml
readinessProbe:
  httpGet:
    path: /wrong-readiness
    port: http
```

Apply:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
```

Check:

```bash
kubectl get pods -n sre-lab
kubectl describe pod -n sre-lab -l app=application-service | grep -A10 -i "Readiness"
kubectl get endpoints application-service -n sre-lab
kubectl get endpoints application-service-nodeport -n sre-lab
```

Root cause:

```text
ReadinessProbe failed.
Pods are Running but not Ready.
Service removes not-ready Pods from endpoints.
```

Fix:

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
```

Validate:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
curl -i http://localhost:8080/actuator/health
```

---

## Drill 4: LoadBalancer Pending

Symptom:

```text
Service type is LoadBalancer.
EXTERNAL-IP is <pending>.
```

Check:

```bash
kubectl get svc application-service-lb -n sre-lab
kubectl describe svc application-service-lb -n sre-lab
kubectl get events -n sre-lab --sort-by=.metadata.creationTimestamp | tail -20
```

Expected in kind:

```text
EXTERNAL-IP <pending>
```

Root cause:

```text
No cloud provider or LoadBalancer controller is available to allocate an external IP.
```

Fix options:

```text
Managed Kubernetes:
  cloud-controller-manager creates cloud Load Balancer.

On-prem/bare-metal:
  MetalLB, F5, HAProxy, NGINX, Citrix ADC, NSX, or similar.

Kind lab:
  install MetalLB later.
```

---

## Final validation

```bash
kubectl get nodes -o wide
kubectl get pods -n sre-lab -o wide
kubectl get svc -n sre-lab
kubectl get endpoints -n sre-lab
curl -i http://localhost:8080/actuator/health
```

Internal validation:

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
curl -i http://application-service-nodeport:8080/actuator/health
curl -i http://application-service-lb:8080/actuator/health
exit
```

Expected:

```text
HTTP/1.1 200
```

---

## Key lessons

```text
1. ClusterIP is for internal cluster access.
2. NodePort opens a port on every Kubernetes Node.
3. LoadBalancer requires cloud provider or LB controller.
4. LoadBalancer usually gets a NodePort internally.
5. In kind, LoadBalancer EXTERNAL-IP remains Pending without MetalLB or another controller.
6. Service selector must match Pod labels.
7. Readiness controls whether Pods appear in Service endpoints.
8. Wrong targetPort can break traffic even when endpoints exist.
9. EndpointSlices show the real backend Pod addresses.
10. externalTrafficPolicy controls cross-node routing behavior for external traffic.
11. External LB balances across nodes; Kubernetes Service balances across Ready Pods.
12. port-forward is a debug tunnel, not production exposure.
```