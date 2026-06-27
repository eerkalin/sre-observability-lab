# Prometheus Alerting Runbook

## Purpose

This runbook describes how to investigate Prometheus alerts for the `application-service` in the local Kubernetes SRE observability lab.

The alerts cover:

- Prometheus target availability
- HTTP 5xx error rate
- HTTP p95 latency
- Business application creation flow

---

## Environment

Application namespace:

```bash
sre-lab