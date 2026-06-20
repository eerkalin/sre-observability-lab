# Week 2, Day 2 — Spring Boot Production Readiness: Configuration and Logging

## Goal

Make application-service configurable, diagnosable and closer to production readiness.

## Responsibility model

Developer-owned:
- ApplicationSettings implementation
- Controller changes
- RequestLoggingFilter implementation
- Application behavior changes

SRE-owned:
- Operational requirements
- Runtime validation
- Logging standard
- Smoke tests
- Failure testing
- Runbook
- Production readiness checklist

Shared:
- application.yml
- profiles
- log format
- actuator exposure policy

## Configuration

- Added externalized configuration
- Added ApplicationSettings with @ConfigurationProperties
- Added @ConfigurationPropertiesScan
- Added application.yml
- Added application-local.yml
- Added application-prodlike.yml
- Added environment variable overrides

## Logging standard

Required fields:

- time
- level
- service
- env
- requestId
- method
- path
- status
- durationMs
- message

## Request correlation

- Added X-Request-Id support
- Request ID is returned in response header
- Request ID is included in logs

## SRE validation

- local profile tested
- prodlike profile tested
- env variables tested
- wrong profile tested
- invalid env variable type tested
- SERVER_PORT override tested
- Actuator loggers endpoint tested
- DEBUG enabled temporarily and reverted
- requestId validated in response and logs

## Runbook

- runbooks/spring-boot-configuration-logging-issue.md

## Key lessons

- Configuration must be externalized
- Profiles separate environment behavior
- Wrong profile can silently fall back to base config
- Logs must be structured and searchable
- Request ID is mandatory for incident investigation
- DEBUG logging is a temporary diagnostic tool
- Actuator loggers is useful but must be protected
- SRE defines operational requirements and validates implementation
