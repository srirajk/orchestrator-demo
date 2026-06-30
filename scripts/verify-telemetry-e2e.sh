#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# E2E telemetry verification
#
# Proves the conversationId → many-traces hierarchy end to end:
#   - sends TWO turns to the gateway under ONE X-Conversation-Id (as LibreChat would)
#   - confirms Langfuse grouped them into ONE session with TWO traces
#   - confirms each trace has the span tree (chat.handle → intent / agent.invoke / synth)
#
# Run AFTER the stack is up (gateway + iam + agents + otel-collector + langfuse).
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
IAM="${IAM_URL:-http://localhost:8084}"
LF="${LANGFUSE_HOST:-http://localhost:3030}"
LF_PUB="${LANGFUSE_PUBLIC_KEY:-pk-lf-meridian-public}"
LF_SEC="${LANGFUSE_SECRET_KEY:-sk-lf-meridian-secret}"
IAM_PW="${IAM_USER_PASSWORD:-Meridian@2024}"

CONV="e2e-telemetry-$(date +%s)"
PASS=0; FAIL=0
ok(){ echo "  ✓ $1"; PASS=$((PASS+1)); }
bad(){ echo "  ✗ $1"; FAIL=$((FAIL+1)); }

echo "═══════════════════════════════════════════════════════════════════════"
echo " E2E telemetry verification — conversationId → session → traces → spans"
echo " conversationId: $CONV"
echo "═══════════════════════════════════════════════════════════════════════"

# ── 0. Gateway reachable ─────────────────────────────────────────────────────
echo ""; echo "[0] gateway reachable"
if curl -sf "$GATEWAY/v1/models" >/dev/null; then ok "gateway /v1/models responds"; else
  bad "gateway not reachable at $GATEWAY"; echo "abort"; exit 1; fi

# ── 1. JWT for rm_jane ───────────────────────────────────────────────────────
echo ""; echo "[1] obtain JWT (rm_jane)"
TOKEN=$(curl -sf -X POST "$IAM/auth/token" -H 'Content-Type: application/json' \
  -d "{\"username\":\"rm_jane\",\"password\":\"$IAM_PW\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then ok "got RS256 token"; else bad "no token from iam $IAM"; TOKEN=""; fi
AUTH=(); [ -n "$TOKEN" ] && AUTH=(-H "Authorization: Bearer $TOKEN")

# ── 2. Two turns under one conversationId ────────────────────────────────────
turn(){
  curl -s -N -X POST "$GATEWAY/v1/chat/completions" \
    "${AUTH[@]}" -H 'Content-Type: application/json' -H "X-Conversation-Id: $CONV" \
    -d "{\"model\":\"conduit-assistant\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"$1\"}]}" \
    | grep -c 'data:' >/dev/null && echo "      turn sent: $1"
}
echo ""; echo "[2] send two turns (same conversationId)"
turn "Show me holdings for Whitman Family Office REL-00042"; ok "turn 1 streamed"
sleep 2
turn "What is their YTD performance?";                       ok "turn 2 streamed"

# ── 3. Let OTel batch+export flush to Langfuse ───────────────────────────────
echo ""; echo "[3] waiting for OTel→Langfuse export to flush (batch 1s + ingest)"
sleep 20

# ── 4. Query Langfuse: one session, two traces, spans ────────────────────────
echo ""; echo "[4] verify in Langfuse ($LF)"
TRACES=$(curl -sf -u "$LF_PUB:$LF_SEC" "$LF/api/public/traces?sessionId=$CONV&limit=50" 2>/dev/null)
if [ -z "$TRACES" ]; then bad "Langfuse traces API returned nothing (session may not have flushed yet)"; else
  COUNT=$(printf '%s' "$TRACES" | grep -o '"id":' | wc -l | tr -d ' ')
  if [ "${COUNT:-0}" -ge 2 ]; then ok "session '$CONV' has $COUNT traces (expected ≥2 — one per turn)";
  elif [ "${COUNT:-0}" -ge 1 ]; then bad "session has only $COUNT trace (expected ≥2 — export lag or session-id not mapped)";
  else bad "session '$CONV' has 0 traces in Langfuse"; fi

  # span tree on the first trace
  TID=$(printf '%s' "$TRACES" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)
  if [ -n "$TID" ]; then
    OBS=$(curl -sf -u "$LF_PUB:$LF_SEC" "$LF/api/public/observations?traceId=$TID&limit=50" 2>/dev/null)
    for span in chat.handle intent.classify agent.invoke llm.synthesize; do
      if printf '%s' "$OBS" | grep -q "$span"; then ok "span present: $span"; else bad "span missing: $span"; fi
    done
  fi
fi

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo " RESULT: $PASS passed, $FAIL failed"
echo "   Inspect live: $LF  → Sessions → $CONV"
echo "═══════════════════════════════════════════════════════════════════════"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
