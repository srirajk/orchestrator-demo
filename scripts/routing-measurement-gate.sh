#!/usr/bin/env bash
# Fleet routing measurement gate — runs the live gateway resolver against the
# labeled goal-pick dataset and fails on low domain accuracy, poor abstention,
# or canonical-intent poaching.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
IAM_URL="${IAM_URL:-http://localhost:8084}"
DATASET="${DATASET:-$ROOT/eval/goal-pick/labeled_queries.json}"
OUTPUT="${OUTPUT:-/tmp/routing-measurement-gate.json}"

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  Conduit — Routing Measurement Gate"
echo "  Gateway: ${GATEWAY_URL}"
echo "  Dataset: ${DATASET}"
echo "═══════════════════════════════════════════════════════════════════════"

python3 "$ROOT/eval/goal-pick/measure_goal_pick.py" \
  --gateway-url "$GATEWAY_URL" \
  --iam-url "$IAM_URL" \
  --dataset "$DATASET" \
  --manifest-root "$ROOT/registry/manifests" \
  --output "$OUTPUT"
