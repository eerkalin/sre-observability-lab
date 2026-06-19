# Week 2, Day 1 — First Spring Boot Service

## Goal

Create the first local Java Spring Boot service for future SRE, Docker, AWS and Kubernetes labs.

## Service

- Name: application-service
- Language: Java 21
- Framework: Spring Boot
- Build tool: Maven Wrapper
- Dependencies:
  - Spring Web
  - Spring Boot Actuator

## Endpoints

- GET /actuator/health
- GET /actuator/info
- GET /actuator/metrics
- GET /api/v1/applications/{id}
- POST /api/v1/applications
- GET /api/v1/failure/500
- GET /api/v1/failure/slow

## Diagnostics

- Process checked with ps
- Listening port checked with lsof
- Health endpoint checked with curl
- 500 error reproduced
- Slow response reproduced
- Port conflict reproduced
- Application built as jar
- Application run with java -jar

## Scripts

- scripts/run-application-service-local.sh
- scripts/test-application-service-local.sh

## Runbook

- runbooks/spring-boot-service-unhealthy.md

## Key lessons

- Health UP does not mean all business endpoints work
- HTTP 500 is an application-level failure
- Slow response is a latency problem, not necessarily downtime
- Port conflict prevents startup
- java -jar is closer to production than mvn spring-boot:run
