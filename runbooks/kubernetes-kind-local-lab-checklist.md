# Kubernetes kind Local Lab Checklist

## Purpose

This runbook describes how to run `application-service` in a local Kubernetes cluster using `kind`.

The goal is to practice Kubernetes runtime operations safely on a local machine:

* create local kind cluster
* build application Docker image
* load image into kind
* apply Kubernetes manifests
* validate Deployment, Pods, Service, ConfigMap, Secret
* use port-forward
* inspect logs, events, runtime environment
* simulate common Kubernetes failures
* clean up resources safely

---

## 1. Preconditions

Required tools:

```bash
docker version
kubectl version --client --output=yaml
kind version
```

Expected architecture on Intel Mac:

```bash
uname -m
```

Expected:

```text
x86_64
```

Before starting the lab, stop Docker Compose stack:

```bash
cd ~/Projects/sre-observability-lab
docker compose down
docker ps
```

Expected:

```text
Only kind container should be running, or no containers before cluster creation.
```

---

## 2. Create kind cluster

Create local cluster:

```bash
kind create cluster --name sre-lab --wait 60s
```

Check clusters:

```bash
kind get clusters
```

Expected:

```text
sre-lab
```

Check current context:

```bash
kubectl config current-context
```

Expected:

```text
kind-sre-lab
```

Check nodes:

```bash
kubectl get nodes
```

Expected:

```text
sre-lab-control-plane   Ready
```

Check Docker container:

```bash
docker ps
```

Expected container:

```text
sre-lab-control-plane
```

---

## 3. Build application image

Build Spring Boot JAR:

```bash
cd ~/Projects/sre-observability-lab/app/application-service
./mvnw clean package
```

Build Docker image:

```bash
docker build -t application-service:local .
```

Check image:

```bash
docker images | grep application-service
```

Expected:

```text
application-service   local
```

---

## 4. Load image into kind

Load local Docker image into kind cluster:

```bash
kind load docker-image application-service:local --name sre-lab
```

Optional check inside kind node:

```bash
docker exec -it sre-lab-control-plane crictl images | grep application-service
```

Important:

The image `application-service:local` must be loaded into kind. Otherwise Pods may fail with:

```text
ImagePullBackOff
ErrImagePull
```

---

## 5. Apply Kubernetes manifests

Check context first:

```bash
kubectl config current-context
```

Expected:

```text
kind-sre-lab
```

Apply namespace:

```bash
cd ~/Projects/sre-observability-lab
kubectl apply --dry-run=server -f k8s/namespace.yaml
kubectl apply -f k8s/namespace.yaml
```

Apply application manifests:

```bash
kubectl apply --dry-run=server -f k8s/application-service/
kubectl apply -f k8s/application-service/
```

Expected objects:

```text
namespace/sre-lab
configmap/application-service-config
secret/application-service-secret
deployment.apps/application-service
service/application-service
```

---

## 6. Validate objects

Check all resources:

```bash
kubectl get all -n sre-lab
```

Check specific objects:

```bash
kubectl get configmap -n sre-lab
kubectl get secret -n sre-lab
kubectl get deployment application-service -n sre-lab
kubectl get service application-service -n sre-lab
kubectl get pods -l app=application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Expected healthy state:

```text
Deployment READY: 2/2
Pods READY: 1/1
Pods STATUS: Running
Service TYPE: ClusterIP
Endpoints: present
```

If local machine is under memory pressure, scale down to 1 replica:

```bash
kubectl scale deployment/application-service --replicas=1 -n sre-lab
```

---

## 7. Validate application through port-forward

Start port-forward:

```bash
kubectl port-forward service/application-service 8080:8080 -n sre-lab
```

If local port 8080 is busy:

```bash
kubectl port-forward service/application-service 18080:8080 -n sre-lab
```

Health check:

```bash
curl -i http://localhost:8080/actuator/health
```

Expected:

```text
HTTP/1.1 200
{"status":"UP"}
```

Config check:

```bash
curl -s http://localhost:8080/api/v1/config
```

Expected values:

```text
environment = local-k8s
owner = sre-lab
defaultCustomerSegment = LOCAL_TEST
```

Metrics check:

```bash
curl -s http://localhost:8080/actuator/prometheus | head -30
```

Business metric check:

```bash
curl -s -X POST http://localhost:8080/api/v1/applications
curl -s http://localhost:8080/actuator/prometheus | grep business_applications_created
```

Stop port-forward:

```text
Ctrl + C
```

---

## 8. Logs, describe, exec

Get Pods:

```bash
kubectl get pods -l app=application-service -n sre-lab
```

Logs by label:

```bash
kubectl logs -l app=application-service -n sre-lab --tail=100
kubectl logs -f -l app=application-service -n sre-lab
```

Describe Pod:

```bash
kubectl describe pod <pod-name> -n sre-lab
```

Previous logs after restart:

```bash
kubectl logs <pod-name> -n sre-lab --previous
```

Runtime env from ConfigMap:

```bash
kubectl exec -it <pod-name> -n sre-lab -- printenv | grep -E 'SPRING|APP_'
```

Secret presence check without printing value:

```bash
kubectl exec -it <pod-name> -n sre-lab -- sh -c 'printenv | grep EXTERNAL_PARTNER_API_TOKEN >/dev/null && echo "secret env exists"'
```

Runtime user:

```bash
kubectl exec -it <pod-name> -n sre-lab -- whoami
```

Expected:

```text
appuser
```

---

## 9. Scaling and self-healing

Check current state:

```bash
kubectl get deployment application-service -n sre-lab
kubectl get pods -l app=application-service -n sre-lab
kubectl get rs -n sre-lab
```

Scale down:

```bash
kubectl scale deployment/application-service --replicas=1 -n sre-lab
```

Scale up:

```bash
kubectl scale deployment/application-service --replicas=2 -n sre-lab
```

Watch Pods:

```bash
kubectl get pods -l app=application-service -n sre-lab -w
```

Self-healing test:

```bash
kubectl get pods -l app=application-service -n sre-lab
kubectl delete pod <pod-name> -n sre-lab
kubectl get pods -l app=application-service -n sre-lab -w
```

Expected:

```text
Deleted Pod is replaced by a new Pod because Deployment maintains desired replicas.
```

Rollout restart:

```bash
kubectl rollout restart deployment/application-service -n sre-lab
kubectl rollout status deployment/application-service -n sre-lab
```

Use rollout restart when ConfigMap or Secret env values change and Pods need to restart.

---

## 10. Failure simulation: broken Service selector

Break Service selector:

```bash
kubectl patch service application-service \
  -n sre-lab \
  --type='merge' \
  -p '{"spec":{"selector":{"app":"wrong-application-service"}}}'
```

Symptoms:

```bash
kubectl get pods -l app=application-service -n sre-lab --show-labels
kubectl get endpoints application-service -n sre-lab
kubectl describe service application-service -n sre-lab
```

Expected:

```text
Pods Running and Ready
Service exists
Endpoints: <none>
Selector does not match Pod labels
```

Restore from Git:

```bash
kubectl apply -f k8s/application-service/service.yaml
kubectl get endpoints application-service -n sre-lab
```

SRE rule:

If Service exists but endpoints are empty, compare Service selector with Pod labels.

---

## 11. Failure simulation: broken readinessProbe

Break readinessProbe path:

```bash
kubectl patch deployment application-service \
  -n sre-lab \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/path","value":"/actuator/health-wrong"}]'
```

Recreate Pod with bad readinessProbe:

```bash
kubectl scale deployment/application-service --replicas=0 -n sre-lab
kubectl scale deployment/application-service --replicas=1 -n sre-lab
kubectl get pods -l app=application-service -n sre-lab -w
```

Symptoms:

```text
Pod STATUS: Running
Pod READY: 0/1
Readiness probe failed
Service endpoints: <none>
```

Diagnose:

```bash
kubectl describe pod <pod-name> -n sre-lab
kubectl logs <pod-name> -n sre-lab --tail=100
kubectl get endpoints application-service -n sre-lab
```

Optional direct Pod check:

```bash
kubectl port-forward pod/<pod-name> 18080:8080 -n sre-lab
curl -i http://localhost:18080/actuator/health
curl -i http://localhost:18080/actuator/health-wrong
```

Restore from Git:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

SRE rule:

```text
Running does not mean Ready.
Ready means readinessProbe passed and Pod can receive Service traffic.
```

---

## 12. Failure simulation: ImagePullBackOff

Break image:

```bash
kubectl set image deployment/application-service \
  application-service=application-service:broken \
  -n sre-lab
```

Symptoms:

```bash
kubectl get pods -n sre-lab -w
```

Expected:

```text
ErrImagePull
ImagePullBackOff
```

Diagnose:

```bash
kubectl describe pod <bad-pod-name> -n sre-lab
kubectl get deployment application-service -n sre-lab -o yaml | grep image:
kubectl describe deployment application-service -n sre-lab
```

Application logs may be empty because container never started:

```bash
kubectl logs <bad-pod-name> -n sre-lab
```

Restore:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
kubectl get pods -l app=application-service -n sre-lab
```

If image is still missing from kind:

```bash
kind load docker-image application-service:local --name sre-lab
kubectl rollout restart deployment/application-service -n sre-lab
```

SRE rule:

ImagePullBackOff is not an application bug. It is an image delivery problem.

---

## 13. Failure simulation: missing ConfigMap reference

Scale down for local machine safety:

```bash
kubectl scale deployment/application-service --replicas=1 -n sre-lab
```

Break ConfigMap reference:

```bash
kubectl patch deployment application-service \
  -n sre-lab \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/template/spec/containers/0/envFrom/0/configMapRef/name","value":"application-service-config-missing"}]'
```

Symptoms:

```bash
kubectl get pods -n sre-lab -w
```

Expected:

```text
CreateContainerConfigError
```

Diagnose:

```bash
kubectl describe pod <bad-pod-name> -n sre-lab
kubectl get configmap application-service-config-missing -n sre-lab
kubectl get configmap application-service-config -n sre-lab
kubectl get deployment application-service -n sre-lab -o yaml | grep -A5 envFrom
```

Application logs may be empty because container never started:

```bash
kubectl logs <bad-pod-name> -n sre-lab
```

Restore:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
kubectl rollout status deployment/application-service -n sre-lab
kubectl get pods -l app=application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

SRE rule:

CreateContainerConfigError often means Kubernetes cannot prepare container config because ConfigMap, Secret, env, or volume reference is broken.

---

## 14. Cleanup options

### Option A: scale application to zero

Use this when cluster is still needed, but application Pods should stop consuming RAM:

```bash
kubectl scale deployment/application-service --replicas=0 -n sre-lab
```

Check:

```bash
kubectl get deployment application-service -n sre-lab
kubectl get pods -n sre-lab
```

Restore application:

```bash
kubectl scale deployment/application-service --replicas=1 -n sre-lab
```

or restore Git state:

```bash
kubectl apply -f k8s/application-service/deployment.yaml
```

Note:

Git manifest currently defines:

```text
replicas: 2
```

---

### Option B: delete application resources only

```bash
kubectl delete -f k8s/application-service/
```

This deletes:

```text
ConfigMap
Secret
Deployment
Service
Pods
```

Namespace remains.

---

### Option C: delete namespace

```bash
kubectl delete -f k8s/namespace.yaml
```

Warning:

Deleting namespace deletes all namespaced resources inside it.

---

### Option D: delete whole kind cluster

```bash
kind delete cluster --name sre-lab
```

Check:

```bash
kind get clusters
docker ps
```

Use this when local Kubernetes lab is no longer needed and Mac resources should be freed.

---

## 15. Resource discipline for Mac 8 GB RAM

Recommended local discipline:

```text
Before lab:
docker compose down

During lab:
run only kind cluster and application-service

If Mac slows down:
scale application-service to 0 or 1 replica

After lab:
scale application-service to 0 if continuing soon
delete kind cluster if finished for the day
```

Useful checks:

```bash
docker ps
docker stats
docker system df
kubectl get pods -A
kind get clusters
```

Avoid aggressive cleanup unless needed:

```bash
docker system prune -a
```

This may remove useful images and force slow re-downloads/rebuilds later.

---

## 16. Completion criteria

Day 12 local Kubernetes lab is successful when:

* kind is installed
* kind cluster `sre-lab` can be created
* kubectl context is `kind-sre-lab`
* `application-service:local` image can be loaded into kind
* namespace `sre-lab` is created
* application manifests apply successfully
* Deployment reaches Ready state
* Pods become Running and Ready
* Service has endpoints
* port-forward reaches `/actuator/health`
* ConfigMap env is visible in runtime
* Secret presence can be checked without exposing value
* logs and describe commands work
* scaling and self-healing are tested
* readiness failure is diagnosed
* ImagePullBackOff is diagnosed
* missing ConfigMap reference is diagnosed
* cleanup strategy is understood
