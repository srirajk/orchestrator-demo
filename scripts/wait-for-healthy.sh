#!/usr/bin/env bash
# Wait until all core docker-compose services report healthy, then exit 0.
# Usage: ./scripts/wait-for-healthy.sh [timeout_seconds]
set -euo pipefail

TIMEOUT=${1:-120}
INTERVAL=5
ELAPSED=0

SERVICES=(conduit-redis conduit-gateway meridian-mock-agents conduit-mongodb meridian-librechat)

echo "⏳  Waiting for services to become healthy (timeout: ${TIMEOUT}s)..."

while true; do
  all_healthy=true

  for svc in "${SERVICES[@]}"; do
    status=$(docker inspect --format '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "missing")
    if [[ "$status" != "healthy" ]]; then
      echo "   $svc → $status"
      all_healthy=false
    fi
  done

  if $all_healthy; then
    echo "✅  All services healthy after ${ELAPSED}s."
    exit 0
  fi

  ELAPSED=$(( ELAPSED + INTERVAL ))
  if (( ELAPSED >= TIMEOUT )); then
    echo "❌  Timeout after ${TIMEOUT}s. Services still not healthy:"
    for svc in "${SERVICES[@]}"; do
      status=$(docker inspect --format '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "missing")
      echo "   $svc → $status"
    done
    exit 1
  fi

  sleep "$INTERVAL"
done
