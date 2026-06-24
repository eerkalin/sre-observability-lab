# Kubernetes Pod Lifecycle Cheatsheet

## Purpose

This document describes basic `kubectl` commands for working with a Kubernetes Pod.

The example manifest is assumed to be at: `k8s/application-service/pod.yaml`

---

### 1. Check current Kubernetes context

```bash
kubectl config current-context
```
* Use this before running commands against a cluster to ensure you are targeting the correct environment.

---

### 2. List contexts

```bash
kubectl config get-contexts
```
* Use this to see available Kubernetes clusters and configurations on your local machine.

---

### 3. Apply Pod manifest

```bash
kubectl apply -f k8s/application-service/pod.yaml
```
* This sends the desired Pod definition to the Kubernetes API Server to create or update the resource.

---

### 4. Get Pods

```bash
kubectl get pods
```

**Useful columns to review:**
* `NAME` — Identifier of the Pod.
* `READY` — Number of containers ready vs total (e.g., `1/1`).
* `STATUS` — Current lifecycle phase or error state.
* `RESTARTS` — How many times the container has restarted.
* `AGE` — Time elapsed since creation.

**Expected healthy state:**
```text
NAME                      READY   STATUS    RESTARTS   AGE
application-service-pod   1/1     Running   0          5m
```

---

### 5. Describe Pod

```bash
kubectl describe pod application-service-pod
```

**Use this for diagnosing:**
* Scheduling issues (e.g., insufficient CPU/Memory).
* Image pull failures.
* Failed health probes (Liveness/Readiness).
* Volume mount issues.
* General Kubernetes infrastructure events.

**Most important section:**
* `Events` (located at the very bottom of the output).

---

### 6. Show logs

```bash
kubectl logs application-service-pod
```

**Follow logs in real time:**
```bash
kubectl logs -f application-service-pod
```

**If the Pod has multiple containers, specify the target container:**
```bash
kubectl logs application-service-pod -c application-service
```

---

### 7. Show previous logs after restart

```bash
kubectl logs application-service-pod --previous
```
* Use this for **CrashLoopBackOff** troubleshooting to read the logs of the container *before* it crashed and restarted.

---

### 8. Execute command inside Pod

Check environment variables:
```bash
kubectl exec -it application-service-pod -- printenv
```

Check current user (security compliance):
```bash
kubectl exec -it application-service-pod -- whoami
```

Check running processes:
```bash
kubectl exec -it application-service-pod -- ps aux
```

---

### 9. Get Pod YAML from cluster

```bash
kubectl get pod application-service-pod -o yaml
```
* Use this to see the actual, fully populated object configuration currently stored in the Kubernetes database (etcd).

---

### 10. Delete Pod

Delete using the manifest file:
```bash
kubectl delete -f k8s/application-service/pod.yaml
```

Or delete directly by resource name:
```bash
kubectl delete pod application-service-pod
```

---

### 11. Basic troubleshooting flow

When a service degrades, follow this standard SRE resolution path:

```text
kubectl get pods
  ↓
kubectl describe pod <pod_name>
  ↓
kubectl logs <pod_name>
  ↓
kubectl logs <pod_name> --previous (if crashing)
  ↓
kubectl exec -it <pod_name> -- printenv (if running but misconfigured)
```

---

### 12. Common statuses

* **`Pending`** — Pod is accepted by Kubernetes, but cannot be scheduled or containers are still downloading.
* **`Running`** — Pod is bound to a node and all containers have been created. At least one is running.
* **`Succeeded`** — All containers in the Pod terminated successfully with exit code 0 (e.g., completed CronJobs).
* **`Failed`** — All containers terminated, and at least one container failed with a non-zero exit code.
* **`CrashLoopBackOff`** — Container repeatedly starts, fails, and restarts, with Kubernetes increasing the delay between attempts.
* **`ImagePullBackOff` / `ErrImagePull`** — Container cannot start because the specified Docker image name or tag is incorrect, or authentication to the registry failed.
* **`ContainerCreating`** — The container is currently being initialized, network namespaces are being set up, or storage volumes are attaching.