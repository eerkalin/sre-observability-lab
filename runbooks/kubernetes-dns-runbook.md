# Kubernetes DNS Runbook

## Purpose

This runbook explains Kubernetes DNS, CoreDNS, service discovery, FQDN, namespace DNS behavior, and DNS troubleshooting.

---

## DNS mental model

```text
Pod
  ↓ DNS query
kube-dns Service
  ↓
CoreDNS Pods
  ↓ DNS response
Pod
  ↓ TCP/UDP connection
Service ClusterIP
  ↓
Endpoints / Ready Pods
```

DNS only resolves names to IPs.  
DNS does not prove that the backend application is healthy.

---

## CoreDNS

Check CoreDNS Pods:

```bash
kubectl get pods -n kube-system -l k8s-app=kube-dns -o wide
```

Check kube-dns Service:

```bash
kubectl get svc kube-dns -n kube-system
```

Expected ports:

```text
53/UDP
53/TCP
```

---

## Pod resolv.conf

```bash
kubectl exec -n sre-lab dns-debug -- cat /etc/resolv.conf
```

Typical output:

```text
nameserver 10.96.0.10
search sre-lab.svc.cluster.local svc.cluster.local cluster.local
options ndots:5
```

Meaning:

```text
nameserver → kube-dns/CoreDNS Service IP
search → DNS suffixes used for short names
ndots → controls when search suffixes are attempted
```

---

## Kubernetes Service DNS names

Short name, same namespace:

```text
application-service
```

Namespace-qualified:

```text
application-service.sre-lab
```

Full FQDN:

```text
application-service.sre-lab.svc.cluster.local
```

---

## Validation from same namespace

```bash
kubectl exec -n sre-lab dns-debug -- nslookup application-service

kubectl exec -n sre-lab dns-debug -- \
  curl -i http://application-service:8080/actuator/health
```

Expected:

```text
DNS resolves
HTTP 200
```

---

## Validation from another namespace

Short name may fail:

```bash
kubectl exec -n dns-demo dns-debug -- \
  curl --connect-timeout 3 -i http://application-service:8080/actuator/health
```

Correct namespace-qualified name:

```bash
kubectl exec -n dns-demo dns-debug -- \
  curl -i http://application-service.sre-lab:8080/actuator/health
```

Full FQDN:

```bash
kubectl exec -n dns-demo dns-debug -- \
  curl -i http://application-service.sre-lab.svc.cluster.local:8080/actuator/health
```

---

## Headless Service and StatefulSet DNS

Regular Service DNS returns ClusterIP.

Headless Service:

```text
clusterIP: None
```

Headless Service DNS can return Pod endpoint IPs.

StatefulSet Pod DNS example:

```text
redis-0.redis.sre-lab.svc.cluster.local
```

Validation:

```bash
kubectl exec -n sre-lab dns-debug -- \
  nslookup redis-0.redis.sre-lab.svc.cluster.local
```

---

## Drill 1: Wrong namespace short name

Symptom:

```text
Could not resolve host
```

Diagnosis:

```text
Short Service name is searched in the current namespace.
The Service exists in another namespace.
```

Fix:

```text
Use service.namespace or full FQDN.
```

---

## Drill 2: Wrong Service name

Check:

```bash
kubectl exec -n sre-lab dns-debug -- nslookup application-service-broken
```

Expected:

```text
NXDOMAIN
```

Diagnosis:

```text
Service name is wrong or Service does not exist.
```

---

## Drill 3: DNS works, Service has no endpoints

Break:

```bash
kubectl patch svc application-service -n sre-lab \
  -p '{"spec":{"selector":{"app":"application-service-broken"}}}'
```

DNS still works:

```bash
kubectl exec -n sre-lab dns-debug -- nslookup application-service
```

But curl fails:

```bash
kubectl exec -n sre-lab dns-debug -- \
  curl --connect-timeout 3 -i http://application-service:8080/actuator/health
```

Check endpoints:

```bash
kubectl get endpoints application-service -n sre-lab -o wide
```

Fix:

```bash
kubectl apply -f k8s/application-service/service.yaml
```

Lesson:

```text
DNS can be healthy while Service endpoints are broken.
```

---

## Drill 4: CoreDNS unavailable

Break:

```bash
kubectl scale deployment coredns -n kube-system --replicas=0
```

Check:

```bash
kubectl exec -n sre-lab dns-debug -- nslookup application-service
```

Expected:

```text
DNS timeout
```

Fix:

```bash
kubectl scale deployment coredns -n kube-system --replicas=2
kubectl rollout status deployment/coredns -n kube-system
```

---

## DNS troubleshooting commands

```bash
kubectl get svc -A
kubectl get endpoints <service> -n <namespace> -o wide

kubectl get pods -n kube-system -l k8s-app=kube-dns
kubectl get svc kube-dns -n kube-system
kubectl logs -n kube-system deployment/coredns --tail=100

kubectl exec -n <namespace> <pod> -- cat /etc/resolv.conf
kubectl exec -n <namespace> <pod> -- nslookup <service>
kubectl exec -n <namespace> <pod> -- dig <service>
kubectl exec -n <namespace> <pod> -- curl -v http://<service>:<port>
```

---

## Error classification

```text
NXDOMAIN
  → wrong service name or wrong namespace

DNS timeout
  → CoreDNS unavailable, kube-dns unreachable, or DNS egress blocked

Could not resolve host
  → DNS resolution failed

DNS resolves but curl fails
  → DNS OK; check Service, Endpoints, Pods, NetworkPolicy, Application

Short name fails but service.namespace works
  → namespace mistake

FQDN works but short name fails
  → search domain / namespace / resolv.conf issue
```

---

## Practical production patterns

Small production:

```text
CoreDNS monitoring
CoreDNS runbook
standard debug pod
DNS egress allowed by baseline NetworkPolicy
```

Enterprise:

```text
NodeLocal DNSCache
CoreDNS autoscaling
DNS latency dashboard
SERVFAIL/NXDOMAIN alerts
upstream DNS monitoring
standard platform DNS runbook
```

---

## Key lessons

```text
1. Kubernetes DNS is usually served by CoreDNS.
2. kube-dns Service exposes DNS on UDP/TCP 53.
3. Short Service names work inside the same namespace.
4. Cross-namespace calls should use service.namespace or FQDN.
5. Regular Service DNS returns ClusterIP.
6. Headless Service DNS can return Pod endpoint IPs.
7. StatefulSet Pods can have stable DNS names with Headless Service.
8. DNS success does not mean backend health.
9. DNS failure is different from Service/Endpoints failure.
10. Egress NetworkPolicy can block DNS.
11. CoreDNS is a critical platform dependency.
12. Diagnose DNS layer before changing app manifests.
```