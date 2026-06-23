# Docker Compose Observability Stack Checklist

## Purpose

This runbook describes how to start, validate, and troubleshoot the local Docker Compose observability stack.

The stack includes:

- `application-service`
- `prometheus`
- `grafana`

The goal is to validate that the Spring Boot service exposes metrics, Prometheus scrapes them, and Grafana visualizes them through provisioned dashboards.

---

## 1. Expected project structure

```text
sre-observability-lab/
├── app/
│   └── application-service/
│       ├── Dockerfile
│       ├── .dockerignore
│       ├── pom.xml
│       └── src/
├── observability/
│   ├── prometheus/
│   │   └── prometheus.yml
│   └── grafana/
│       ├── provisioning/
│       │   ├── datasources/
│       │   │   └── datasource.yml
│       │   └── dashboards/
│       │       └── dashboard-provider.yml
│       └── dashboards/
│           └── application-service-overview.json
├── runbooks/
├── scripts/
├── compose.env
└── docker-compose.yml
```

---

## 2. Validate Docker Compose configuration

Run from repository root:

```bash
cd ~/Projects/sre-observability-lab
docker compose config
```

Expected result:

- Compose file is rendered successfully.
- No YAML parsing errors.
- Services are visible:
  - `application-service`
  - `prometheus`
  - `grafana`

If this command fails, fix `docker-compose.yml` before starting the stack.

---

## 3. Build application image

Go to application directory:

```bash
cd ~/Projects/sre-observability-lab/app/application-service
```

Build Spring Boot JAR:

```bash
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

Expected result:

```text
application-service   local
```

---

## 4. Start Docker Compose stack

Run from repository root:

```bash
cd ~/Projects/sre-observability-lab
docker compose down
docker compose up -d
```

Check services:

```bash
docker compose ps
```

Expected services:

```text
application-service
prometheus
grafana
```

Expected status:

- `application-service` is `Up` or `Up (healthy)`
- `prometheus` is `Up`
- `grafana` is `Up`

---

## 5. Validate application-service

Check health:

```bash
curl -i http://localhost:8080/actuator/health
```

Expected result:

```text
HTTP/1.1 200
```

Check runtime config:

```bash
curl -s http://localhost:8080/api/v1/config
```

Expected values:

```text
environment = local-docker
owner = sre-lab
defaultCustomerSegment = LOCAL_TEST
service = application-service
```

Check Prometheus endpoint:

```bash
curl -s http://localhost:8080/actuator/prometheus | head -20
```

Expected result:

- Prometheus-format metrics are returned.
- No HTTP 404 or connection error.

---

## 6. Validate environment variables inside container

```bash
docker compose exec application-service printenv | grep -E 'SPRING|APP_'
```

Expected values:

```text
SPRING_PROFILES_ACTIVE=local
APP_ENVIRONMENT=local-docker
APP_OWNER=sre-lab
APP_DEFAULT_CUSTOMER_SEGMENT=LOCAL_TEST
APP_SLOW_ENDPOINT_ENABLED=true
APP_DEFAULT_SLOW_DELAY_MS=1000
```

SRE note:

Do not rely only on `compose.env`. Always validate runtime environment inside the running container.

---

## 7. Validate application logs

```bash
docker compose logs application-service | tail -50
```

Generate traffic:

```bash
curl -s http://localhost:8080/api/v1/applications/123
curl -s -X POST http://localhost:8080/api/v1/applications
```

Follow logs:

```bash
docker compose logs -f application-service
```

Expected result:

- Request logs are visible.
- Logs are written to stdout/stderr.
- Logs are available through Docker Compose.

---

## 8. Validate Prometheus

Open:

```text
http://localhost:9090/targets
```

Expected target:

```text
application-service:8080
```

Expected status:

```text
UP
```

Check query in Prometheus UI:

```promql
up
```

Expected result:

```text
up{instance="application-service:8080", job="application-service"} 1
```

Check HTTP metrics:

```promql
http_server_requests_seconds_count
```

Check business metric after POST request:

```promql
business_applications_created_total
```

---

## 9. Validate Grafana

Open:

```text
http://localhost:3000
```

Default lab credentials:

```text
admin / admin
```

Expected provisioning result:

- Prometheus datasource exists.
- Dashboard folder `SRE Lab` exists.
- Dashboard `Application Service Overview` exists.

Validate datasource URL:

```text
http://prometheus:9090
```

Important:

Do not use `http://localhost:9090` inside Grafana datasource, because inside the Grafana container `localhost` means Grafana itself.

---

## 10. Validate Grafana dashboard

Open dashboard:

```text
Application Service Overview
```

Generate traffic:

```bash
curl -s http://localhost:8080/api/v1/applications/123
curl -s http://localhost:8080/api/v1/applications/456
curl -s -X POST http://localhost:8080/api/v1/applications
curl -s http://localhost:8080/api/v1/failure/500
```

Wait 15–30 seconds.

Expected panels:

- `Application service UP` shows `1`
- `HTTP request rate` shows traffic
- `HTTP 5xx error rate` reacts after `/failure/500`
- `Applications created in last 5 minutes` reacts after POST requests

---

## 11. Troubleshooting: Compose stack does not start

Check rendered configuration:

```bash
docker compose config
```

Check service status:

```bash
docker compose ps
```

Check logs:

```bash
docker compose logs
```

Check specific service logs:

```bash
docker compose logs application-service
docker compose logs prometheus
docker compose logs grafana
```

Common causes:

- YAML indentation error
- Missing Docker image `application-service:local`
- Port conflict on `8080`, `9090`, or `3000`
- Invalid environment variable in `compose.env`
- Invalid Grafana dashboard JSON
- Invalid Prometheus config

---

## 12. Troubleshooting: Prometheus target is DOWN

Check application metrics endpoint from Mac:

```bash
curl -i http://localhost:8080/actuator/prometheus
```

Check Compose services:

```bash
docker compose ps
```

Check Prometheus logs:

```bash
docker compose logs prometheus
```

Check Prometheus config:

```bash
cat observability/prometheus/prometheus.yml
```

Expected target:

```text
application-service:8080
```

Wrong target:

```text
localhost:8080
```

Reason:

Inside the Prometheus container, `localhost` means Prometheus itself, not the Spring Boot service.

---

## 13. Troubleshooting: Grafana datasource does not work

Check Grafana logs:

```bash
docker compose logs grafana | tail -100
```

Check datasource provisioning file:

```bash
cat observability/grafana/provisioning/datasources/datasource.yml
```

Expected URL:

```text
http://prometheus:9090
```

Expected UID:

```text
prometheus
```

Check Prometheus directly:

```text
http://localhost:9090/targets
```

If Prometheus target is DOWN, fix Prometheus before debugging Grafana panels.

---

## 14. Troubleshooting: Grafana dashboard does not appear

Check dashboard provider:

```bash
cat observability/grafana/provisioning/dashboards/dashboard-provider.yml
```

Check dashboard file exists:

```bash
ls -la observability/grafana/dashboards
```

Check that Grafana sees the dashboard file inside container:

```bash
docker compose exec grafana ls -la /var/lib/grafana/dashboards
```

Check Grafana logs:

```bash
docker compose logs grafana | tail -100
```

Common causes:

- Invalid JSON
- Wrong mounted path
- Wrong dashboard provider path
- Datasource UID mismatch

---

## 15. Troubleshooting: Environment variables are not applied

Check env file:

```bash
cat compose.env
```

Expected format:

```text
KEY=value
```

Wrong format:

```text
KEY: value
KEY = value
```

Check Compose file:

```bash
cat docker-compose.yml
```

Expected block:

```yaml
env_file:
  - ./compose.env
```

Recreate service:

```bash
docker compose up -d --force-recreate application-service
```

Check runtime environment:

```bash
docker compose exec application-service printenv | grep -E 'SPRING|APP_'
```

---

## 16. Useful commands

Start stack:

```bash
docker compose up -d
```

Stop and remove stack containers/network:

```bash
docker compose down
```

Show service status:

```bash
docker compose ps
```

Show logs:

```bash
docker compose logs
```

Follow logs for one service:

```bash
docker compose logs -f application-service
```

Execute command inside service container:

```bash
docker compose exec application-service printenv
```

Validate Compose YAML:

```bash
docker compose config
```

Recreate one service:

```bash
docker compose up -d --force-recreate application-service
```

Restart one service:

```bash
docker compose restart prometheus
```

---

## 17. Completion criteria

The Docker Compose observability stack is ready when:

- `docker compose config` succeeds.
- `docker compose ps` shows all services running.
- `application-service` health endpoint returns HTTP 200.
- `application-service` metrics endpoint returns Prometheus metrics.
- Runtime env variables are visible inside application container.
- Prometheus target `application-service:8080` is UP.
- Grafana datasource `Prometheus` is provisioned.
- Grafana dashboard `Application Service Overview` is provisioned.
- Dashboard panels show UP, traffic, errors, and business metric.
- Basic troubleshooting commands are documented.