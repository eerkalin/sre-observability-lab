#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "Testing health endpoint..."
curl -fsS "$BASE_URL/actuator/health"
echo

echo "Testing application lookup..."
curl -fsS "$BASE_URL/api/v1/applications/123"
echo

echo "Testing create application..."
curl -fsS -X POST "$BASE_URL/api/v1/applications"
echo

echo "Testing slow endpoint..."
curl -s -o /dev/null \
  -w "http_code=%{http_code} time_total=%{time_total}\n" \
  "$BASE_URL/api/v1/failure/slow?delayMs=500"
