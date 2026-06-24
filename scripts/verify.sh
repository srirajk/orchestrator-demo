#!/usr/bin/env bash
# Full verification script — runs after each phase to confirm acceptance criteria.
# Usage: ./scripts/verify.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  Meridian Gateway — Verify Script"
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
echo "$MODELS" | grep -q '"id":"meridian-assistant"'
echo "   ✅  GET /v1/models → meridian-assistant found"

# /v1/chat/completions — check SSE stream contains [DONE]
STREAM=$(curl -sf -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}' \
  --max-time 15)

echo "$STREAM" | grep -q '\[DONE\]'
echo "   ✅  POST /v1/chat/completions → SSE stream contains [DONE]"

echo "$STREAM" | grep -q '"chat.completion.chunk"'
echo "   ✅  POST /v1/chat/completions → chunks have correct object type"

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  All Phase 1 checks passed ✅"
echo "  → Open LibreChat at http://localhost:3080"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""
