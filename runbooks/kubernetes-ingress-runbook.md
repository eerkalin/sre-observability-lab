# Kubernetes Ingress Controller and Ingress Rules Runbook

## Purpose

This runbook explains how Kubernetes Ingress works, how ingress-nginx routes HTTP traffic, and how to troubleshoot common Ingress issues.

It covers:

```text
Ingress Controller
Ingress object
IngressClass
host-based routing
path-based routing
rewrite-target
backend Service
EndpointSlices
404 vs 503 troubleshooting
multi-namespace Ingress rules
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
ingress-nginx
```

Kind port mappings:

```text
Mac localhost:8080 → NodePort 30080
Mac localhost:8443 → NodePort 30443
```

Ingress Controller:

```text
ingress-nginx-controller
namespace: ingress-nginx
ingressClassName: nginx
```

Applications:

```text
application-service
namespace: sre-lab
Service: application-service
Port: 8080

Grafana
namespace: monitoring
Service: grafana
Port: 3000
```

---

## Service vs Ingress

A Kubernetes Service provides stable access to Pods.

```text
Service
  ↓
selector
  ↓
Ready Pods
  ↓
Endpoints / EndpointSlices
```

A Kubernetes Ingress provides HTTP/HTTPS routing by host/path.

```text
Client
  ↓
Ingress Controller
  ↓
Ingress rule
  ↓
Service
  ↓
Pods
```

Important:

```text
Ingress object alone does not receive traffic.
Ingress Controller is the actual reverse proxy.
```

---

## Ingress Controller

Ingress Controller watches Kubernetes Ingress objects and configures its reverse proxy accordingly.

Examples:

```text
ingress-nginx
HAProxy Ingress
Traefik
Kong Ingress Controller
Contour / Envoy
Istio Gateway
OpenShift Router
```

Lab controller:

```text
ingress-nginx
```

Install for kind:

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
```

Wait:

```bash
kubectl wait --namespace ingress-nginx \
  --for=condition=Ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=180s
```

Check:

```bash
kubectl get pods -n ingress-nginx -o wide
kubectl get svc -n ingress-nginx
kubectl get ingressclass
```

Expected Service ports:

```text
80:30080/TCP
443:30443/TCP
```

If needed, patch the controller Service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: ingress-nginx-controller
  namespace: ingress-nginx
spec:
  type: NodePort
  ports:
    - name: http
      port: 80
      protocol: TCP
      targetPort: http
      nodePort: 30080
    - name: https
      port: 443
      protocol: TCP
      targetPort: https
      nodePort: 30443
```

Apply:

```bash
kubectl apply -f k8s/ingress/ingress-nginx-controller-service-patch.yaml
```

Check controller access:

```bash
curl -i http://localhost:8080
```

Expected without matching Ingress rule:

```text
HTTP/1.1 404 Not Found
server: nginx
```

This means the controller is reachable.

---

## External hostname vs internal Service name

External hostnames are used by clients and Ingress rules.

Examples:

```text
app.localhost
grafana.localhost
api.localhost
```

Internal Service names are used inside Kubernetes.

Examples:

```text
application-service
grafana
```

Example path:

```text
Client:
http://app.localhost:8080/actuator/health

Ingress:
Host: app.localhost

Backend:
Service application-service:8080
```

Important:

```text
app.localhost is not a Kubernetes Service.
application-service is not usually resolvable from the Mac browser.
```

Production equivalent:

```text
app.localhost      → app.company.kz
grafana.localhost  → grafana.company.kz
api.localhost      → api.company.kz
```

---

## Host-based routing

Application Service Ingress:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: application-service-ingress
  namespace: sre-lab
spec:
  ingressClassName: nginx
  rules:
    - host: app.localhost
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: application-service
                port:
                  number: 8080
```

Apply:

```bash
kubectl apply -f k8s/ingress/application-service-ingress.yaml
```

Check:

```bash
curl -i -H "Host: app.localhost" http://localhost:8080/actuator/health
```

Expected:

```text
HTTP/1.1 200
```

---

## Grafana Ingress

Grafana is in namespace `monitoring`.

The Ingress object must be in the same namespace as the backend Service.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-service-ingress
  namespace: monitoring
spec:
  ingressClassName: nginx
  rules:
    - host: grafana.localhost
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: grafana
                port:
                  number: 3000
```

Apply:

```bash
kubectl apply -f k8s/ingress/grafana-service-ingress.yaml
```

Check:

```bash
curl -i -H "Host: grafana.localhost" http://localhost:8080/login
```

Expected:

```text
HTTP 200 or 302
```

Important:

```text
Ingress backend Service is resolved in the namespace of the Ingress object.
Ingress in monitoring can point to Service grafana in monitoring.
Ingress in monitoring cannot directly point to Service application-service in sre-lab.
```

---

## Multiple Ingress rules

One Ingress Controller can serve many hostnames:

```text
Host: app.localhost      → application-service in sre-lab
Host: grafana.localhost  → grafana in monitoring
Host: api.localhost      → path-based application routing
```

Check all Ingress objects:

```bash
kubectl get ingress -A
```

Important:

```text
host + path combination must be unique for ingress-nginx.
```

If two Ingress objects define the same host and path, admission webhook may reject the second object.

Example error:

```text
host "grafana.localhost" and path "/" is already defined
```

Fix:

```text
Use unique host/path combinations:
app.localhost + /
grafana.localhost + /
api.localhost + /app
```

---

## path: / with pathType: Prefix

Example:

```yaml
path: /
pathType: Prefix
```

Meaning:

```text
Match all paths under this host.
```

Examples:

```text
grafana.localhost/          → grafana
grafana.localhost/login     → grafana
grafana.localhost/dashboards → grafana
grafana.localhost/api/search → grafana
```

This is common for UI applications.

---

## Path-based routing

Path-based Ingress example:

```text
api.localhost/app → application-service
```

Problem:

```text
Client requests:
/app/actuator/health

Backend expects:
/actuator/health
```

Without rewrite, backend receives `/app/actuator/health` and may return application-level 404.

---

## Rewrite target

Path-based Ingress with rewrite:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: application-service-path-ingress
  namespace: sre-lab
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$2
spec:
  ingressClassName: nginx
  rules:
    - host: api.localhost
      http:
        paths:
          - path: /app(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: application-service
                port:
                  number: 8080
```

Meaning:

```text
If request path starts with /app, remove /app before sending to backend.
```

Regex breakdown:

```text
/app        → path must start with /app
(/|$)       → after /app there is either "/" or end of string
(.*)        → everything after /app/
```

Capture groups:

```text
$1 = "/" or end marker
$2 = everything after /app/
```

Rewrite:

```text
rewrite-target: /$2
```

Examples:

```text
/app                       → /
/app/actuator/health       → /actuator/health
/app/api/v1/applications   → /api/v1/applications
```

Check:

```bash
curl -i -H "Host: api.localhost" http://localhost:8080/app/actuator/health
curl -i -H "Host: api.localhost" http://localhost:8080/app/api/v1/applications/123
```

Expected:

```text
HTTP 200 or valid backend application response
```

---

## pathType: ImplementationSpecific

`ImplementationSpecific` means path interpretation is controlled by the Ingress Controller implementation.

In this lab:

```text
Controller: ingress-nginx
Regex-like path: /app(/|$)(.*)
```

Use `ImplementationSpecific` because this is nginx-ingress-specific behavior.

Standard path types:

```text
Prefix
Exact
ImplementationSpecific
```

Use:

```text
Prefix — for normal prefix matching
Exact — for exact path matching
ImplementationSpecific — when using controller-specific behavior, such as nginx regex/rewrite
```

---

## Production routing patterns

Host-based routing:

```text
app.company.kz      → application-service
grafana.company.kz  → grafana
payment.company.kz  → payment-service
```

Usually cleaner for UI applications.

Path-based routing:

```text
api.company.kz/app      → application-service
api.company.kz/payment  → payment-service
api.company.kz/scoring  → scoring-service
```

Useful for API gateway style routing.

Caution:

```text
Path-based routing may require rewrite or application base path support.
UI applications may break if served under sub-path without configuration.
```

---

## Ingress change and Deployment rollout

Changing Ingress does not require application Deployment rollout.

Flow:

```text
kubectl apply ingress.yaml
  ↓
Ingress Controller sees Kubernetes API change
  ↓
Controller reloads reverse proxy config
  ↓
Traffic uses new routing
```

Deployment rollout is needed when changing:

```text
image
env
container command/args
probes
resources
volume mounts
startup behavior
```

Ingress rule changes are routing changes, not application deployment changes.

---

## Troubleshooting chain

General chain:

```text
Client/curl
  ↓
DNS / Host header
  ↓
kind port mapping / external LB
  ↓
ingress-nginx-controller Service
  ↓
IngressClass
  ↓
Ingress host/path rule
  ↓
backend Service
  ↓
Service Endpoints
  ↓
Ready Pods
```

Commands:

```bash
kubectl get ingress -A
kubectl describe ingress <ingress-name> -n <namespace>
kubectl get ingressclass
kubectl get svc -n ingress-nginx
kubectl get pods -n ingress-nginx -o wide
kubectl logs -n ingress-nginx deploy/ingress-nginx-controller --tail=100
kubectl get svc <service-name> -n <namespace>
kubectl get endpoints <service-name> -n <namespace> -o wide
kubectl get pods -n <namespace> -o wide
```

---

## 404 vs 503

### nginx 404

Usually means:

```text
Ingress Controller received the request,
but no matching host/path rule was found.
```

Common causes:

```text
wrong Host
wrong path
Ingress object missing
wrong ingressClassName
```

### nginx 503

Usually means:

```text
Ingress rule matched,
but backend/upstream is unavailable.
```

Common causes:

```text
backend Service does not exist
Service endpoints are empty
Pods are not Ready
wrong Service selector
wrong backend service port
```

Quick rule:

```text
404 → match/routing rule problem
503 → backend/upstream problem
```

---

## Drill 1: Wrong Host

Request:

```bash
curl -i -H "Host: wrong.localhost" http://localhost:8080/actuator/health
```

Expected:

```text
HTTP/1.1 404 Not Found
server: nginx
```

Root cause:

```text
No Ingress rule for Host: wrong.localhost.
```

Fix:

```text
Use app.localhost, grafana.localhost, or api.localhost.
```

---

## Drill 2: Wrong Path

Request:

```bash
curl -i -H "Host: api.localhost" http://localhost:8080/wrong/actuator/health
```

Expected:

```text
HTTP/1.1 404 Not Found
server: nginx
```

Root cause:

```text
Host matched, but path did not match /app(/|$)(.*).
```

Fix:

```bash
curl -i -H "Host: api.localhost" http://localhost:8080/app/actuator/health
```

---

## Drill 3: Wrong backend Service name

Break:

```yaml
backend:
  service:
    name: application-service-broken
    port:
      number: 8080
```

Expected:

```text
HTTP/1.1 503 Service Temporarily Unavailable
```

Root cause:

```text
Ingress matched, but backend Service does not exist.
```

Fix:

```yaml
backend:
  service:
    name: application-service
    port:
      number: 8080
```

---

## Drill 4: Backend Service exists, but Endpoints empty

Break Service selector:

```yaml
selector:
  app: application-service-broken
```

Check:

```bash
kubectl get endpoints application-service -n sre-lab
```

Expected:

```text
Endpoints: <none>
```

Ingress request:

```bash
curl -i -H "Host: app.localhost" http://localhost:8080/actuator/health
```

Expected:

```text
HTTP/1.1 503 Service Temporarily Unavailable
```

Root cause:

```text
Backend Service exists, but has no Ready endpoints.
```

Fix selector:

```yaml
selector:
  app: application-service
```

---

## Drill 5: Wrong IngressClass

Break:

```yaml
spec:
  ingressClassName: wrong-nginx
```

Expected:

```text
Ingress object exists, but ingress-nginx ignores it.
Request may return nginx 404.
```

Root cause:

```text
IngressClass does not match controller class.
```

Fix:

```yaml
ingressClassName: nginx
```

---

## Final validation

```bash
kubectl get ingress -A
kubectl get ingressclass
kubectl get svc -n ingress-nginx
kubectl get pods -n ingress-nginx -o wide
kubectl get endpoints application-service -n sre-lab -o wide
kubectl get endpoints grafana -n monitoring -o wide
```

Check routes:

```bash
curl -i -H "Host: app.localhost" http://localhost:8080/actuator/health

curl -i -H "Host: grafana.localhost" http://localhost:8080/login

curl -i -H "Host: api.localhost" http://localhost:8080/app/actuator/health
```

Expected:

```text
app.localhost → 200
grafana.localhost/login → 200 or 302
api.localhost/app/actuator/health → 200
```

---

## Key lessons

```text
1. Ingress object defines routing rules.
2. Ingress Controller is the actual reverse proxy.
3. IngressClass connects Ingress object to a controller.
4. External hostnames are not Kubernetes Service names.
5. app.localhost is a lab external hostname.
6. application-service is an internal Kubernetes Service name.
7. One Ingress Controller can serve many hosts.
8. host + path must be unique in ingress-nginx.
9. path: / with Prefix matches all paths under a host.
10. Path-based routing may require rewrite.
11. rewrite-target is nginx-ingress-specific.
12. ImplementationSpecific is used for controller-specific path behavior.
13. Backend Service must be in the same namespace as the Ingress object.
14. Changing Ingress does not require application Deployment rollout.
15. nginx 404 usually means host/path/class mismatch.
16. nginx 503 usually means backend/upstream problem.
```