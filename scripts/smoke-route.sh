#!/bin/bash
# smoke-route.sh — trace-truth routing + entitlement smoke.
#
# Asserts on the RouteDecision returned by POST /debug/route (the production pre-routing pipeline:
# intent + extract + RoutePreparer + resolveContextual + per-group structural authz, NO agent
# invocation, NO synthesis). This is deliberately NOT a prose grep: it checks the deterministic
# routing outcome — overallDisposition, resolver.primaryAgentId/primaryDomain, selected candidates,
# and per-group denied capability ids — so a degraded/hallucinated ANSWER can never false-green it.
# Runs in ~seconds (no synthesis LLM) and is safe to run against a live stack.
#
# Personas (seeded, Meridian@2024): rm_jane (wealth RM — covers Whitman/REL-00042, NOT Okafor/REL-00188),
# uw_sam (insurance underwriter — covers his policies, NOT asset-servicing).
set -u
GW="${GW:-http://localhost:8080}"
IAM="${IAM:-http://localhost:8084}"
PASS=0; FAIL=0

tok(){ curl -s -X POST "$IAM/auth/token" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"Meridian@2024\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null; }

# assert_route <label> <token> <query> <python-predicate over dict `d`>
assert_route(){
  local label="$1" token="$2" query="$3" pred="$4"
  local resp
  resp=$(curl -s -X POST "$GW/debug/route" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -d "{\"messages\":[{\"role\":\"user\",\"content\":\"$query\"}]}")
  local out
  out=$(printf '%s' "$resp" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e:
    print('ERR bad-json: '+str(e)); sys.exit(3)
r=d.get('resolver',{})
insel=[c['agentId'] for c in d.get('candidates',[]) if c.get('domain')=='insurance' and c.get('selected')]
denied=[]
for g in d.get('disposition',[]): denied+=g.get('deniedCapabilityIds',[])
disp=d.get('overallDisposition'); primary=r.get('primaryAgentId'); pdom=r.get('primaryDomain'); fb=r.get('fallback')
ok = bool($pred)
print(('OK ' if ok else 'NO ')+'disp=%s primary=%s dom=%s fallback=%s ins_sel=%d' % (disp,primary,pdom,fb,len(insel)))
sys.exit(0 if ok else 1)
")
  local rc=$?
  if [ "$rc" = "0" ]; then PASS=$((PASS+1)); echo "  ✅ $label — ${out#OK }";
  else FAIL=$((FAIL+1)); echo "  ❌ $label — ${out#NO }"; fi
}

# assert_route_msgs <label> <token> <messages-json-array> <python-predicate over dict `d`>
# Like assert_route, but takes a FULL multi-message body (a JSON array of {role,content}) so a
# multi-turn conversation (client SWITCH / anaphoric CARRY) can be exercised end-to-end.
assert_route_msgs(){
  local label="$1" token="$2" messages="$3" pred="$4"
  local resp
  resp=$(curl -s -X POST "$GW/debug/route" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -d "{\"messages\":$messages}")
  local out
  out=$(printf '%s' "$resp" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e:
    print('ERR bad-json: '+str(e)); sys.exit(3)
r=d.get('resolver',{})
insel=[c['agentId'] for c in d.get('candidates',[]) if c.get('domain')=='insurance' and c.get('selected')]
denied=[]
for g in d.get('disposition',[]): denied+=g.get('deniedCapabilityIds',[])
disp=d.get('overallDisposition'); primary=r.get('primaryAgentId'); pdom=r.get('primaryDomain'); fb=r.get('fallback')
ok = bool($pred)
print(('OK ' if ok else 'NO ')+'disp=%s primary=%s dom=%s fallback=%s ins_sel=%d' % (disp,primary,pdom,fb,len(insel)))
sys.exit(0 if ok else 1)
")
  local rc=$?
  if [ "$rc" = "0" ]; then PASS=$((PASS+1)); echo "  ✅ $label — ${out#OK }";
  else FAIL=$((FAIL+1)); echo "  ❌ $label — ${out#NO }"; fi
}

JANE=$(tok rm_jane); UW=$(tok uw_sam)
if [ -z "$JANE" ] || [ -z "$UW" ]; then echo "  ❌ could not mint persona tokens (IAM down?)"; exit 1; fi

echo "── routing + entitlement (trace-truth /debug/route) ──"

# S1 — wealth happy path: routes to holdings, served.
assert_route "S1 rm_jane Whitman holdings → SERVED wealth.holdings" "$JANE" \
  "what are the holdings for the Whitman Family Office" \
  "disp=='SERVED' and primary=='meridian.wealth.holdings'"

# S2 — insurance happy path: routes to policy_details, served (servicing capabilities denied but insurance served).
assert_route "S2 uw_sam policy details → SERVED insurance.policy_details" "$UW" \
  "policy details for Continental Freight POL-77001" \
  "disp=='SERVED' and primary=='meridian.insurance.policy_details'"

# S3 — bug-261: capability beats entity. 'settlements' routes to asset-servicing settlement_status
# (NOT insurance, despite 'Continental Freight'); uw_sam has no servicing coverage → structural deny.
assert_route "S3 bug-261 uw_sam settlements/Continental → STRUCTURAL_DENIED, servicing, zero insurance" "$UW" \
  "pending and failed settlements for Continental Freight" \
  "disp=='STRUCTURAL_DENIED' and primary=='meridian.servicing.settlement_status' and len(insel)==0 and 'meridian.servicing.settlement_status' in denied"

# S4 — route right, book denies: rm_jane routes to wealth for an out-of-coverage relationship (Okafor).
assert_route "S4 rm_jane Okafor (out of book) → COVERAGE_DENIED, routed wealth" "$JANE" \
  "show me the Okafor relationship holdings REL-00188" \
  "disp=='COVERAGE_DENIED' and pdom=='wealth-management'"

# S5 — cross-domain: serves something and never leaks an unselected insurance agent.
assert_route "S5 rm_jane cross-domain → served (SERVED/PARTIAL), no insurance leak" "$JANE" \
  "Whitman Family Office holdings and their settlement status" \
  "disp in ('SERVED','PARTIAL') and len(insel)==0"

# S6 — off-topic: nothing to route → abstain (fallback), not a forced pick.
assert_route "S6 rm_jane off-topic haiku → ABSTAIN (fallback)" "$JANE" \
  "write a haiku about compound interest" \
  "disp=='ABSTAIN' and fb==True"

# S7 — under-specified data request: names a capability but no resolvable entity → no confident
# route, no agent invoked. (Uses a phrasing that abstains deterministically; "latest on my client"
# sits on the intent boundary and flaps, which a smoke gate must not.)
assert_route "S7 rm_jane under-specified (no entity) → ABSTAIN (no agent invoked)" "$JANE" \
  "show me the performance" \
  "disp=='ABSTAIN' and fb==True"

# S8 — multi-turn client SWITCH (the fix): after Whitman turns, "show me the Calderon Trust holdings"
# must route on the LATEST facet (holdings), NOT inherit the prior turn's concentration topic. The LLM
# greedily extracts "Calderon Trust holdings"; needle tightening masks only the canonical name, so the
# capability word survives and the turn routes alone (no widen into history).
S8_MSGS='[
  {"role":"user","content":"give me a summary of the Whitman Family Office holdings"},
  {"role":"assistant","content":"Whitman Family Office (REL-00042) holdings summary: diversified across equities and fixed income."},
  {"role":"user","content":"whats the concentration risk there"},
  {"role":"assistant","content":"Concentration for Whitman Family Office (REL-00042): top-5 positions are 38% of the book."},
  {"role":"user","content":"show me the Calderon Trust holdings"}
]'
assert_route_msgs "S8 SWITCH rm_jane Whitman→Calderon holdings → SERVED wealth.holdings (not concentration)" "$JANE" \
  "$S8_MSGS" \
  "disp=='SERVED' and primary=='meridian.wealth.holdings' and primary!='meridian.wealth.concentration'"

# S9 — anaphoric CARRY (guard sentinel): after a Whitman holdings turn, "whats the concentration risk
# there" states no entity and must WIDEN + inherit the entity, routing to concentration. Pins that the
# tightening fix never breaks the designed facet-carry.
S9_MSGS='[
  {"role":"user","content":"give me a summary of the Whitman Family Office holdings"},
  {"role":"assistant","content":"Whitman Family Office (REL-00042) holdings summary: diversified across equities and fixed income."},
  {"role":"user","content":"whats the concentration risk there"}
]'
assert_route_msgs "S9 CARRY rm_jane concentration anaphora → SERVED wealth.concentration" "$JANE" \
  "$S9_MSGS" \
  "disp=='SERVED' and primary=='meridian.wealth.concentration'"

echo "── smoke-route: $PASS passed / $FAIL failed ──"
[ "$FAIL" -eq 0 ]
