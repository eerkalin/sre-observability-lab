# Kubernetes Apply Checklist

## Purpose

This checklist describes a safe workflow for validating and applying Kubernetes manifests.

Application manifests:

```text
k8s/application-service/
```

Namespace manifest:

```text
k8s/namespace.yaml
```

Educational examples:

```text
k8s/examples/
```

Do not apply `k8s/examples/` as part of the normal deployment flow.

---

## 1. Check current context

```bash
kubectl config current-context
```

Never apply manifests before checking the current Kubernetes context.

---

## 2. Check namespaces

```bash
kubectl get ns
```

Expected namespace:

```text
sre-lab
```

If it does not exist yet, apply:

```bash
kubectl apply -f k8s/namespace.yaml
```

---

## 3. Validate namespace manifest

Client-side dry-run:

```bash
kubectl apply --dry-run=client -f k8s/namespace.yaml
```

Server-side dry-run:

```bash
kubectl apply --dry-run=server -f k8s/namespace.yaml
```

---

## 4. Validate application manifests

Client-side dry-run:

```bash
kubectl apply --dry-run=client -f k8s/application-service/
```

Server-side dry-run:

```bash
kubectl apply --dry-run=server -f k8s/application-service/
```

---

## 5. Check diff before apply

```bash
kubectl diff -f k8s/application-service/
```

Use this before applying changes to an existing cluster.

---

## 6. Apply manifests safely

Apply namespace first:

```bash
kubectl apply -f k8s/namespace.yaml
```

Apply application manifests:

```bash
kubectl apply -f k8s/application-service/
```

---

## 7. Validate created objects

```bash
kubectl get all -n sre-lab
```

Check ConfigMap:

```bash
kubectl get configmap -n sre-lab
```

Check Secret:

```bash
kubectl get secret -n sre-lab
```

Check Deployment:

```bash
kubectl get deployment -n sre-lab
```

Check Pods:

```bash
kubectl get pods -n sre-lab
```

Check Service:

```bash
kubectl get service -n sre-lab
```

---

## 8. Troubleshooting flow

```bash
kubectl get pods -n sre-lab
kubectl describe deployment application-service -n sre-lab
kubectl get pods -l app=application-service -n sre-lab
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --previous
kubectl get endpoints application-service -n sre-lab
```

---

## 9. Safe delete

Delete application manifests:

```bash
kubectl delete -f k8s/application-service/
```

Delete namespace only when you intentionally want to remove everything inside it:

```bash
kubectl delete -f k8s/namespace.yaml
```

Warning:

Deleting a namespace deletes namespaced resources inside it.

---

## 10. Important rules

- Always check context before apply/delete.
- Prefer explicit namespace.
- Do not apply educational examples as part of normal deployment.
- Apply namespace before namespaced resources.
- ConfigMap and Secret must exist before Pods need them.
- Service selector must match Pod labels.
- Use dry-run before apply.
- Use diff before changing an existing cluster.