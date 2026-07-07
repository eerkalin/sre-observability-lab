# Kubernetes cert-manager Runbook

## Purpose

This runbook explains how cert-manager automates TLS certificate management in Kubernetes.

It covers:

```text
cert-manager installation
CRD verification
Issuer vs ClusterIssuer
SelfSigned ClusterIssuer
Certificate resource
cert-manager-managed TLS Secret
Ingress TLS integration
ingress-shim
CertificateRequest
cert-manager troubleshooting
```

---

## Mental model

Manual TLS flow:

```text
openssl
  ↓
kubectl create secret tls
  ↓
Ingress tls.secretName
  ↓
ingress-nginx HTTPS
```

cert-manager flow:

```text
Certificate resource
  ↓
cert-manager controller
  ↓
Issuer / ClusterIssuer
  ↓
TLS Secret
  ↓
Ingress tls.secretName
  ↓
ingress-nginx HTTPS
```

ingress-shim flow:

```text
Ingress annotation + tls block
  ↓
cert-manager ingress-shim
  ↓
Certificate auto-created
  ↓
TLS Secret auto-created
  ↓
Ingress HTTPS
```

---

## Components

cert-manager installs:

```text
cert-manager
cert-manager-cainjector
cert-manager-webhook
```

Main resources:

```text
Issuer
ClusterIssuer
Certificate
CertificateRequest
Order
Challenge
```

For this lab:

```text
ClusterIssuer
Certificate
CertificateRequest
TLS Secret
Ingress
```

---

## Issuer vs ClusterIssuer

Issuer is namespace-scoped:

```text
Issuer in namespace sre-lab
  ↓
can issue certificates only in sre-lab
```

ClusterIssuer is cluster-scoped:

```text
ClusterIssuer
  ↓
can be used by Certificates in multiple namespaces
```

In this lab we use:

```text
selfsigned-cluster-issuer
```

---

## Install cert-manager

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.20.2/cert-manager.yaml
```

Check namespace and pods:

```bash
kubectl get ns cert-manager
kubectl get pods -n cert-manager
```

Wait for deployments:

```bash
kubectl wait --for=condition=Available deployment/cert-manager -n cert-manager --timeout=180s
kubectl wait --for=condition=Available deployment/cert-manager-cainjector -n cert-manager --timeout=180s
kubectl wait --for=condition=Available deployment/cert-manager-webhook -n cert-manager --timeout=180s
```

Check CRDs:

```bash
kubectl get crd | grep cert-manager
```

Expected resources include:

```text
certificates.cert-manager.io
issuers.cert-manager.io
clusterissuers.cert-manager.io
certificaterequests.cert-manager.io
```

---

## SelfSigned ClusterIssuer

File:

```text
k8s/cert-manager/selfsigned-cluster-issuer.yaml
```

Manifest:

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: selfsigned-cluster-issuer
spec:
  selfSigned: {}
```

Apply:

```bash
kubectl apply -f k8s/cert-manager/selfsigned-cluster-issuer.yaml
```

Check:

```bash
kubectl get clusterissuer
kubectl describe clusterissuer selfsigned-cluster-issuer
```

Expected:

```text
Ready=True
```

Note:

```text
SelfSigned is useful for lab.
For production use ACME, internal CA, Vault PKI, cloud CA, or enterprise PKI.
```

---

## Certificate for app.localhost

File:

```text
k8s/cert-manager/app-localhost-certificate.yaml
```

Manifest:

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: app-localhost-cert
  namespace: sre-lab
spec:
  secretName: app-localhost-certmanager-tls
  duration: 2160h
  renewBefore: 360h
  commonName: app.localhost
  dnsNames:
    - app.localhost
  issuerRef:
    name: selfsigned-cluster-issuer
    kind: ClusterIssuer
```

Apply:

```bash
kubectl apply -f k8s/cert-manager/app-localhost-certificate.yaml
```

Check:

```bash
kubectl get certificate -n sre-lab
kubectl describe certificate app-localhost-cert -n sre-lab
kubectl get secret app-localhost-certmanager-tls -n sre-lab
kubectl describe secret app-localhost-certmanager-tls -n sre-lab
```

Expected:

```text
Certificate READY=True
Secret type: kubernetes.io/tls
Data:
  tls.crt
  tls.key
```

---

## Certificate for grafana.localhost

File:

```text
k8s/cert-manager/grafana-localhost-certificate.yaml
```

Manifest:

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: grafana-localhost-cert
  namespace: monitoring
spec:
  secretName: grafana-localhost-certmanager-tls
  duration: 2160h
  renewBefore: 360h
  commonName: grafana.localhost
  dnsNames:
    - grafana.localhost
  issuerRef:
    name: selfsigned-cluster-issuer
    kind: ClusterIssuer
```

Apply:

```bash
kubectl apply -f k8s/cert-manager/grafana-localhost-certificate.yaml
```

Check:

```bash
kubectl get certificate -n monitoring
kubectl describe certificate grafana-localhost-cert -n monitoring
kubectl get secret grafana-localhost-certmanager-tls -n monitoring
```

Expected:

```text
Certificate READY=True
Secret type: kubernetes.io/tls
```

---

## Use cert-manager Secret in Ingress

Application Ingress should reference:

```yaml
tls:
  - hosts:
      - app.localhost
    secretName: app-localhost-certmanager-tls
```

Grafana Ingress should reference:

```yaml
tls:
  - hosts:
      - grafana.localhost
    secretName: grafana-localhost-certmanager-tls
```

Apply:

```bash
kubectl apply -f k8s/ingress/application-service-ingress.yaml
kubectl apply -f k8s/ingress/grafana-service-ingress.yaml
```

Check:

```bash
kubectl describe ingress application-service-ingress -n sre-lab
kubectl describe ingress grafana-service-ingress -n monitoring
```

Expected:

```text
app-localhost-certmanager-tls terminates app.localhost
grafana-localhost-certmanager-tls terminates grafana.localhost
```

---

## HTTPS validation

Application:

```bash
curl -k -i \
  --resolve app.localhost:8443:127.0.0.1 \
  https://app.localhost:8443/actuator/health
```

Expected:

```text
HTTP 200
```

Grafana:

```bash
curl -k -i \
  --resolve grafana.localhost:8443:127.0.0.1 \
  https://grafana.localhost:8443/login
```

Expected:

```text
HTTP 200 or 302
```

Certificate check:

```bash
openssl s_client -connect localhost:8443 -servername app.localhost -showcerts
openssl s_client -connect localhost:8443 -servername grafana.localhost -showcerts
```

Expected:

```text
subject=CN=app.localhost
subject=CN=grafana.localhost
```

---

## ingress-shim

ingress-shim allows cert-manager to create Certificate automatically from Ingress annotations.

File:

```text
k8s/ingress/demo-certmanager-ingress.yaml
```

Manifest:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: demo-certmanager-ingress
  namespace: sre-lab
  annotations:
    cert-manager.io/cluster-issuer: selfsigned-cluster-issuer
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - demo.localhost
      secretName: demo-localhost-ingress-shim-tls
  rules:
    - host: demo.localhost
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
kubectl apply -f k8s/ingress/demo-certmanager-ingress.yaml
```

Check auto-created Certificate:

```bash
kubectl get certificate -n sre-lab
kubectl describe certificate demo-localhost-ingress-shim-tls -n sre-lab
kubectl get secret demo-localhost-ingress-shim-tls -n sre-lab
```

Expected:

```text
Certificate READY=True
Secret type: kubernetes.io/tls
```

Check HTTPS:

```bash
curl -k -i \
  --resolve demo.localhost:8443:127.0.0.1 \
  https://demo.localhost:8443/actuator/health
```

Expected:

```text
HTTP 200
```

---

## Explicit Certificate vs ingress-shim

Explicit Certificate:

```text
Certificate YAML
  ↓
clear Git-owned lifecycle
  ↓
better for learning and advanced control
```

ingress-shim:

```text
Ingress annotation + tls block
  ↓
less YAML
  ↓
common with Helm charts
```

Important rule:

```text
For ingress-shim, annotation alone is not enough.
Ingress also needs spec.tls.hosts and spec.tls.secretName.
```

---

## Diagnostic chain

```text
Ingress
  ↓
Certificate
  ↓
CertificateRequest
  ↓
Issuer / ClusterIssuer
  ↓
TLS Secret
  ↓
Ingress Controller
  ↓
HTTPS
```

Useful commands:

```bash
kubectl get clusterissuer
kubectl describe clusterissuer selfsigned-cluster-issuer

kubectl get certificate -A
kubectl describe certificate app-localhost-cert -n sre-lab
kubectl describe certificate grafana-localhost-cert -n monitoring

kubectl get certificaterequest -A
kubectl describe certificaterequest <name> -n <namespace>

kubectl get secret -n sre-lab | grep certmanager
kubectl get secret -n monitoring | grep certmanager

kubectl logs -n cert-manager deploy/cert-manager --tail=100
```

---

## Troubleshooting: wrong issuerRef in Certificate

Symptom:

```text
Certificate not Ready
CertificateRequest cannot be approved/issued
Issuer not found
```

Example broken config:

```yaml
issuerRef:
  name: wrong-selfsigned-cluster-issuer
  kind: ClusterIssuer
```

Check:

```bash
kubectl describe certificate app-localhost-cert -n sre-lab
kubectl get certificaterequest -n sre-lab
kubectl describe clusterissuer wrong-selfsigned-cluster-issuer
kubectl logs -n cert-manager deploy/cert-manager --tail=100
```

Root cause:

```text
Certificate.spec.issuerRef.name points to a non-existing ClusterIssuer.
```

Fix:

```yaml
issuerRef:
  name: selfsigned-cluster-issuer
  kind: ClusterIssuer
```

---

## Troubleshooting: wrong cluster-issuer annotation

Broken Ingress annotation:

```yaml
metadata:
  annotations:
    cert-manager.io/cluster-issuer: wrong-selfsigned-cluster-issuer
```

Symptom:

```text
ingress-shim creates or updates Certificate,
but Certificate does not become Ready.
```

Check:

```bash
kubectl describe ingress demo-certmanager-ingress -n sre-lab
kubectl get certificate -n sre-lab
kubectl describe certificate demo-localhost-ingress-shim-tls -n sre-lab
kubectl get certificaterequest -n sre-lab
kubectl logs -n cert-manager deploy/cert-manager --tail=100
```

Root cause:

```text
Ingress annotation references a non-existing ClusterIssuer.
```

Fix:

```yaml
cert-manager.io/cluster-issuer: selfsigned-cluster-issuer
```

---

## Troubleshooting: annotation without tls block

Incorrect mental model:

```text
cert-manager annotation alone creates a certificate.
```

Correct mental model:

```text
ingress-shim needs both:
1. cert-manager issuer annotation
2. spec.tls with hosts and secretName
```

Required:

```yaml
metadata:
  annotations:
    cert-manager.io/cluster-issuer: selfsigned-cluster-issuer
spec:
  tls:
    - hosts:
        - demo.localhost
      secretName: demo-localhost-ingress-shim-tls
```

---

## Troubleshooting: Secret exists but Certificate not Ready

Do not check only Secret.

Check Certificate status:

```bash
kubectl get certificate -n sre-lab
kubectl describe certificate app-localhost-cert -n sre-lab
```

Secret can be:

```text
old
manual
wrong type
not matching Certificate spec
missing tls.crt or tls.key
managed by another process
```

Correct check:

```bash
kubectl describe certificate app-localhost-cert -n sre-lab
kubectl get certificaterequest -n sre-lab
kubectl describe certificaterequest <name> -n sre-lab
```

---

## Troubleshooting: cert-manager OK, HTTPS 503

If:

```text
Certificate READY=True
TLS Secret exists
HTTPS returns 503
```

then TLS is probably OK.

503 means backend problem:

```text
Ingress rule matched,
but backend Service/Endpoints/Pods are unavailable.
```

Check:

```bash
kubectl get svc application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab -o wide
kubectl get pods -n sre-lab -o wide
```

---

## Error classification

```text
Certificate READY=False
  → cert-manager / issuer / CertificateRequest / Secret lifecycle

HTTPS certificate error
  → TLS / SNI / trust / Secret problem

HTTPS 404 nginx
  → Host / Path / IngressClass / Ingress rule problem

HTTPS 503 nginx
  → backend Service / Endpoints / Pods readiness problem
```

---

## Final validation

```bash
kubectl get clusterissuer
kubectl get certificate -A
kubectl get certificaterequest -A
kubectl get ingress -A
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

Check demo:

```bash
curl -k -i \
  --resolve demo.localhost:8443:127.0.0.1 \
  https://demo.localhost:8443/actuator/health
```

Expected:

```text
app.localhost → HTTP 200
grafana.localhost → HTTP 200 or 302
demo.localhost → HTTP 200
```

---

## Key lessons

```text
1. cert-manager turns certificates into Kubernetes resources.
2. Certificate describes the desired certificate.
3. Issuer / ClusterIssuer describes how to issue it.
4. cert-manager creates and maintains the TLS Secret.
5. Ingress still uses tls.secretName.
6. ClusterIssuer can be used across namespaces.
7. Issuer is namespace-scoped.
8. SelfSigned is useful for lab, not ideal for production trust.
9. ingress-shim can create Certificate from Ingress annotation.
10. ingress-shim needs annotation + tls block.
11. Certificate Ready status is more important than Secret existence.
12. CertificateRequest helps diagnose issuing problems.
13. Wrong issuerRef causes Certificate not Ready.
14. 503 after valid TLS usually means backend problem, not cert-manager problem.
15. Production setups normally use ACME, internal CA, Vault PKI, or cloud/enterprise PKI.
```