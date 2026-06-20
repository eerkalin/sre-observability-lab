# Runbook: Spring Boot configuration or logging issue

## Symptoms

- Application does not start
- Application starts on unexpected port
- Wrong environment is shown
- Feature flag has unexpected value
- Health endpoint exposes too much or too little detail
- Logs are missing requestId
- Logs are too noisy
- Logs do not contain status or durationMs
- DEBUG logging was enabled and not reverted

## SRE first questions

1. Which profile is active?
2. Which environment variables are set?
3. Which port is actually listening?
4. Does the service expose non-sensitive runtime config?
5. Does every request have requestId?
6. Are status and durationMs present in request logs?
7. Are secrets absent from logs?
8. Is DEBUG enabled only temporarily?

## Initial checks

```bash
java -version
echo "$JAVA_HOME"
env | grep -E 'SPRING|APP_|SERVER_PORT'
ps -ef | grep '[j]ava'
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:9090 -sTCP:LISTEN













