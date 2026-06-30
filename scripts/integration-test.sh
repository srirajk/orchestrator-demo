#!/usr/bin/env bash
# Curl-based integration tests against the running gateway.
# Run AFTER the stack is healthy: ./scripts/wait-for-healthy.sh && ./scripts/integration-test.sh
set -euo pipefail

GATEWAY=${GATEWAY_URL:-http://localhost:8080}
PASS=0
FAIL=0

ok()   { echo "  ✅  $1"; (( PASS++ )) || true; }
fail() { echo "  ❌  $1"; (( FAIL++ )) || true; }

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Conduit Gateway — Integration Tests  (${GATEWAY})"
echo "═══════════════════════════════════════════════════════════════"

# ── 1. Actuator health ────────────────────────────────────────────────────────
echo ""
echo "▶ 1. Actuator health"
HEALTH=$(curl -sf "${GATEWAY}/actuator/health" || echo '{"status":"DOWN"}')
if echo "$HEALTH" | grep -q '"status":"UP"'; then
  ok "GET /actuator/health → UP"
else
  fail "GET /actuator/health → expected UP, got: $HEALTH"
fi

# ── 2. Models endpoint ────────────────────────────────────────────────────────
echo ""
echo "▶ 2. GET /v1/models"
MODELS=$(curl -sf "${GATEWAY}/v1/models")

if echo "$MODELS" | grep -q '"object":"list"'; then
  ok "Response object is 'list'"
else
  fail "Response missing object=list: $MODELS"
fi

if echo "$MODELS" | grep -q '"id":"conduit-assistant"'; then
  ok "conduit-assistant model present"
else
  fail "conduit-assistant not found in models: $MODELS"
fi

if echo "$MODELS" | grep -q '"owned_by":"conduit"'; then
  ok "owned_by = conduit"
else
  fail "owned_by not conduit: $MODELS"
fi

# ── 3. Chat completions — SSE format ─────────────────────────────────────────
echo ""
echo "▶ 3. POST /v1/chat/completions (SSE stream)"
SSE=$(curl -sf -X POST "${GATEWAY}/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"conduit-assistant","messages":[{"role":"user","content":"hello conduit"}],"stream":true}' \
  --max-time 20)

if echo "$SSE" | grep -q '^data:'; then
  ok "SSE lines begin with 'data:'"
else
  fail "No 'data:' prefix found in stream"
fi

if echo "$SSE" | grep -q '"role":"assistant"'; then
  ok "First chunk contains role=assistant delta"
else
  fail "No role=assistant delta found"
fi

if echo "$SSE" | grep -q '"chat.completion.chunk"'; then
  ok "Chunks have object=chat.completion.chunk"
else
  fail "Wrong object type in chunks"
fi

if echo "$SSE" | grep -q '"finish_reason":"stop"'; then
  ok "Final chunk has finish_reason=stop"
else
  fail "No stop chunk found"
fi

if echo "$SSE" | grep -q 'data:\[DONE\]'; then
  ok "Stream ends with [DONE]"
else
  fail "Stream missing [DONE] terminator"
fi

# Check finish_reason:null in intermediate chunks (required by OpenAI spec)
INTER_CHUNKS=$(echo "$SSE" | grep '"finish_reason":null' | wc -l | tr -d ' ')
if (( INTER_CHUNKS > 0 )); then
  ok "Intermediate chunks carry finish_reason:null (${INTER_CHUNKS} found)"
else
  fail "Intermediate chunks missing finish_reason:null"
fi

# ── 4. Auto-title short-circuit ───────────────────────────────────────────────
echo ""
echo "▶ 4. Auto-title short-circuit"
TITLE_SSE=$(curl -sf -X POST "${GATEWAY}/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"conduit-assistant","messages":[{"role":"user","content":"Generate a concise title for this conversation"}],"stream":true}' \
  --max-time 10)

if echo "$TITLE_SSE" | grep -q 'data:\[DONE\]'; then
  ok "Title request returns SSE stream ending with [DONE]"
else
  fail "Title request did not return a proper SSE stream"
fi

if echo "$TITLE_SSE" | grep -q '"content"'; then
  ok "Title stream returns content delta"
else
  fail "Title stream returned no content"
fi

# ── 5. Unknown params are accepted (dropParams simulation) ────────────────────
echo ""
echo "▶ 5. Extra LibreChat params are ignored gracefully"
EXTRA=$(curl -sf -o /dev/null -w "%{http_code}" -X POST "${GATEWAY}/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"conduit-assistant","messages":[{"role":"user","content":"test"}],"stream":true,"stop":null,"frequency_penalty":0,"presence_penalty":0}' \
  --max-time 10)

if [[ "$EXTRA" == "200" ]]; then
  ok "Extra params (stop, frequency_penalty, etc.) accepted — HTTP 200"
else
  fail "Extra params caused HTTP $EXTRA (expected 200)"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
TOTAL=$(( PASS + FAIL ))
echo "  Results: ${PASS}/${TOTAL} passed"
if (( FAIL > 0 )); then
  echo "  ❌  ${FAIL} test(s) FAILED"
  echo "═══════════════════════════════════════════════════════════════"
  exit 1
else
  echo "  ✅  All integration tests passed"
  echo "═══════════════════════════════════════════════════════════════"
  exit 0
fi
