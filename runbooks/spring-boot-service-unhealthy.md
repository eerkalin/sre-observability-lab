# Runbook: Spring Boot service is unhealthy

## Symptoms

- Health endpoint is DOWN
- Application returns HTTP 500
- Application is slow
- Port is not listening
- Process is not running
- Application cannot start

## Initial checks

```bash
ps -ef | grep '[j]ava'
lsof -nP -iTCP:8080 -sTCP:LISTEN
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/info
curl -s http://localhost:8080/actuator/metrics | head
