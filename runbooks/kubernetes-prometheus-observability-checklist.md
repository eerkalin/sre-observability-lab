# Kubernetes Prometheus Observability Checklist

## Purpose

This runbook describes how to monitor `application-service` inside Kubernetes using Prometheus.

It covers:

- application metrics endpoint
- Prometheus inside Kubernetes
- Service-level scrape
- pod-level discovery
- RBAC for Kubernetes service discovery
- failure simulations
- Prometheus Operator concept
- local cleanup for limited laptop resources

---

## 1. Current lab namespaces

Application namespace:

```bash
kubectl get all -n sre-lab
```

Observability namespace:

```bash
kubectl get all -n monitoring
```

**Expected:**

*   **`sre-lab`**: `application-service` Deployment / Pod / Service / ConfigMap / Secret
*   **`monitoring`**: `prometheus` Deployment / Pod / Service / ConfigMap / RBAC

---

## 2. Restore application-service

If `application-service` was scaled to zero:

```bash
kubectl scale deployment/application-service --replicas=1 -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

**Check:**

```bash
kubectl get pods -l app=application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

**Expected:**

*   Pod READY `1/1`
*   Service endpoints present

---

## 3. Validate application metrics endpoint

Open port-forward:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

Check health:

```bash
curl -i http://localhost:8080/actuator/health
```

Check Prometheus metrics:

```bash
curl -s http://localhost:8080/actuator/prometheus | head -30
```

Generate business event:

```bash
curl -s -X POST http://localhost:8080/api/v1/applications
```

Check business metric:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep business_applications_created
```

**Expected:**

*   `business_applications_created_total` appears after POST request

---

## 4. Prometheus Kubernetes manifests

Prometheus manifests are stored in:

*   `k8s/observability/namespace.yaml`
*   `k8s/observability/prometheus/configmap.yaml`
*   `k8s/observability/prometheus/deployment.yaml`
*   `k8s/observability/prometheus/service.yaml`
*   `k8s/observability/prometheus/serviceaccount.yaml`
*   `k8s/observability/prometheus/clusterrole.yaml`
*   `k8s/observability/prometheus/clusterrolebinding.yaml`

Apply:

```bash
kubectl apply -f k8s/observability/namespace.yaml
kubectl apply -f k8s/observability/prometheus/
```

Restart Prometheus after ConfigMap or RBAC changes:

```bash
kubectl rollout restart deployment/prometheus -n monitoring
kubectl rollout status deployment/prometheus -n monitoring
```

**Check:**

```bash
kubectl get pods -n monitoring
kubectl get service -n monitoring
kubectl logs -l app=prometheus -n monitoring --tail=100
```

---

## 5. Open Prometheus UI

```bash
kubectl port-forward service/prometheus 9090:9090 -n monitoring
```

Open: [http://localhost:9090](http://localhost:9090)

**Useful pages:**

*   **Status -> Targets**
*   **Graph -> PromQL queries**

---

## 6. Service-level scrape

Service-level target: `application-service.sre-lab.svc.cluster.local:8080`

**PromQL:**

```promql
up{job="application-service-service"}
```

**Expected:**

*   One logical target

**Important limitation:**

*   Service-level scrape hides pod-level details.
*   If replicas = 2, Prometheus still sees one logical Service target.

---

## 7. Pod-level discovery

Pod-level job: `application-service-pods`

**PromQL:**

```promql
up{job="application-service-pods"}
```

**Expected:**

*   One time series per Pod

Check JVM memory per Pod:

```promql
jvm_memory_used_bytes{job="application-service-pods"}
```

Check business metric per Pod:

```promql
business_applications_created_total{job="application-service-pods"}
```

Aggregate business metric:

```promql
sum(business_applications_created_total{job="application-service-pods"})
```

**Important rule:**

*   Collect metrics per runtime instance first.
*   Aggregate later in PromQL.

---

## 8. RBAC for pod-level discovery

Prometheus uses:

*   **ServiceAccount**: `prometheus`
*   **ClusterRole**: `prometheus-discovery`
*   **ClusterRoleBinding**: `prometheus-discovery`

Check ServiceAccount:

```bash
kubectl describe pod -l app=prometheus -n monitoring | grep "Service Account"
```

**Expected:**

*   `Service Account: prometheus`

Check RBAC:

```bash
kubectl get serviceaccount prometheus -n monitoring
kubectl get clusterrole prometheus-discovery
kubectl get clusterrolebinding prometheus-discovery
```

Check permission:

```bash
kubectl auth can-i list pods --as=system:serviceaccount:monitoring:prometheus -n sre-lab
```

**Expected:**

*   `yes`

---

## 9. Failure simulation: wrong metrics_path

Bad config example:

```yaml
metrics_path: "/actuator/prometheus-wrong"
```

**Expected symptoms:**

*   `application-service` healthy
*   Service endpoints present
*   Prometheus target **DOWN**
*   Last Error: **HTTP 404**

**PromQL:**

```promql
up{job="application-service-service"}
```

Expected during failure: `0`

**Diagnosis:**

```bash
kubectl get pods -n sre-lab
kubectl get endpoints application-service -n sre-lab
kubectl logs -l app=prometheus -n monitoring --tail=100
kubectl exec -it <prometheus-pod-name> -n monitoring -- cat /etc/prometheus/prometheus.yml
```

**Restore:**

```bash
kubectl apply -f k8s/observability/prometheus/configmap.yaml
kubectl rollout restart deployment/prometheus -n monitoring
kubectl rollout status deployment/prometheus -n monitoring
```

---

## 10. Failure simulation: broken RBAC discovery

Break live ClusterRoleBinding:

```bash
kubectl delete clusterrolebinding prometheus-discovery
kubectl rollout restart deployment/prometheus -n monitoring
kubectl rollout status deployment/prometheus -n monitoring
```

**Expected symptoms:**

*   Prometheus Pod **Running**
*   Static Service target may still work
*   Pod-level discovery **fails**
*   Prometheus logs show **forbidden / cannot list pods**

Check logs:

```bash
kubectl logs -l app=prometheus -n monitoring --tail=100
```

Look for: `forbidden` or `cannot list resource "pods"`

**Restore:**

```bash
kubectl apply -f k8s/observability/prometheus/clusterrolebinding.yaml
kubectl rollout restart deployment/prometheus -n monitoring
kubectl rollout status deployment/prometheus -n monitoring
```

Check:

```bash
kubectl auth can-i list pods --as=system:serviceaccount:monitoring:prometheus -n sre-lab
```

**Expected:**

*   `yes`

---

## 11. Important distinction: DOWN vs disappeared target

**Wrong metrics path:**
*   Target exists
*   Scrape fails
*   `up == 0`
*   Last Error usually HTTP 404

**Broken RBAC discovery:**
*   Prometheus cannot list Pods
*   Pod targets may **disappear**
*   Logs show forbidden

**SRE rule:** Target **DOWN** and target **missing** are different problem classes.

---

## 12. Port-forward limitation

This command:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

is a debug tunnel. It does not reliably prove Kubernetes Service load balancing.

**Possible behavior:**
*   `kubectl` chooses one backend Pod
*   All local traffic goes to that specific Pod

**Better in-cluster test:**

```bash
kubectl run curl-test \
  -n sre-lab \
  --rm -it \
  --restart=Never \
  --image=curlimages/curl:8.6.0 \
  -- sh
```

Inside the temporary Pod:

```bash
for i in $(seq 1 50); do
  curl -s -X POST http://application-service:8080/api/v1/applications >/dev/null
done
```

Then check in Prometheus:

```promql
sum by (pod) (
  increase(business_applications_created_total{job="application-service-pods"}[2m])
)
```

---

## 13. Prometheus Operator concept

Prometheus Operator was not installed locally because it is heavy for the current laptop resources.

Conceptually, the Operator replaces manual Prometheus configuration with Kubernetes-native objects:

*   `ServiceMonitor`
*   `PodMonitor`
*   `PrometheusRule`
*   `Alertmanager`
*   `Prometheus` custom resource

**Manual config:**
*   Engineer edits `prometheus.yml`
*   Prometheus reads `scrape_configs`

**Operator model:**
*   Engineer creates `ServiceMonitor` / `PodMonitor`
*   Prometheus Operator generates scrape config
*   Prometheus scrapes targets

**ServiceMonitor:** Selects Services by labels and scrapes their endpoints.

**PodMonitor:** Selects Pods by labels and scrapes Pods directly.

**PrometheusRule:** Defines alerting rules as Kubernetes objects.
