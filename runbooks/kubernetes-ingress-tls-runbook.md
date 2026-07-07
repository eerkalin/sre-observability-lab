# Kubernetes Ingress TLS and HTTPS Runbook

## Purpose

This runbook explains how TLS works with Kubernetes Ingress, how ingress-nginx terminates HTTPS traffic, how TLS Secrets are used, and how to troubleshoot common certificate and HTTPS issues.

It covers:

```text
TLS termination
self-signed certificates
Kubernetes TLS Secret
Ingress tls block
SNI
Host header
curl -k
curl --resolve
multiple HTTPS hosts
HTTP to HTTPS redirect
TLS troubleshooting
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
Mac localhost:8080 → NodePort 30080 → ingress-nginx HTTP
Mac localhost:8443 → NodePort 30443 → ingress-nginx HTTPS
```

Ingress Controller:

```text
ingress-nginx-controller
namespace: ingress-nginx
IngressClass: nginx
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

## TLS termination model

In this lab, TLS terminates at the Ingress Controller.

```text
Client
  ↓ HTTPS
ingress-nginx-controller
  ↓ HTTP
Kubernetes Service
  ↓
Pod
```

Important:

```text
Adding HTTPS to Ingress does not make the backend application HTTPS.
The backend can still be plain HTTP inside the cluster.
```

Production alternatives:

```text
TLS termination at Ingress
End-to-end TLS
mTLS through service mesh
```

---

## Kubernetes TLS Secret

A TLS Secret stores:

```text
tls.crt — public certificate
tls.key — private key
```

Secret type:

```text
kubernetes.io/tls
```

Rule:

```text
TLS Secret must be in the same namespace as the Ingress object that references it.
```

Example:

```text
Ingress: sre-lab/application-service-ingress
TLS Secret: sre-lab/app-localhost-tls

Ingress: monitoring/grafana-service-ingress
TLS Secret: monitoring/grafana-localhost-tls
```

---

## Self-signed certificate

For lab purposes, create a self-signed certificate for `app.localhost`:

```bash
mkdir -p tls

openssl req -x509 -nodes -days 365 \
  -newkey rsa:2048 \
  -keyout tls/app.localhost.key \
  -out tls/app.localhost.crt \
  -subj "/CN=app.localhost" \
  -addext "subjectAltName=DNS:app.localhost"
```

Important fields:

```text
CN = Common Name
SAN = Subject Alternative Name
```

Modern clients validate SAN.

Check SAN:

```bash
openssl x509 -in tls/app.localhost.crt -text -noout | grep -A2 "Subject Alternative Name"
```

Expected:

```text
DNS:app.localhost
```

---

## Create TLS Secret for app.localhost

```bash
kubectl create secret tls app-localhost-tls \
  --cert=tls/app.localhost.crt \
  --key=tls/app.localhost.key \
  -n sre-lab
```

Check:

```bash
kubectl get secret app-localhost-tls -n sre-lab
kubectl describe secret app-localhost-tls -n sre-lab
```

Expected:

```text
Type: kubernetes.io/tls
Data:
  tls.crt
  tls.key
```

---

## Git hygiene

Private keys must not be committed to Git.

Add to `.gitignore`:

```gitignore
tls/*.key
tls/*.crt
```

For production use:

```text
cert-manager
External Secrets
Vault
cloud certificate manager
corporate CA automation
sealed-secrets or SOPS for encrypted Git storage
```

Do not store private keys as plain text in Git.

---

## Ingress TLS block

Application Ingress with TLS:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: application-service-ingress
  namespace: sre-lab
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - app.localhost
      secretName: app-localhost-tls
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

Meaning:

```text
For HTTPS requests to app.localhost, use Secret app-localhost-tls.
```

Apply:

```bash
kubectl apply -f k8s/ingress/application-service-ingress.yaml
```

Check:

```bash
kubectl describe ingress application-service-ingress -n sre-lab
```

Expected:

```text
TLS:
  app-localhost-tls terminates app.localhost
```

---

## HTTPS check

Use `curl -k` because the lab certificate is self-signed.

```bash
curl -k -i \
  --resolve app.localhost:8443:127.0.0.1 \
  https://app.localhost:8443/actuator/health
```

Expected:

```text
HTTP 200
```

Without `-k`:

```bash
curl -i \
  --resolve app.localhost:8443:127.0.0.1 \
  https://app.localhost:8443/actuator/health
```

Expected:

```text
SSL certificate problem: self-signed certificate
```

Diagnosis:

```text
TLS works, but the certificate issuer is not trusted by the client.
```

---

## SNI

SNI means Server Name Indication.

It is the hostname sent by the client during the TLS handshake.

Ingress Controller uses SNI to choose the correct certificate.

Check app.localhost certificate:

```bash
openssl s_client -connect localhost:8443 -servername app.localhost -showcerts
```

Expected:

```text
subject=CN=app.localhost
Verify return code: 18 (self-signed certificate)
```

Check Grafana certificate:

```bash
openssl s_client -connect localhost:8443 -servername grafana.localhost -showcerts
```

Expected:

```text
subject=CN=grafana.localhost
Verify return code: 18 (self-signed certificate)
```

Without SNI:

```bash
openssl s_client -connect localhost:8443 -showcerts
```

The controller may return a default certificate.

---

## Host header vs SNI

In HTTPS there are two relevant names:

```text
SNI — used during TLS handshake to select certificate
Host header — used after TLS to route HTTP request
```

Example:

```bash
curl -k -i -H "Host: app.localhost" https://localhost:8443/actuator/health
```

Here:

```text
SNI: localhost
Host header: app.localhost
```

This may still route correctly because `-k` skips certificate validation and HTTP routing uses Host header.

More production-like check:

```bash
curl -k -i \
  --resolve app.localhost:8443:127.0.0.1 \
  https://app.localhost:8443/actuator/health
```

Here:

```text
SNI: app.localhost
Host header: app.localhost
```

This better simulates real DNS behavior.

---

## TLS for grafana.localhost

Create certificate:

```bash
openssl req -x509 -nodes -days 365 \
  -newkey rsa:2048 \
  -keyout tls/grafana.localhost.key \
  -out tls/grafana.localhost.crt \
  -subj "/CN=grafana.localhost" \
  -addext "subjectAltName=DNS:grafana.localhost"
```

Create Secret in `monitoring` namespace:

```bash
kubectl create secret tls grafana-localhost-tls \
  --cert=tls/grafana.localhost.crt \
  --key=tls/grafana.localhost.key \
  -n monitoring
```

Grafana Ingress:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-service-ingress
  namespace: monitoring
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - grafana.localhost
      secretName: grafana-localhost-tls
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
curl -k -i \
  --resolve grafana.localhost:8443:127.0.0.1 \
  https://grafana.localhost:8443/login
```

Expected:

```text
HTTP 200 or 302
```

---

## Multiple HTTPS hosts on one Ingress Controller

One Ingress Controller can serve multiple HTTPS hosts:

```text
SNI: app.localhost
  ↓
Secret: sre-lab/app-localhost-tls
  ↓
Service: application-service

SNI: grafana.localhost
  ↓
Secret: monitoring/grafana-localhost-tls
  ↓
Service: grafana
```

Important:

```text
TLS Secret is selected based on SNI and Ingress TLS configuration.
HTTP backend routing happens after TLS termination.
```

---

## HTTP to HTTPS redirect

nginx-ingress annotation:

```yaml
nginx.ingress.kubernetes.io/ssl-redirect: "true"
```

Meaning:

```text
Redirect HTTP requests to HTTPS when TLS is configured.
```

Check HTTP:

```bash
curl -i -H "Host: app.localhost" http://localhost:8080/actuator/health
```

Expected:

```text
HTTP 301 / 302 / 308 redirect
Location: https://app.localhost/actuator/health
```

Lab caveat:

```text
In production HTTPS normally uses port 443.
In this kind lab HTTPS is exposed on localhost:8443.
Redirect Location may not include :8443.
```

Direct HTTPS check:

```bash
curl -k -i \
  --resolve app.localhost:8443:127.0.0.1 \
  https://app.localhost:8443/actuator/health
```

Expected:

```text
HTTP 200
```

---

## Backend remains HTTP

Check internal backend:

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
exit
```

Expected:

```text
HTTP 200
```

Conclusion:

```text
TLS terminates at Ingress Controller.
Backend service remains HTTP.
```

---

## Troubleshooting chain

```text
Client / curl
  ↓
DNS / --resolve
  ↓
SNI
  ↓
localhost:8443 / external LB:443
  ↓
kind port mapping / NodePort 30443
  ↓
ingress-nginx-controller
  ↓
Ingress tls section
  ↓
TLS Secret in same namespace
  ↓
certificate SAN/CN
  ↓
Host header routing
  ↓
backend Service
  ↓
Endpoints
  ↓
Ready Pods
```

Commands:

```bash
kubectl get ingress -A
kubectl describe ingress application-service-ingress -n sre-lab
kubectl describe ingress grafana-service-ingress -n monitoring

kubectl get secret app-localhost-tls -n sre-lab
kubectl describe secret app-localhost-tls -n sre-lab

kubectl get secret grafana-localhost-tls -n monitoring
kubectl describe secret grafana-localhost-tls -n monitoring

kubectl get svc ingress-nginx-controller -n ingress-nginx
kubectl logs -n ingress-nginx deploy/ingress-nginx-controller --tail=100
```

---

## Drill 1: Wrong TLS Secret name

Break:

```yaml
tls:
  - hosts:
      - app.localhost
    secretName: app-localhost-tls-broken
```

Symptom:

```text
Request may still route with curl -k,
but openssl may show default/fake certificate.
```

Check:

```bash
kubectl get secret app-localhost-tls-broken -n sre-lab
openssl s_client -connect localhost:8443 -servername app.localhost -showcerts
```

Root cause:

```text
Ingress references a TLS Secret that does not exist in the Ingress namespace.
```

Fix:

```yaml
secretName: app-localhost-tls
```

---

## Drill 2: Secret in wrong namespace

Rule:

```text
Ingress can only reference TLS Secret in its own namespace.
```

Example:

```text
sre-lab/application-service-ingress
  can use sre-lab/app-localhost-tls

monitoring/grafana-service-ingress
  can use monitoring/grafana-localhost-tls
```

Wrong mental model:

```text
Ingress in monitoring can use Secret from sre-lab.
```

Correct mental model:

```text
Secret lookup is namespace-local.
```

---

## Drill 3: Certificate/SNI check

Check correct cert:

```bash
openssl s_client -connect localhost:8443 -servername app.localhost -showcerts
```

Expected:

```text
subject=CN=app.localhost
```

Check wrong SNI:

```bash
openssl s_client -connect localhost:8443 -servername wrong.localhost -showcerts
```

Expected:

```text
default certificate or different certificate
```

---

## Drill 4: Host header vs SNI

Less production-like:

```bash
curl -k -i -H "Host: app.localhost" https://localhost:8443/actuator/health
```

Production-like:

```bash
curl -k -i \
  --resolve app.localhost:8443:127.0.0.1 \
  https://app.localhost:8443/actuator/health
```

Use `--resolve` to simulate DNS and set both correct SNI and Host header.

---

## Drill 5: Backend still HTTP

Check from inside the cluster:

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
exit
```

Expected:

```text
HTTP 200
```

Conclusion:

```text
TLS termination does not change backend protocol.
```

---

## Final validation

```bash
kubectl get ingress -A
kubectl get secret app-localhost-tls -n sre-lab
kubectl get secret grafana-localhost-tls -n monitoring
kubectl get svc ingress-nginx-controller -n ingress-nginx
```

Check app:

```bash
curl -k -i \
  --resolve app.localhost:8443:127.0.0.1 \
  https://app.localhost:8443/actuator/health
```

Check Grafana:

```bash
curl -k -i \
  --resolve grafana.localhost:8443:127.0.0.1 \
  https://grafana.localhost:8443/login
```

Check redirect:

```bash
curl -i -H "Host: app.localhost" http://localhost:8080/actuator/health
```

Expected:

```text
app HTTPS → 200
grafana HTTPS → 200 or 302
app HTTP → redirect to HTTPS
```

---

## Key lessons

```text
1. TLS Secret stores tls.crt and tls.key.
2. TLS Secret type is kubernetes.io/tls.
3. TLS Secret must be in the same namespace as the Ingress.
4. Ingress tls block maps host to secretName.
5. Ingress Controller terminates TLS.
6. Backend can remain HTTP after TLS termination.
7. Self-signed certificates are not trusted by default.
8. curl -k skips certificate trust validation.
9. SNI selects certificate during TLS handshake.
10. Host header selects route after TLS termination.
11. curl --resolve is better than only setting Host header for HTTPS tests.
12. Multiple HTTPS hosts can share one Ingress Controller.
13. Wrong Secret often results in default/fake certificate.
14. HTTP to HTTPS redirect is controlled by nginx annotation.
15. For production, use cert-manager, corporate CA, or managed certificate automation.
```