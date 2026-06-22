# Spring Boot Metrics Checklist

## Purpose

This runbook describes how to verify that a Spring Boot service exposes technical and business metrics for SRE diagnostics and future Prometheus scraping.

## Service

- Service name: application-service
- Local URL: http://localhost:8080
- Metrics endpoint for manual inspection: /actuator/metrics
- Metrics endpoint for Prometheus scraping: /actuator/prometheus

## 1. Check that the service is running

```bash
curl -i http://localhost:8080/actuator/health
