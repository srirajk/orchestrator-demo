#!/usr/bin/env bash
# Full verification script — runs after each phase to confirm acceptance criteria.
# Usage: ./scripts/verify.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  Conduit Gateway — Verify Script"
echo "═══════════════════════════════════════════════════════════════════════"

# ── Phase 1: unit tests ───────────────────────────────────────────────────────
echo ""
echo "▶  [1/3] Building gateway and running unit tests..."
cd "$ROOT/gateway"
mvn test -q
echo "   ✅  Unit tests passed."

# ── Phase 1: bring up compose stack ──────────────────────────────────────────
echo ""
echo "▶  [2/3] Starting docker-compose stack..."
cd "$ROOT"
docker compose up -d --build
"$ROOT/scripts/wait-for-healthy.sh" 180

# ── Phase 1: API smoke ────────────────────────────────────────────────────────
echo ""
echo "▶  [3/3] API smoke tests..."

# /v1/models
MODELS=$(curl -sf http://localhost:8080/v1/models)
echo "$MODELS" | grep -q '"id":"conduit-assistant"'
echo "   ✅  GET /v1/models → conduit-assistant found"

# /v1/chat/completions — check SSE stream contains [DONE]
STREAM=$(curl -sf -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"conduit-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}' \
  --max-time 15)

echo "$STREAM" | grep -q '\[DONE\]'
echo "   ✅  POST /v1/chat/completions → SSE stream contains [DONE]"

echo "$STREAM" | grep -q '"chat.completion.chunk"'
echo "   ✅  POST /v1/chat/completions → chunks have correct object type"

# ── World B gate: the gateway must carry zero domain knowledge ───────────────
# Hard gate (the refactor reached CRITICAL 0). Fails the build if any domain
# name / client name / entity-field literal / REL-/FND- pattern / domain copy
# re-enters gateway/src/main/java. See docs/WORLD-B-LOCKDOWN.md.
echo ""
echo "▶  [world-b] checking the gateway carries no domain knowledge..."
"$ROOT/scripts/world-b-check.sh" --quiet
echo "   ✅  World B clean — no domain knowledge in the gateway."

# ── Optional: eval release gate (set RUN_EVAL=1 to include) ──────────────────
if [[ "${RUN_EVAL:-0}" == "1" ]]; then
  echo ""
  echo "▶  [eval] RUN_EVAL=1 — running the eval release gate..."
  "$ROOT/scripts/eval-gate.sh"
  echo "   ✅  Eval gate passed."
fi

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  All Phase 1 checks passed ✅"
echo "  → Open LibreChat at http://localhost:3080"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""
