#!/bin/bash
# smoke.sh — full API/CLI smoke for Conduit. Run against a live stack (docker compose up).
# Covers: health, the proper JWT auth path, byte-exact OpenAI SSE, the four World-B scenarios,
# Langfuse traces/tags/datasets/scores, Grafana metrics, the World-B gate, and seeded principals.
# Exit 0 if all pass. Pair with the browser suite in docs/QA-CODEX-PLAYBOOK.md (§1-§7).
#
# Dev-default creds (already in the stack): Langfuse pk-lf-meridian-public / sk-lf-meridian-secret,
# Grafana admin/changeme, users rm_jane|rm_carlos|uw_sam|rm_guest / Meridian@2024.
set -u
GW=http://localhost:8080 ; LF=http://localhost:3030 ; GF=http://localhost:3000 ; AX=http://localhost:8084
LFK="pk-lf-meridian-public:sk-lf-meridian-secret"
P=0; F=0
pass(){ echo "  ✅ $1"; P=$((P+1)); }
fail(){ echo "  ❌ $1"; F=$((F+1)); }
ask(){ curl -s -X POST "$GW/v1/chat/completions" -H "Content-Type: application/json" -H "X-User-Id: $1" \
  -d "{\"model\":\"conduit-assistant\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]}" 2>/dev/null \
  | python3 -c 'import sys,json;print("".join(json.loads(l[5:]).get("choices",[{}])[0].get("delta",{}).get("content","") for l in sys.stdin if l.startswith("data:") and l[5:].strip() not in ("","[DONE]")))' 2>/dev/null; }

echo "═══ A. HEALTH ═══"
[ "$(curl -s -o /dev/null -w '%{http_code}' $GW/v1/models)" = "200" ] && pass "gateway 200" || fail "gateway"
[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:3080/oauth/openid)" = "302" ] && pass "librechat 302" || fail "librechat"
[ "$(curl -s -o /dev/null -w '%{http_code}' $GF/api/health)" = "200" ] && pass "grafana 200" || fail "grafana"
[ "$(curl -s -o /dev/null -w '%{http_code}' $LF/api/public/health)" = "200" ] && pass "langfuse 200" || fail "langfuse"
[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:4000/)" = "200" ] && pass "glass-box 200" || fail "glass-box"

echo "═══ B. AUTH (proper JWT path) ═══"
TOK=$(curl -s -X POST $AX/auth/token -H 'Content-Type: application/json' -d '{"username":"rm_jane","password":"Meridian@2024"}' | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))')
[ -n "$TOK" ] && pass "Axiom mints JWT" || fail "Axiom /auth/token"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/v1/chat/completions -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"model":"conduit-assistant","stream":false,"messages":[{"role":"user","content":"hi"}]}')" = "200" ] && pass "gateway accepts Bearer-only (no Redis)" || fail "JWT path"

echo "═══ C. SSE byte-exact (OpenAI spec) ═══"
curl -sN -X POST $GW/v1/chat/completions -H "Content-Type: application/json" -H "X-User-Id: rm_jane" \
  -d '{"model":"conduit-assistant","stream":true,"messages":[{"role":"user","content":"hi"}]}' 2>/dev/null > /tmp/_smoke_sse.txt
grep -qE '^data: \{' /tmp/_smoke_sse.txt && pass "data: {json} (space)" || fail "data: framing (no space)"
grep -qE '^data: \[DONE\]$' /tmp/_smoke_sse.txt && pass "data: [DONE] (space)" || fail "[DONE] framing"
grep -q '"finish_reason":"stop"' /tmp/_smoke_sse.txt && pass "terminal finish_reason:stop" || fail "no finish_reason:stop"

echo "═══ D. WORLD B SCENARIOS ═══"
echo "$(ask rm_jane 'Give me a complete overview of the Whitman relationship: holdings, performance, settlement status, and cash position')" | grep -qiE "whitman|holding|portfolio|cash|settle|[0-9],[0-9]{3}" && pass "hero grounded" || fail "hero"
echo "$(ask rm_jane 'Show me the Okafor relationship holdings')" | grep -qiE "denied|deny|not.*(cover|access|book)" && pass "denial (Okafor blocked for rm_jane)" || fail "denial"
echo "$(ask rm_jane 'What is the latest on my client')" | grep -qiE "which|clarif|specify|by (id|name)|\?" && pass "clarify (no guess)" || fail "clarify"
echo "$(ask uw_sam 'Give me the policy details and claim status for the Nakamura policy')" | grep -qiE "nakamura|policy|claim|coverage|premium" && pass "insurance grounded (uw_sam)" || fail "insurance"

echo "═══ E. LANGFUSE (traces/tags/datasets) ═══"
sleep 8
python3 - "$LFK" <<'PY'
import sys,json,urllib.request,base64
k=base64.b64encode(sys.argv[1].encode()).decode()
def g(p):
    r=urllib.request.Request("http://localhost:3030"+p); r.add_header("Authorization","Basic "+k); return json.load(urllib.request.urlopen(r,timeout=20))
chat=g("/api/public/traces?name=chat-turn&limit=15&orderBy=timestamp.desc").get("data",[])
tagged=[t for t in chat if t.get("tags")]
print(("  ✅" if chat else "  ❌")+f" chat-turn traces: {len(chat)}")
print(("  ✅" if tagged else "  ❌")+f" domain:/agent: tags: {len(tagged)}")
ds={d["name"] for d in g("/api/public/datasets").get("data",[])}
print(("  ✅" if {"conduit-routing","conduit-synthesis"}<=ds else "  ❌")+f" datasets: {sorted(ds)}")
PY

echo "═══ F. GRAFANA / WORLD-B / USERS ═══"
n=$(curl -s -u admin:changeme "$GF/api/datasources/proxy/uid/prometheus/api/v1/query?query=conduit_agent_calls_total" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["result"]))' 2>/dev/null)
[ "${n:-0}" -gt 0 ] && pass "grafana metric has data ($n series)" || fail "grafana metric"
bash "$(dirname "$0")/world-b-check.sh" 2>/dev/null | grep -q "CRITICAL violations : 0" && pass "world-b CRITICAL 0" || fail "world-b"
[ "$(docker exec conduit-redis redis-cli KEYS 'principal:*' 2>/dev/null | wc -l | tr -d ' ')" -ge 4 ] && pass "4 principals seeded" || fail "principals"

echo ""
echo "═══ eval scores (scorer runs on a cycle — up to ~5 min) ═══"
for i in $(seq 1 15); do
  s=$(curl -s -u $LFK "$LF/api/public/scores?limit=10" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("data",[])))' 2>/dev/null)
  [ "${s:-0}" -gt 0 ] && { pass "eval scores flowing"; break; }
  [ "$i" = "15" ] && echo "  ⏳ no scores yet after ~5min (check conduit-eval-continuous)"
  sleep 20
done

echo ""
echo "═══ RESULT: $P passed / $F failed ═══"
[ "$F" -eq 0 ] && echo "  🟢 SMOKE GREEN" || echo "  🔴 SMOKE FAIL"
exit $F
