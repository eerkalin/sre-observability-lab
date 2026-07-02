# Kubernetes StatefulSet and Redis Runbook

## Purpose

This runbook explains how Redis works on Kubernetes with StatefulSet, Headless Service, PVC, and persistence.

It covers:

```text
Deployment vs StatefulSet
stable Pod identity
Headless Service
volumeClaimTemplates
Redis persistence
PVC lifecycle
StatefulSet scaling
PVC deletion data loss
Redis maxmemory behavior
orphan PVC cleanup
```

---

## Environment

Namespace:

```text
sre-lab
```

Redis manifests:

```text
k8s/redis/redis-headless-service.yaml
k8s/redis/redis-statefulset.yaml
```

Expected objects:

```text
Service: redis
StatefulSet: redis
Pod: redis-0
PVC: redis-data-redis-0
Port: 6379
```

---

## Deployment vs StatefulSet

Deployment Pods have generated disposable names:

```text
application-service-<replicaset-hash>-<random-suffix>
```

If a Deployment Pod is deleted, the replacement gets a different name.

StatefulSet Pods have stable ordinal names:

```text
redis-0
redis-1
redis-2
```

If `redis-0` is deleted, Kubernetes recreates `redis-0`.

Important:

```text
StatefulSet Pod name is based on StatefulSet metadata.name plus ordinal number.
Container name does not define the Pod name.
```

Example:

```yaml
kind: StatefulSet
metadata:
  name: redis
```

creates:

```text
redis-0
```

---

## StatefulSet Identity

StatefulSet provides:

```text
stable Pod name
stable network identity
stable PVC per ordinal
ordered startup
ordered termination
```

For Redis:

```text
redis-0 → redis-data-redis-0
redis-1 → redis-data-redis-1
```

---

## Headless Service

StatefulSet commonly uses a Headless Service.

Example:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: sre-lab
spec:
  clusterIP: None
  selector:
    app: redis
  ports:
    - name: redis
      port: 6379
      targetPort: redis
```

Important:

```text
clusterIP: None
```

This enables stable DNS names for StatefulSet Pods:

```text
redis-0.redis.sre-lab.svc.cluster.local
redis-1.redis.sre-lab.svc.cluster.local
```

---

## Redis StatefulSet

Redis StatefulSet baseline:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis
  namespace: sre-lab
spec:
  serviceName: redis
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
        - name: redis
          image: redis:7.2-alpine
          imagePullPolicy: IfNotPresent
          ports:
            - name: redis
              containerPort: 6379
          command:
            - redis-server
          args:
            - "--appendonly"
            - "yes"
            - "--appendfsync"
            - "everysec"
            - "--save"
            - "900 1"
            - "--save"
            - "300 10"
            - "--save"
            - "60 10000"
            - "--dir"
            - "/data"
            - "--maxmemory"
            - "200mb"
            - "--maxmemory-policy"
            - "allkeys-lru"
          volumeMounts:
            - name: redis-data
              mountPath: /data
          resources:
            requests:
              cpu: "50m"
              memory: "64Mi"
            limits:
              cpu: "250m"
              memory: "256Mi"
          readinessProbe:
            tcpSocket:
              port: redis
            initialDelaySeconds: 5
            periodSeconds: 5
          livenessProbe:
            tcpSocket:
              port: redis
            initialDelaySeconds: 15
            periodSeconds: 10
  volumeClaimTemplates:
    - metadata:
        name: redis-data
      spec:
        accessModes:
          - ReadWriteOnce
        resources:
          requests:
            storage: 1Gi
```

---

## volumeClaimTemplates

StatefulSet uses `volumeClaimTemplates` to create one PVC per Pod ordinal.

Template name:

```text
redis-data
```

StatefulSet Pod:

```text
redis-0
```

PVC name:

```text
redis-data-redis-0
```

For replicas 3:

```text
redis-0 → redis-data-redis-0
redis-1 → redis-data-redis-1
redis-2 → redis-data-redis-2
```

---

## Deploy Redis

Apply manifests:

```bash
kubectl apply -f k8s/redis/redis-headless-service.yaml
kubectl apply -f k8s/redis/redis-statefulset.yaml
```

Check:

```bash
kubectl get statefulset redis -n sre-lab
kubectl get pods -n sre-lab -l app=redis
kubectl get pvc -n sre-lab | grep redis
```

Expected:

```text
redis StatefulSet READY 1/1
redis-0 Running 1/1
redis-data-redis-0 Bound
```

Check Redis:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli ping
```

Expected:

```text
PONG
```

---

## Redis Persistence

Redis is primarily an in-memory database.

Important model:

```text
RAM = active dataset
PV/PVC = persistence files for recovery
```

When Redis starts:

```text
1. Pod mounts PVC to /data.
2. Redis reads AOF/RDB files from /data.
3. Dataset is loaded into RAM.
4. Redis starts serving requests.
```

PVC allows data recovery after Pod restart, but Redis still needs enough RAM to hold the active dataset.

---

## AOF and RDB

Redis persistence in this lab:

```text
AOF enabled
appendfsync everysec
RDB snapshots enabled
data directory /data
```

AOF:

```text
better durability for recent writes
more disk I/O
requires rewrite
```

RDB:

```text
snapshot-based persistence
compact backup format
may lose changes since last snapshot
```

Common production compromise:

```text
appendonly yes
appendfsync everysec
RDB snapshots enabled
```

---

## Redis Memory and Kubernetes Memory Limit

Current Redis resources:

```yaml
resources:
  requests:
    cpu: "50m"
    memory: "64Mi"
  limits:
    cpu: "250m"
    memory: "256Mi"
```

Current Redis memory setting:

```text
maxmemory 200mb
```

Reason:

```text
Redis maxmemory must be lower than Kubernetes memory limit.
```

Headroom is needed for:

```text
Redis process overhead
client buffers
AOF rewrite
RDB snapshot/fork
memory fragmentation
system overhead
```

Bad configuration:

```text
Kubernetes memory limit = 256Mi
Redis maxmemory = 256mb or not set
```

Risk:

```text
Container may hit Kubernetes memory limit and get OOMKilled.
```

Better configuration:

```text
Kubernetes memory limit = 256Mi
Redis maxmemory = 200mb
```

---

## Eviction Policy

Current lab policy:

```text
maxmemory-policy allkeys-lru
```

Meaning:

```text
When Redis reaches maxmemory, it evicts least recently used keys from all keys.
```

For cache-like Redis:

```text
allkeys-lru
allkeys-lfu
```

For critical data:

```text
noeviction
```

Important:

```text
eviction is controlled degradation
OOMKilled is uncontrolled failure
```

---

## Check Redis Configuration

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli config get appendonly
kubectl exec -it redis-0 -n sre-lab -- redis-cli config get appendfsync
kubectl exec -it redis-0 -n sre-lab -- redis-cli config get dir
kubectl exec -it redis-0 -n sre-lab -- redis-cli config get maxmemory
kubectl exec -it redis-0 -n sre-lab -- redis-cli config get maxmemory-policy
```

Expected:

```text
appendonly yes
appendfsync everysec
dir /data
maxmemory approximately 200000000
maxmemory-policy allkeys-lru
```

Check persistence:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli info persistence
```

Important fields:

```text
aof_enabled
aof_current_size
aof_last_bgrewrite_status
rdb_last_bgsave_status
rdb_changes_since_last_save
```

Check memory:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli info memory
```

Important fields:

```text
used_memory_human
used_memory_peak_human
maxmemory_human
maxmemory_policy
mem_fragmentation_ratio
```

---

## Test Persistence After Pod Deletion

Write keys:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli set day25 "statefulset-redis"
kubectl exec -it redis-0 -n sre-lab -- redis-cli set persistence "pvc-backed"
kubectl exec -it redis-0 -n sre-lab -- redis-cli get day25
kubectl exec -it redis-0 -n sre-lab -- redis-cli get persistence
```

Delete Pod:

```bash
kubectl delete pod redis-0 -n sre-lab
kubectl get pods -n sre-lab -l app=redis -w
```

After `redis-0` is Running again:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli ping
kubectl exec -it redis-0 -n sre-lab -- redis-cli get day25
kubectl exec -it redis-0 -n sre-lab -- redis-cli get persistence
```

Expected:

```text
PONG
"statefulset-redis"
"pvc-backed"
```

Conclusion:

```text
redis-0 was recreated.
redis-data-redis-0 remained.
Redis mounted /data again.
Data was restored from persistence files.
```

---

## Scale StatefulSet

Scale to 2:

```bash
kubectl scale statefulset redis -n sre-lab --replicas=2
kubectl get pods -n sre-lab -l app=redis -w
```

Expected Pods:

```text
redis-0
redis-1
```

Check PVCs:

```bash
kubectl get pvc -n sre-lab | grep redis
```

Expected:

```text
redis-data-redis-0
redis-data-redis-1
```

Important:

```text
redis-0 and redis-1 do not automatically replicate data.
They are separate Redis instances unless Redis replication/cluster is configured.
```

Check:

```bash
kubectl exec -it redis-1 -n sre-lab -- redis-cli get day25
```

Expected:

```text
(nil)
```

Scale back to 1:

```bash
kubectl scale statefulset redis -n sre-lab --replicas=1
```

PVC `redis-data-redis-1` usually remains.

Reason:

```text
StatefulSet scale down does not automatically delete PVCs to prevent accidental data loss.
```

---

## DNS Checks

Use netshoot:

```bash
kubectl run netshoot \
  -n sre-lab \
  --rm -it \
  --restart=Never \
  --image=nicolaka/netshoot \
  -- bash
```

Inside:

```bash
nslookup redis
nslookup redis-0.redis.sre-lab.svc.cluster.local
nc -vz redis-0.redis.sre-lab.svc.cluster.local 6379
```

If scaled to 2:

```bash
nslookup redis-1.redis.sre-lab.svc.cluster.local
nc -vz redis-1.redis.sre-lab.svc.cluster.local 6379
```

---

## Delete/Recreate StatefulSet Without Deleting PVC

Write data:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli set day25 "statefulset-redis"
kubectl exec -it redis-0 -n sre-lab -- redis-cli set persistence "pvc-backed"
```

Delete StatefulSet:

```bash
kubectl delete statefulset redis -n sre-lab
```

Check PVC remains:

```bash
kubectl get pvc -n sre-lab | grep redis
```

Recreate StatefulSet:

```bash
kubectl apply -f k8s/redis/redis-statefulset.yaml
```

Check data:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli get day25
kubectl exec -it redis-0 -n sre-lab -- redis-cli get persistence
```

Expected:

```text
"statefulset-redis"
"pvc-backed"
```

Conclusion:

```text
StatefulSet can be deleted/recreated.
Data survives if PVC remains.
```

---

## Incident: PVC Deletion Data Loss

Dangerous production action:

```bash
kubectl delete pvc redis-data-redis-0 -n sre-lab
```

If PV Reclaim Policy is `Delete`, the underlying storage may be deleted.

Symptoms after recreating Redis:

```text
Redis starts successfully.
PVC redis-data-redis-0 is recreated.
Old keys are missing.
```

Evidence:

```bash
kubectl describe pvc redis-data-redis-0 -n sre-lab
kubectl get pv
kubectl exec -it redis-0 -n sre-lab -- redis-cli get day25
```

Expected after PVC deletion:

```text
(nil)
```

Root cause:

```text
PersistentVolumeClaim was deleted.
PV Reclaim Policy was Delete.
Underlying Redis persistence storage was deleted.
```

Prevention:

```text
Never delete stateful workload PVCs without backup, approval, reclaim policy review, and restore plan.
```

---

## Incident: Redis maxmemory Behavior

Check current config:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli config get maxmemory
kubectl exec -it redis-0 -n sre-lab -- redis-cli config get maxmemory-policy
```

Create load keys:

```bash
kubectl exec -it redis-0 -n sre-lab -- sh
```

Inside:

```sh
i=1
while [ $i -le 20000 ]; do
  redis-cli set "load:key:$i" "$(head -c 2048 /dev/zero | tr '\0' 'x')" >/dev/null
  i=$((i+1))
done
exit
```

Check memory and eviction:

```bash
kubectl exec -it redis-0 -n sre-lab -- redis-cli info memory | grep -E "used_memory_human|maxmemory_human|maxmemory_policy|mem_fragmentation_ratio"
kubectl exec -it redis-0 -n sre-lab -- redis-cli info stats | grep evicted_keys
```

Check Pod:

```bash
kubectl get pod redis-0 -n sre-lab
kubectl describe pod redis-0 -n sre-lab | grep -i "OOMKilled"
```

Expected:

```text
Redis remains Running.
OOMKilled is absent.
evicted_keys may increase after maxmemory is reached.
```

Conclusion:

```text
Redis eviction is controlled degradation.
Kubernetes OOMKilled is uncontrolled failure.
```

Cleanup load keys:

```bash
kubectl exec -it redis-0 -n sre-lab -- sh
```

Inside:

```sh
redis-cli --scan --pattern "load:key:*" | xargs -r redis-cli del
exit
```

---

## Orphan PVC Cleanup

After scale down, unused PVCs may remain:

```bash
kubectl get pvc -n sre-lab | grep redis
```

Example:

```text
redis-data-redis-1
```

Check:

```bash
kubectl describe pvc redis-data-redis-1 -n sre-lab
```

If `Used By` is empty and the ordinal is no longer needed, lab cleanup:

```bash
kubectl delete pvc redis-data-redis-1 -n sre-lab --ignore-not-found
```

Production warning:

```text
Do not delete orphan PVCs automatically.
Verify owner, data importance, backup, reclaim policy, and rollback requirements first.
```

---

## Final Validation

```bash
kubectl get statefulset redis -n sre-lab
kubectl get pods -n sre-lab -l app=redis
kubectl get svc redis -n sre-lab
kubectl get pvc -n sre-lab | grep redis
kubectl exec -it redis-0 -n sre-lab -- redis-cli ping
kubectl exec -it redis-0 -n sre-lab -- redis-cli get day25
kubectl exec -it redis-0 -n sre-lab -- redis-cli get persistence
kubectl exec -it redis-0 -n sre-lab -- redis-cli info persistence
```

Expected:

```text
StatefulSet READY 1/1
redis-0 Running 1/1
Headless Service redis exists
PVC redis-data-redis-0 Bound
Redis PONG
```

---

## Key Lessons

```text
1. StatefulSet gives stable Pod identity.
2. Pod names come from StatefulSet metadata.name plus ordinal number.
3. Container name does not define Pod name.
4. Headless Service enables stable DNS for StatefulSet Pods.
5. volumeClaimTemplates creates one PVC per Pod ordinal.
6. redis-0 uses redis-data-redis-0.
7. Deleting redis-0 is safe if PVC remains.
8. Deleting StatefulSet is usually safe for data if PVC remains.
9. Deleting PVC can delete Redis data.
10. Redis stores active data in RAM.
11. PVC stores persistence files for recovery.
12. Redis maxmemory should be lower than Kubernetes memory limit.
13. Eviction policy depends on Redis role.
14. StatefulSet does not create Redis replication automatically.
15. PVC is not a backup.
```