#!/usr/bin/env bash
# Eval release gate — seeds Langfuse datasets, then runs the DeepEval routing
# accuracy gate against the live gateway. Exits non-zero if the gateway is
# unreachable or routing accuracy is below the threshold.
#
# Usage:
#   ./scripts/eval-gate.sh
#   GATEWAY_URL=http://localhost:8080 EVAL_THRESHOLD=0.75 ./scripts/eval-gate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
EVAL_THRESHOLD="${EVAL_THRESHOLD:-0.75}"

# Map compose-style Langfuse project keys onto the names the eval scripts expect,
# so a single .env works for both `docker compose` and bare-metal runs.
export LANGFUSE_PUBLIC_KEY="${LANGFUSE_PUBLIC_KEY:-${LANGFUSE_PROJECT_PUBLIC_KEY:-pk-lf-meridian-public}}"
export LANGFUSE_SECRET_KEY="${LANGFUSE_SECRET_KEY:-${LANGFUSE_PROJECT_SECRET_KEY:-sk-lf-meridian-secret}}"
export LANGFUSE_HOST="${LANGFUSE_HOST:-http://localhost:3030}"

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  Meridian Gateway — Eval Release Gate"
echo "  Gateway: ${GATEWAY_URL}   Threshold: ${EVAL_THRESHOLD}"
echo "═══════════════════════════════════════════════════════════════════════"

# ── [1/3] Gateway reachability ───────────────────────────────────────────────
echo ""
echo "▶  [1/3] Checking gateway is reachable..."
if ! curl -sf "${GATEWAY_URL}/v1/models" >/dev/null 2>&1; then
  echo "   ❌  Gateway not reachable at ${GATEWAY_URL}/v1/models"
  echo "       Start the stack first:  docker compose up -d  (then ./scripts/wait-for-healthy.sh)"
  exit 1
fi
echo "   ✅  Gateway reachable at ${GATEWAY_URL}/v1/models"

# ── [2/3] Seed Langfuse datasets (best-effort; tolerate already-exists) ──────
echo ""
echo "▶  [2/3] Seeding Langfuse datasets (conduit-routing + conduit-synthesis)..."
if python3 "$ROOT/eval/langfuse_seed_datasets.py"; then
  echo "   ✅  Datasets seeded (or already present)."
else
  echo "   ⚠️   Dataset seeding skipped/failed (non-fatal) — continuing to the gate."
fi

# ── [3/3] DeepEval routing accuracy gate ─────────────────────────────────────
echo ""
echo "▶  [3/3] Running DeepEval routing gate (threshold ${EVAL_THRESHOLD})..."
LOG="$(mktemp)"
set +e
python3 "$ROOT/eval/eval_deepeval.py" \
  --gateway-url "${GATEWAY_URL}" \
  --threshold "${EVAL_THRESHOLD}" 2>&1 | tee "$LOG"
GATE_RC=${PIPESTATUS[0]}
set -e

ACCURACY_LINE="$(grep -E 'ROUTING ACCURACY' "$LOG" | tail -1 || true)"
rm -f "$LOG"

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
if [[ "$GATE_RC" -eq 0 ]]; then
  echo "  ✅  EVAL GATE: PASS"
else
  echo "  ❌  EVAL GATE: FAIL"
fi
[[ -n "$ACCURACY_LINE" ]] && echo "  ${ACCURACY_LINE}"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""

exit "$GATE_RC"
