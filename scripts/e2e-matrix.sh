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

# case <n> <user> <expect allow|deny> <data-regex> <prompt>
#
# <data-regex> means "domain data that MUST appear" for an allow case, and
# "domain data that MUST NOT appear" (a leak) for a deny case.
#
# A phrase like "not in your coverage" is NOT sufficient to call a request denied: a
# correct PARTIAL answer (hard rule d — harvest survivors, state what's missing) legitimately
# says "asset-servicing data was not included because it is outside your access" while still
# returning grounded data. A FULL denial is a denial phrase AND no domain data returned.
# Requiring "no data" also closes a false-pass hole: a response that leaked data while
# uttering a denial phrase used to PASS an expect=deny case.
run(){
  local n=$1 user=$2 expect=$3 rx=$4 prompt=$5
  local ans; ans=$(ask "$user" "$prompt")
  printf '%s\n' "$ans" > "$OUT/${n}_${user}.txt"
  local phrase="no"
  printf '%s' "$ans" | grep -qiE "denied|deny|don't have access|do not have access|not.*(cover|access|book|entitl|part of|in your)|isn't (in|part)|unable to|cannot (access|provide|help)|no (coverage|access|client)|outside" && phrase="yes"
  # data present? (allow: grounding evidence; deny: leaked domain data)
  local data="no"; printf '%s' "$ans" | grep -qiE "$rx" && data="yes"
  # full denial = refusal phrase AND no domain data returned
  local isdeny="no"; [ "$phrase" = "yes" ] && [ "$data" = "no" ] && isdeny="yes"
  local verdict
  if [ "$expect" = "allow" ]; then
    if [ "$isdeny" = "no" ] && [ "$data" = "yes" ]; then verdict="PASS"; P=$((P+1)); else verdict="FAIL"; F=$((F+1)); fi
  else
    # deny must refuse AND leak nothing
    if [ "$isdeny" = "yes" ]; then verdict="PASS"; P=$((P+1)); else verdict="FAIL"; F=$((F+1)); fi
  fi
  printf '[%s] #%s %-12s expect=%-5s deny=%-3s data=%-3s :: %s\n' "$verdict" "$n" "$user" "$expect" "$isdeny" "$data" "$prompt"
  printf '        ans: %.180s\n' "$(printf '%s' "$ans" | tr '\n' ' ')"
}

echo "═══════════════ E2E VARIATION MATRIX ═══════════════"
run 01 rm_jane    allow 'whitman|holding|portfolio|cash|[0-9],[0-9]{3}' 'Give me a complete overview of the Whitman relationship: holdings, performance, settlement status, and cash position'
run 02 rm_jane    deny  'okafor|shares|[0-9]{3},[0-9]{3}|portfolio value'  'Show me the Okafor relationship holdings'
run 03 rm_jane    deny  'continental|premium|deductible|[0-9]{3},[0-9]{3}' 'Give me the policy details for POL-77001'
run 04 rm_carlos  allow 'sterling|holding|portfolio|cash|[0-9]'        'Give me an overview of the Sterling Capital Partners relationship holdings and cash'
run 05 uw_sam     allow 'polic|premium|coverage|continental|limit|[0-9]' 'Give me the policy details for POL-77001'
run 06 uw_sam     deny  'nvda|msft|shares|[0-9]{3},[0-9]{3}|portfolio value' 'Show me the Whitman relationship holdings'
run 07 rm_guest   deny  'nvda|msft|shares|[0-9]{3},[0-9]{3}|portfolio value' 'Show me the Whitman relationship holdings'
run 08 analyst_amy allow 'equit|sector|outlook|allocation|overweight|house view|fixed income|macro|market' 'What is the current house view and market outlook on equities?'
run 09 analyst_amy deny 'nvda|msft|shares|[0-9]{3},[0-9]{3}|portfolio value' 'Show me the Whitman relationship holdings'
run 10 rm_jane    allow 'leave|pto|benefit|conduct|polic|vacation|parental|day' 'What is our parental leave policy?'
run 11 uw_sam     allow 'leave|pto|benefit|conduct|polic|vacation|parental|day' 'What is our parental leave policy?'
run 12 analyst_amy allow 'leave|pto|benefit|conduct|polic|vacation|parental|day' 'What is our parental leave policy?'

echo ""
echo "═══ E2E RESULT: $P passed / $F failed ═══"
[ "$F" -eq 0 ] && echo "  🟢 E2E GREEN" || echo "  🔴 E2E FAIL"
exit "$F"
