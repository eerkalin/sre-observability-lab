# Docker Application Service Checklist

## Purpose

This runbook describes how to build, run, validate, and troubleshoot the `application-service` Docker container.

It is intended for DevOps, Platform, and SRE validation.

## Service

- Service name: `application-service`
- Docker image: `application-service:local`
- Container name: `application-service-local`
- Application port inside container: `8080`
- Local port mapping: `8080:8080`
- Health endpoint: `/actuator/health`
- Prometheus endpoint: `/actuator/prometheus`

---

## 1. Build the Spring Boot JAR

Go to the service directory:

```bash
cd ~/Projects/sre-observability-lab/app/application-service

Build the application:
```bash
./mvnw clean package
```
**Expected result:**
```text
BUILD SUCCESS
```

Check that the JAR exists:
```bash
ls target/*.jar
```
**Expected example:**
```text
target/application-service-0.0.1-SNAPSHOT.jar
```

---

### 2. Build Docker Image

Build the local image:
```bash
docker build -t application-service:local .
```

Check that the image exists:
```bash
docker images | grep application-service
```
**Expected result:**
```text
application-service   local
```

---

### 3. Run Container

Remove old container if it exists:
```bash
docker rm -f application-service-local 2>/dev/null || true
```

Run the container:
```bash
docker run -d \
  --name application-service-local \
  -e SPRING_PROFILES_ACTIVE=local \
  -e APP_ENVIRONMENT=local-docker \
  -p 8080:8080 \
  application-service:local
```

Check running containers:
```bash
docker ps
```
**Expected result:**
```text
application-service-local   Up
```
**After healthcheck completes, expected status:**
```text
Up ... (healthy)
```

---

### 4. Validate Health

```bash
curl -i http://localhost:8080/actuator/health
```
**Expected result:**
```text
HTTP/1.1 200
```
**Expected body:**
```json
{
  "status": "UP"
}
```

---

### 5. Validate Runtime Configuration

```bash
curl -s http://localhost:8080/api/v1/config
```
**Expected values:**
```text
environment = local-docker
service = application-service
```

Check environment variables inside the container:
```bash
docker exec application-service-local printenv | grep -E 'SPRING|APP_'
```
**Expected values:**
```text
SPRING_PROFILES_ACTIVE=local
APP_ENVIRONMENT=local-docker
```

---

### 6. Validate Logs

Show container logs:
```bash
docker logs application-service-local
```

Follow logs in real time:
```bash
docker logs -f application-service-local
```

Generate traffic:
```bash
curl -s http://localhost:8080/api/v1/applications/123
```
**Expected result:**
* Request appears in logs.
* Logs are available through Docker `stdout`/`stderr`.

---

### 7. Validate Prometheus Metrics

```bash
curl -s http://localhost:8080/actuator/prometheus | head -20
```

Check HTTP metrics:
```bash
curl -s http://localhost:8080/actuator/prometheus \
  | grep http_server_requests \
  | head -10
```

Generate traffic if HTTP metrics are missing:
```bash
curl -s http://localhost:8080/api/v1/applications/123
curl -s -X POST http://localhost:8080/api/v1/applications
```
**Expected metrics:**
* `http_server_requests_seconds_count`
* `http_server_requests_seconds_sum`
* `http_server_requests_seconds_max`

---

### 8. Validate Non-Root User

```bash
docker exec application-service-local whoami
```
**Expected result:**
```text
appuser
```

Check Java process:
```bash
docker exec application-service-local ps aux
```
**Expected result:**
* Java process is running.
* Process is **not** running as root.

---

### 9. Validate Port Mapping

```bash
docker port application-service-local
```
**Expected result:**
```text
8080/tcp -> 0.0.0.0:8080
```

If port 8080 is busy, check:
```bash
lsof -i :8080
docker ps
```

Alternative run command with another local port:
```bash
docker run -d \
  --name application-service-local-9090 \
  -e SPRING_PROFILES_ACTIVE=local \
  -e APP_ENVIRONMENT=local-docker \
  -p 9090:8080 \
  application-service:local
```
Then check:
```bash
curl -i http://localhost:9090/actuator/health
```

---

### 10. Validate Docker Healthcheck

Check container status:
```bash
docker ps
```
**Expected result:**
```text
Up ... (healthy)
```

Inspect health details:
```bash
docker inspect application-service-local \
  --format '{{json .State.Health}}'
```

If status is **unhealthy**, check:
```bash
docker logs application-service-local
curl -i http://localhost:8080/actuator/health
docker exec application-service-local printenv | grep -E 'SPRING|APP_|SERVER_PORT'
```

---

### 11. Check Resource Usage

```bash
docker stats --no-stream application-service-local
```

Review critical metrics:
* **CPU %**
* **MEM USAGE / LIMIT**
* **NET I/O**
* **BLOCK I/O**
* **PIDS**

**SRE Interpretation:**
* **High CPU** may indicate high load or inefficient code execution.
* **High memory** may indicate a leak, bad JVM configuration, or insufficient limits.
* **High PIDS** may indicate uncontrolled thread or process growth.

---

### 12. Troubleshooting Flow

#### Container is not visible in `docker ps`
Check all containers:
```bash
docker ps -a
```
If container is `Exited`, check logs:
```bash
docker logs application-service-local
```

#### Container exited immediately
Check status:
```bash
docker ps -a
```
Check logs:
```bash
docker logs application-service-local
```
Check exit code:
```bash
docker inspect application-service-local \
  --format 'ExitCode={{.State.ExitCode}}'
```
**Common causes:**
* Invalid environment variable.
* JAR not found.
* Java startup failure.
* Configuration binding error.
* Port/config mismatch.

#### Health endpoint does not respond
Check container status:
```bash
docker ps
```
Check logs:
```bash
docker logs application-service-local
```
Check port mapping:
```bash
docker port application-service-local
```
Check application config:
```bash
docker exec application-service-local printenv | grep -E 'SERVER_PORT|SPRING|APP_'
```

#### Profile is not applied
Check environment variables inside container:
```bash
docker exec application-service-local printenv | grep SPRING_PROFILES_ACTIVE
```
Check runtime config endpoint:
```bash
curl -s http://localhost:8080/api/v1/config
```
Check startup logs:
```bash
docker logs application-service-local | grep -i profile
```

#### Port conflict
**Symptom:**
```text
Bind for 0.0.0.0:8080 failed: port is already allocated
```
Check:
```bash
lsof -i :8080
docker ps
```
Fix:
```bash
docker stop application-service-local
docker rm application-service-local
```
Or run with a different local port using `-p 9090:8080`.

---

### 13. Cleanup

Stop the container:
```bash
docker stop application-service-local
```

Remove the stopped container:
```bash
docker rm application-service-local
```

Remove stopped containers if needed:
```bash
docker container prune
```
*Note: Do not remove the local image `application-service:local` if it is still needed for the course.*