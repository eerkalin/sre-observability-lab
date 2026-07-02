# Kubernetes Storage Runbook

## Purpose

This runbook explains Kubernetes storage basics and troubleshooting for the lab.

It covers:

```text
stateless vs stateful workloads
volumes
emptyDir
PersistentVolume
PersistentVolumeClaim
StorageClass
PVC Pending
Reclaim Policy
storage cleanup
```

---

## Environment

Application namespace:

```text
sre-lab
```

Main application:

```text
application-service
```

Storage lab manifests:

```text
k8s/storage/storage-test-pvc.yaml
k8s/storage/storage-test-pod.yaml
k8s/storage/storage-broken-pvc.yaml
k8s/storage/storage-broken-pod.yaml
```

---

## Stateless vs Stateful

### Stateless workload

A stateless workload does not keep critical state inside the Pod filesystem.

Examples:

```text
REST API
API Gateway
frontend
stateless workers
application-service
```

Typical Kubernetes object:

```text
Deployment
```

A stateless Pod can be deleted and recreated without data loss.

### Stateful workload

A stateful workload requires persistent data, stable identity, or stable storage.

Examples:

```text
PostgreSQL
Redis with persistence
Kafka
Elasticsearch
Prometheus
Grafana with local database
Loki with local storage
```

Typical Kubernetes objects:

```text
StatefulSet
PersistentVolumeClaim
PersistentVolume
StorageClass
```

---

## Storage Mental Model

Kubernetes storage flow:

```text
Pod
  ↓ mounts
PVC
  ↓ bound to
PV
  ↓ provisioned by
StorageClass
  ↓ backed by
real storage
```

The real storage may be:

```text
local disk
cloud block storage
NFS
Ceph
Longhorn
SAN/NAS
object storage through application-specific integration
```

Important:

```text
PVC is a claim/request for storage.
PVC itself is not the physical disk.
```

---

## Storage Backend Best Practice

A good production model is:

```text
centralized storage platform or storage pool
CSI driver
StorageClass definitions
separate PVCs per stateful component
separate PVCs per StatefulSet replica
backup/snapshot/retention policies
storage monitoring
```

Do not use one shared writable volume for all stateful applications.

Better model:

```text
Grafana PVC              → separate volume
Redis PVC                → separate volume
Prometheus PVC           → separate volume
PostgreSQL replica PVCs  → one volume per replica
Camunda state            → usually external PostgreSQL/MySQL
```

---

## What Happens When a Pod Moves to Another Node

### Local storage

```text
Volume is physically tied to one Node.
Pod may need to run on that same Node.
If scheduled elsewhere, mount may fail.
```

Possible evidence:

```text
volume node affinity conflict
MountVolume failed
Pod Pending
```

### Cloud/block storage

```text
Volume can detach from old Node and attach to new Node.
Usually ReadWriteOnce.
```

Possible issues:

```text
attach timeout
detach timeout
multi-attach error
mount failed
```

### Shared filesystem

```text
Storage is visible to multiple Nodes.
Usually supports ReadWriteMany.
```

Useful for shared files, but not always ideal for databases.

---

## Volume Types

Common volume types:

```text
emptyDir
configMap
secret
persistentVolumeClaim
```

### emptyDir

`emptyDir` is created with the Pod and deleted with the Pod.

Properties:

```text
survives container restart inside the same Pod
does not survive Pod deletion
not shared between replicas
```

Good for:

```text
temporary files
cache
scratch data
intermediate processing
```

Bad for:

```text
database data
business-critical files
user uploads
state that must survive Pod recreation
```

Example:

```yaml
volumeMounts:
  - name: temp-data
    mountPath: /tmp/app-data

volumes:
  - name: temp-data
    emptyDir: {}
```

---

## PVC

PVC is a request for persistent storage.

Example:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: storage-test-pvc
  namespace: sre-lab
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```

Meaning:

```text
request 1Gi persistent storage
with ReadWriteOnce access mode
```

---

## Access Modes

Common access modes:

```text
ReadWriteOnce
ReadOnlyMany
ReadWriteMany
ReadWriteOncePod
```

Practical interpretation:

```text
ReadWriteOnce:
volume can be mounted as read-write by one Node.

ReadOnlyMany:
many Nodes can mount as read-only.

ReadWriteMany:
many Nodes can mount as read-write.

ReadWriteOncePod:
only one Pod can mount it as read-write.
```

---

## StorageClass

StorageClass describes the type of storage and the provisioner.

Check StorageClasses:

```bash
kubectl get storageclass
kubectl get sc
kubectl get sc -o yaml
```

Look for default annotation:

```text
storageclass.kubernetes.io/is-default-class: "true"
```

If a PVC does not specify `storageClassName`, Kubernetes may use the default StorageClass.

If there is no default StorageClass, the PVC may stay Pending.

---

## Storage Baseline Checks

```bash
kubectl get storageclass
kubectl get sc
kubectl get pv
kubectl get pvc -A
kubectl get nodes
```

Check PVC:

```bash
kubectl describe pvc storage-test-pvc -n sre-lab
```

Check PV:

```bash
kubectl get pv
kubectl describe pv <pv-name>
```

Important fields:

```text
Status
Volume
StorageClass
Capacity
Access Modes
VolumeMode
Used By
Reclaim Policy
Claim
```

---

## PVC Experiment

Create PVC:

```bash
kubectl apply -f k8s/storage/storage-test-pvc.yaml
```

Check:

```bash
kubectl get pvc -n sre-lab
kubectl describe pvc storage-test-pvc -n sre-lab
```

Create Pod:

```bash
kubectl apply -f k8s/storage/storage-test-pod.yaml
```

Write file:

```bash
kubectl exec -it storage-test-pod -n sre-lab -- sh
```

Inside:

```sh
echo "hello from pvc" > /data/pvc-test.txt
cat /data/pvc-test.txt
exit
```

Delete Pod but keep PVC:

```bash
kubectl delete pod storage-test-pod -n sre-lab
```

Recreate Pod:

```bash
kubectl apply -f k8s/storage/storage-test-pod.yaml
```

Verify persistence:

```bash
kubectl exec -it storage-test-pod -n sre-lab -- cat /data/pvc-test.txt
```

Expected:

```text
hello from pvc
```

Conclusion:

```text
Pod was deleted.
PVC remained.
New Pod mounted the same PVC.
Data persisted.
```

---

## Pod Name vs PVC

Direct Pod manifest:

```yaml
kind: Pod
metadata:
  name: storage-test-pod
```

creates a Pod with exact name:

```text
storage-test-pod
```

Deployment creates Pods with generated names:

```text
application-service-<replicaset-hash>-<random-suffix>
```

StatefulSet creates stable ordinal names:

```text
redis-0
redis-1
redis-2
```

Important:

```text
Pod name does not store data.
Data is stored in the PVC/PV.
```

---

## PV/PVC Anatomy

Check PVC:

```bash
kubectl describe pvc storage-test-pvc -n sre-lab
```

Look for:

```text
Status: Bound
Volume: <pv-name>
StorageClass:
Capacity:
Access Modes:
Used By:
```

Check PV:

```bash
kubectl describe pv <pv-name>
```

Look for:

```text
StorageClass
Status
Claim
Reclaim Policy
Access Modes
Capacity
Source
Node Affinity
```

Meaning:

```text
PVC is bound to PV.
PV is backed by real storage.
Pod uses PVC.
```

---

## Reclaim Policy

Reclaim Policy defines what happens to PV/backend storage after PVC deletion.

Common values:

```text
Delete
Retain
```

### Delete

```text
PVC deleted
PV deleted
backend storage usually deleted
data deleted
```

Often default for dynamic provisioning.

### Retain

```text
PVC deleted
PV remains
backend data remains
manual cleanup or recovery required
```

Safer for critical data but operationally heavier.

Important:

```text
Deleting a Pod is usually safe for PVC data.
Deleting a PVC can delete the underlying data depending on Reclaim Policy.
```

---

## PVC Protection

Kubernetes can protect a PVC that is actively used by a Pod.

A PVC may remain in `Terminating` until the consuming Pod releases it.

Important:

```text
PVC protection is not a backup.
It only prevents immediate deletion while the PVC is in use.
```

---

## Incident: PVC Pending

### Failure

PVC references a non-existing StorageClass:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: storage-broken-pvc
  namespace: sre-lab
spec:
  storageClassName: non-existing-storage-class
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```

### Symptoms

```text
PVC Pending
Pod using the PVC cannot start
application logs are absent
```

### Evidence

```bash
kubectl get pvc -n sre-lab
kubectl describe pvc storage-broken-pvc -n sre-lab
kubectl get pod storage-broken-pod -n sre-lab
kubectl describe pod storage-broken-pod -n sre-lab
kubectl get events -n sre-lab --sort-by=.lastTimestamp
```

Expected evidence:

```text
storageclass.storage.k8s.io "non-existing-storage-class" not found
pod has unbound immediate PersistentVolumeClaims
PersistentVolumeClaim is not bound
```

### Root Cause

```text
PVC references a non-existing StorageClass, so Kubernetes cannot provision a matching PV.
```

### Fix

Use a valid StorageClass or create the required StorageClass/provisioner.

### Prevention

```text
Validate storageClassName.
Check PVC status before deploying Pods.
Monitor PVC Pending state.
Validate storage provisioner health.
```

---

## Cleanup

Delete storage test Pod:

```bash
kubectl delete pod storage-test-pod -n sre-lab --ignore-not-found
```

Check PVC remains:

```bash
kubectl get pvc storage-test-pvc -n sre-lab
```

Check Reclaim Policy before deleting PVC:

```bash
kubectl get pv
kubectl describe pv <pv-name>
```

Delete test PVC:

```bash
kubectl delete pvc storage-test-pvc -n sre-lab --ignore-not-found
```

Check PV cleanup:

```bash
kubectl get pv
```

Delete broken test objects:

```bash
kubectl delete pod storage-broken-pod -n sre-lab --ignore-not-found
kubectl delete pvc storage-broken-pvc -n sre-lab --ignore-not-found
```

---

## Final Application Validation

```bash
kubectl get deployment application-service -n sre-lab
kubectl get pods -n sre-lab
kubectl get service application-service -n sre-lab
kubectl get endpoints application-service -n sre-lab
```

Expected:

```text
Deployment READY 2/2
application-service Pods Running 1/1
Endpoints contain two PodIP:8080 entries
```

Check from debug Pod:

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
```

Expected:

```text
HTTP 200
```

---

## Key Lessons

```text
1. Pod filesystem is temporary.
2. emptyDir is tied to Pod lifecycle.
3. emptyDir is not shared between replicas.
4. PVC data can survive Pod deletion.
5. PVC is a claim, not the physical disk.
6. PV is the bound storage resource.
7. StorageClass defines how storage is provisioned.
8. Stateful apps should use separate PVCs, not one shared writable volume.
9. For StatefulSets, each replica usually gets its own PVC.
10. Deleting Pod is usually safe for PVC data.
11. Deleting PVC can delete data depending on Reclaim Policy.
12. PVC Pending is diagnosed through PVC describe, Pod describe, and events.
13. Pod logs usually do not help if the container never starts.
```