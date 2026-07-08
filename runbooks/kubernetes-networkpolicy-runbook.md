# Kubernetes NetworkPolicy Runbook

## Purpose

This runbook explains how Kubernetes NetworkPolicy controls Pod-to-Pod traffic.

It covers:

```text
default allow
default deny ingress
allow ingress by podSelector
namespace isolation
namespaceSelector + podSelector
default deny egress
DNS egress
allow egress to backend
NetworkPolicy troubleshooting
```

---

## Mental model

NetworkPolicy controls L3/L4 traffic:

```text
IP
TCP/UDP
ports
Pod labels
Namespace labels
```

It does not control:

```text
HTTP paths
HTTP headers
JWT claims
business users
application roles
```

---

## Ingress vs NetworkPolicy ingress

Kubernetes Ingress:

```text
external HTTP/HTTPS routing into services
```

NetworkPolicy ingress:

```text
incoming network traffic into selected Pods
```

---

## Egress

Egress means outgoing traffic from selected Pods.

```text
frontend-client
  ↓ egress
backend
```

To make traffic work under NetworkPolicy isolation, both sides may matter:

```text
source egress must allow traffic
destination ingress must allow traffic
```

---

## Default deny egress

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: frontend-default-deny-egress
  namespace: netpol-demo
spec:
  podSelector:
    matchLabels:
      role: frontend
  policyTypes:
    - Egress
  egress: []
```

Meaning:

```text
frontend Pods cannot initiate any outgoing connections.
```

---

## Allow DNS egress

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: frontend-allow-dns-egress
  namespace: netpol-demo
spec:
  podSelector:
    matchLabels:
      role: frontend
  policyTypes:
    - Egress
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
```

Why:

```text
Service names require DNS resolution.
If DNS egress is blocked, curl http://backend may fail before connecting to backend.
```

---

## Allow egress to backend

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: frontend-allow-backend-egress
  namespace: netpol-demo
spec:
  podSelector:
    matchLabels:
      role: frontend
  policyTypes:
    - Egress
  egress:
    - to:
        - podSelector:
            matchLabels:
              role: backend
      ports:
        - protocol: TCP
          port: 80
```

Meaning:

```text
frontend Pods may connect to backend Pods on TCP/80.
```

---

## Validation

Frontend should work:

```bash
kubectl exec -n netpol-demo frontend-client -- \
  curl --connect-timeout 3 -s -o /dev/null -w "%{http_code}\n" http://backend
```

Expected:

```text
200
```

Random should still be blocked:

```bash
kubectl exec -n netpol-demo random-client -- \
  curl --connect-timeout 3 -s -o /dev/null -w "%{http_code}\n" http://backend
```

Expected:

```text
000
```

---

## Important lesson

```text
If egress default deny is enabled, remember DNS.
Many failures look like backend issues, but the real root cause is blocked DNS.
```
---

## Default deny ingress

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: backend-default-deny-ingress
  namespace: netpol-demo
spec:
  podSelector:
    matchLabels:
      role: backend
  policyTypes:
    - Ingress
  ingress: []
```

Meaning:

```text
All incoming traffic to Pods with role=backend is denied unless another policy allows it.
```

---

## Allow ingress from frontend

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: backend-allow-frontend
  namespace: netpol-demo
spec:
  podSelector:
    matchLabels:
      role: backend
  policyTypes:
    - Ingress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              role: frontend
      ports:
        - protocol: TCP
          port: 80
```

Meaning:

```text
Pods with role=frontend in the same namespace may connect to backend Pods on TCP/80.
```

---

## Allow ingress from another namespace

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: backend-allow-external-namespace
  namespace: netpol-demo
spec:
  podSelector:
    matchLabels:
      role: backend
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              access: external
          podSelector:
            matchLabels:
              role: external
      ports:
        - protocol: TCP
          port: 80
```

Meaning:

```text
Pods with role=external from namespaces labeled access=external may connect to backend Pods on TCP/80.
```

Important:

```text
namespaceSelector and podSelector in the same item mean AND.
Both namespace label and pod label must match.
```

---

## Policies are additive

NetworkPolicy uses allow-list logic.

```text
default deny
  +
allow frontend
  +
allow monitoring
```

Result:

```text
frontend is allowed
monitoring is allowed
everything else remains blocked
```

Kubernetes NetworkPolicy does not use firewall-style ordered explicit deny rules.

---

## Troubleshooting drill

### Drill 1: Wrong source label

Break:

```bash
kubectl label pod frontend-client -n netpol-demo role=frontend-broken --overwrite
```

Expected:

```text
frontend-client cannot reach backend
curl returns 000 or timeout
```

Diagnosis:

```text
backend ingress policy allows role=frontend, but source Pod label is role=frontend-broken.
```

Fix:

```bash
kubectl label pod frontend-client -n netpol-demo role=frontend --overwrite
```

---

### Drill 2: Wrong destination label

Break:

```bash
BACKEND_POD=$(kubectl get pod -n netpol-demo -l role=backend -o jsonpath='{.items[0].metadata.name}')
kubectl label pod "$BACKEND_POD" -n netpol-demo role=backend-broken --overwrite
```

Check:

```bash
kubectl get pods -n netpol-demo --show-labels
kubectl describe svc backend -n netpol-demo
kubectl get endpoints backend -n netpol-demo -o wide
kubectl get networkpolicy -n netpol-demo
```

Important lesson:

```text
Service selectors and NetworkPolicy podSelectors may use different labels.
Wrong labels can either block traffic or accidentally remove policy protection.
```

Fix:

```bash
kubectl label pod "$BACKEND_POD" -n netpol-demo role=backend --overwrite
```

---

### Drill 3: DNS egress blocked

Break:

```bash
kubectl delete -f k8s/networkpolicy/frontend-allow-dns-egress.yaml
```

Check:

```bash
kubectl exec -n netpol-demo frontend-client -- \
  curl --connect-timeout 3 -v http://backend
```

Expected:

```text
DNS resolution failure or timeout
```

Diagnosis:

```text
frontend egress default deny is active, but DNS egress to kube-dns is not allowed.
```

Fix:

```bash
kubectl apply -f k8s/networkpolicy/frontend-allow-dns-egress.yaml
```

---

### Drill 4: Wrong namespace label

Break:

```bash
kubectl label namespace external-demo access=broken --overwrite
```

Check:

```bash
kubectl exec -n external-demo external-client -- \
  curl --connect-timeout 3 -s -o /dev/null -w "%{http_code}\n" http://backend.netpol-demo.svc.cluster.local
```

Expected:

```text
000
```

Diagnosis:

```text
namespaceSelector does not match because namespace label changed.
```

Fix:

```bash
kubectl label namespace external-demo access=external --overwrite
```

---

## Troubleshooting commands

```bash
kubectl get networkpolicy -n netpol-demo
kubectl describe networkpolicy <policy-name> -n netpol-demo

kubectl get pods -n netpol-demo --show-labels
kubectl get pods -A --show-labels

kubectl get ns --show-labels

kubectl get svc -n netpol-demo
kubectl describe svc backend -n netpol-demo

kubectl get endpoints backend -n netpol-demo -o wide

kubectl exec -n netpol-demo frontend-client -- \
  curl --connect-timeout 3 -v http://backend
```

---

## Error classification

```text
HTTP 200
  → traffic allowed and application responded

HTTP 403
  → request reached application, denied by application layer

HTTP 404
  → request reached application/proxy, route/resource not found

HTTP 500
  → request reached application, app/dependency error

curl 000 / timeout
  → network path blocked, DNS issue, NetworkPolicy, Pod unreachable, port issue

Could not resolve host
  → DNS resolution issue, often blocked egress to kube-dns

Connection refused
  → endpoint reachable, but no process listens on target port
```

---

## Final validation

Frontend to backend:

```bash
kubectl exec -n netpol-demo frontend-client -- \
  curl --connect-timeout 3 -s -o /dev/null -w "%{http_code}\n" http://backend
```

Expected:

```text
200
```

Random to backend:

```bash
kubectl exec -n netpol-demo random-client -- \
  curl --connect-timeout 3 -s -o /dev/null -w "%{http_code}\n" http://backend
```

Expected:

```text
000
```

External namespace to backend:

```bash
kubectl exec -n external-demo external-client -- \
  curl --connect-timeout 3 -s -o /dev/null -w "%{http_code}\n" http://backend.netpol-demo.svc.cluster.local
```

Expected:

```text
200
```

---

## Key lessons

```text
1. NetworkPolicy requires CNI enforcement.
2. NetworkPolicy controls L3/L4 traffic, not HTTP paths or users.
3. No NetworkPolicy means default allow.
4. Once a Pod is selected by an ingress policy, only explicitly allowed ingress traffic is accepted.
5. Once a Pod is selected by an egress policy, only explicitly allowed egress traffic is accepted.
6. Default deny + explicit allow is the production pattern.
7. podSelector without namespaceSelector works only inside the policy namespace.
8. namespaceSelector + podSelector in the same item means AND.
9. NetworkPolicies are additive allow-lists.
10. There is no ordered firewall-style explicit deny in standard Kubernetes NetworkPolicy.
11. Egress default deny usually requires explicit DNS allow.
12. Blocked DNS can look like backend failure.
13. Service selectors and NetworkPolicy selectors can use different labels.
14. Empty endpoints are not the same as NetworkPolicy block.
15. curl 000/timeout often indicates network-layer blocking.
```

