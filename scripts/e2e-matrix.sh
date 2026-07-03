#!/usr/bin/env bash
# e2e-matrix.sh — full ABAC variation matrix through the running gateway/chat path.
# Mints per-persona Axiom JWTs and asserts allow/deny + grounding for each case.
set -u
GW=${GATEWAY_URL:-http://localhost:8080}
AX=${IAM_URL:-http://localhost:8084}
PW=${IAM_USER_PASSWORD:-Meridian@2024}
OUT=${OUT_DIR:-/private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/29f180d9-6150-4300-ae30-ee615cfcd441/scratchpad/e2e}
mkdir -p "$OUT"
P=0; F=0

tok(){ curl -s -X POST $AX/auth/token -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null; }

ask(){ local t; t=$(tok "$1"); curl -s -X POST "$GW/v1/chat/completions" -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
  -d "{\"model\":\"conduit-assistant\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]}" 2>/dev/null \
  | python3 -c 'import sys,json;print("".join(json.loads(l[5:]).get("choices",[{}])[0].get("delta",{}).get("content","") for l in sys.stdin if l.startswith("data:") and l[5:].strip() not in ("","[DONE]")))' 2>/dev/null; }

# case <n> <user> <expect allow|deny> <grounding-regex> <prompt>
run(){
  local n=$1 user=$2 expect=$3 rx=$4 prompt=$5
  local ans; ans=$(ask "$user" "$prompt")
  printf '%s\n' "$ans" > "$OUT/${n}_${user}.txt"
  local isdeny="no"
  printf '%s' "$ans" | grep -qiE "denied|deny|don't have access|do not have access|not.*(cover|access|book|entitl|part of|in your)|isn't (in|part)|unable to|cannot (access|provide|help)|no (coverage|access|client)|outside" && isdeny="yes"
  local grounded="no"; printf '%s' "$ans" | grep -qiE "$rx" && grounded="yes"
  local verdict
  if [ "$expect" = "allow" ]; then
    if [ "$isdeny" = "no" ] && [ "$grounded" = "yes" ]; then verdict="PASS"; P=$((P+1)); else verdict="FAIL"; F=$((F+1)); fi
  else
    if [ "$isdeny" = "yes" ]; then verdict="PASS"; P=$((P+1)); else verdict="FAIL"; F=$((F+1)); fi
  fi
  printf '[%s] #%s %-12s expect=%-5s deny=%-3s grounded=%-3s :: %s\n' "$verdict" "$n" "$user" "$expect" "$isdeny" "$grounded" "$prompt"
  printf '        ans: %.180s\n' "$(printf '%s' "$ans" | tr '\n' ' ')"
}

echo "═══════════════ E2E VARIATION MATRIX ═══════════════"
run 01 rm_jane    allow 'whitman|holding|portfolio|cash|[0-9],[0-9]{3}' 'Give me a complete overview of the Whitman relationship: holdings, performance, settlement status, and cash position'
run 02 rm_jane    deny  'x'                                            'Show me the Okafor relationship holdings'
run 03 rm_jane    deny  'x'                                            'Give me the policy details for POL-77001'
run 04 rm_carlos  allow 'sterling|holding|portfolio|cash|[0-9]'        'Give me an overview of the Sterling Capital Partners relationship holdings and cash'
run 05 uw_sam     allow 'polic|premium|coverage|continental|limit|[0-9]' 'Give me the policy details for POL-77001'
run 06 uw_sam     deny  'x'                                            'Show me the Whitman relationship holdings'
run 07 rm_guest   deny  'x'                                            'Show me the Whitman relationship holdings'
run 08 analyst_amy allow 'equit|sector|outlook|allocation|overweight|house view|fixed income|macro|market' 'What is the current house view and market outlook on equities?'
run 09 analyst_amy deny 'x'                                            'Show me the Whitman relationship holdings'
run 10 rm_jane    allow 'leave|pto|benefit|conduct|polic|vacation|parental|day' 'What is our parental leave policy?'
run 11 uw_sam     allow 'leave|pto|benefit|conduct|polic|vacation|parental|day' 'What is our parental leave policy?'
run 12 analyst_amy allow 'leave|pto|benefit|conduct|polic|vacation|parental|day' 'What is our parental leave policy?'

echo ""
echo "═══ E2E RESULT: $P passed / $F failed ═══"
[ "$F" -eq 0 ] && echo "  🟢 E2E GREEN" || echo "  🔴 E2E FAIL"
exit "$F"
