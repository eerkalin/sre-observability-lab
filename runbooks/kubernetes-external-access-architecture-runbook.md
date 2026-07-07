## External access incident drill

### Drill 1: Wrong Host

Command:

```bash
curl -k -i \
  --resolve wrong.localhost:8443:127.0.0.1 \
  https://wrong.localhost:8443/actuator/health
```

Expected:

```text
HTTP 404 from nginx
```

Diagnosis:

```text
Traffic reached ingress-nginx, but no Ingress rule matched the Host.
```

Fix:

```text
Use a valid host configured in Ingress, for example app.localhost.
```

---

### Drill 2: Wrong Path

Command:

```bash
curl -i \
  -H "Host: api.localhost" \
  http://localhost:8080/wrong/actuator/health
```

Expected:

```text
HTTP 404 from nginx
```

Diagnosis:

```text
Host matched, but path did not match the Ingress path rule.
```

Fix:

```text
Use the correct external path, for example /app/actuator/health.
```

---

### Drill 3: Wrong backend Service in Ingress

Broken config:

```yaml
backend:
  service:
    name: application-service-broken
```

Expected:

```text
HTTP 503 from nginx
```

Diagnosis:

```text
Ingress rule matched, but backend Service does not exist.
```

Useful commands:

```bash
kubectl describe ingress demo-certmanager-ingress -n sre-lab
kubectl get svc application-service-broken -n sre-lab
kubectl get svc application-service -n sre-lab
```

Fix:

```yaml
backend:
  service:
    name: application-service
```

---

### Drill 4: Broken Service selector

Break:

```bash
kubectl patch svc application-service -n sre-lab \
  -p '{"spec":{"selector":{"app":"application-service-broken"}}}'
```

Expected:

```text
Service endpoints become empty.
Ingress returns HTTP 503 from nginx.
```

Useful commands:

```bash
kubectl describe svc application-service -n sre-lab
kubectl get pods -n sre-lab --show-labels
kubectl get endpoints application-service -n sre-lab -o wide
```

Root cause:

```text
Service selector does not match Pod labels.
```

Fix:

```bash
kubectl apply -f k8s/application-service/service.yaml
```

---

### Drill 5: Backend application error

Command:

```bash
curl -k -i \
  --resolve app.localhost:8443:127.0.0.1 \
  https://app.localhost:8443/api/v1/failure/500
```

Expected:

```text
HTTP 500 from application
```

Diagnosis:

```text
Traffic passed through DNS/port/TLS/Ingress/Service/Endpoints and reached the application.
The problem is inside the application or its dependencies.
```

Useful commands:

```bash
kubectl logs -n sre-lab deploy/application-service --tail=100
kubectl get pods -n sre-lab
```

---

## Error classification

```text
DNS error / cannot resolve
  → DNS layer

Connection refused / timeout
  → LB / firewall / port / NodePort / Service exposure

TLS certificate error
  → certificate / SNI / trust / cert-manager / Secret

nginx 404
  → Ingress Host / Path / IngressClass

nginx 503
  → backend Service / Endpoints / Pods

application 500
  → application / dependency / business logic
```