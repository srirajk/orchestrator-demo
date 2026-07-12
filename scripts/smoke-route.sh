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

# ── MULTI-ENTITY COMPARE (Multi-Entity Compare spec — S10/S11/S12) ───────────────────────────────
# The per-entity fan-out: one query naming ≥2 resolvable clients coverage-CHECKs EACH independently,
# forms one entity-facet group per client, and composes SERVED / PARTIAL / COVERAGE_DENIED from the SET
# of entity verdicts (never the focal mention alone — the MC-3 order-dependence bug fix).

# S10 — both clients covered (rm_jane covers Whitman/REL-00042 AND Calderon/REL-00099): both served.
S10_MSGS='[{"role":"user","content":"compare the concentration of Whitman Family Office and Calderon Trust"}]'
assert_route_msgs "S10 COMPARE both-covered → SERVED, 2 entity-facet groups" "$JANE" \
  "$S10_MSGS" \
  "disp=='SERVED' and pdom=='wealth-management' and len([g for g in d.get('requestedGroups',[]) if g.get('routingEvidence')=='entity-facet'])>=2"

# S11 — one covered, one denied, DENIED-FIRST (pins the MC-3 fix): "the Okafor account" (REL-00188,
# OUT of jane's book) named FIRST, then Whitman. Must PARTIAL: serve Whitman, WITHHOLD Okafor — the
# covered half is NOT killed by the uncovered focal. No-leak proxy: exactly one entity group DENIED,
# ≥1 SERVED (the /debug/route projection carries no canonical ids; the live E2E asserts data absence).
S11_MSGS='[{"role":"user","content":"compare the concentration of the Okafor account and the Whitman Family Office"}]'
assert_route_msgs "S11 COMPARE one-denied (Okafor first) → PARTIAL, 1 entity group withheld" "$JANE" \
  "$S11_MSGS" \
  "disp=='PARTIAL' and pdom=='wealth-management' and len([g for g in d.get('requestedGroups',[]) if g.get('routingEvidence')=='entity-facet'])>=2 and len([x for x in d.get('disposition',[]) if x.get('disposition')=='DENIED'])==1 and len([x for x in d.get('disposition',[]) if x.get('disposition')=='SERVED'])>=1"

# S12 — order flip (Whitman FIRST, Okafor second): SAME PARTIAL outcome → pins order independence.
S12_MSGS='[{"role":"user","content":"compare the concentration of the Whitman Family Office and the Okafor account"}]'
assert_route_msgs "S12 COMPARE order-flip (Whitman first) → PARTIAL (order independent)" "$JANE" \
  "$S12_MSGS" \
  "disp=='PARTIAL' and pdom=='wealth-management' and len([g for g in d.get('requestedGroups',[]) if g.get('routingEvidence')=='entity-facet'])>=2 and len([x for x in d.get('disposition',[]) if x.get('disposition')=='DENIED'])==1 and len([x for x in d.get('disposition',[]) if x.get('disposition')=='SERVED'])>=1"

# ── COMPARE-CLARIFY (Compare-CLARIFY spec — S13/S14) ─────────────────────────────────────────────
# When a request names ≥2 distinct clients but binds FEWER, the gateway ASKS (deterministic CLARIFY)
# instead of silently serving one-sided. These rows are written FLAKE-PROOF against extractor variance
# (PC-3 proved the same phrasing can legitimately two-side-serve): they pin the INVARIANT — CLARIFY or a
# TWO-SIDED served (≥2 entity-facet groups) — and FAIL only on a one-sided SERVED (a lone single-selection
# group). `ef` = count of entity-facet groups.

# S13 — incomplete resolution (alias 2nd, rm_jane): "the Calderon account" is the flaky/possessive form
# the extractor sometimes drops. NEVER one-sided: CLARIFY, or SERVED with both clients (≥2 entity-facet).
S13_MSGS='[{"role":"user","content":"compare the concentration of the Whitman Family Office and the Calderon account"}]'
assert_route_msgs "S13 COMPARE-CLARIFY incomplete-resolution → CLARIFY or two-sided SERVED (never one-sided)" "$JANE" \
  "$S13_MSGS" \
  "(disp=='CLARIFY') or (disp=='SERVED' and len([g for g in d.get('requestedGroups',[]) if g.get('routingEvidence')=='entity-facet'])>=2)"

# S14 — out-of-book dropped (rm_jane): the second client (Okafor) is out of book. Either CLARIFY (the
# extractor dropped it → unbound) or PARTIAL (it bound and was withheld) — never a plain one-sided SERVED.
S14_MSGS='[{"role":"user","content":"compare the concentration of the Whitman Family Office and the Okafor account"}]'
assert_route_msgs "S14 COMPARE out-of-book dropped → CLARIFY or PARTIAL (never one-sided SERVED)" "$JANE" \
  "$S14_MSGS" \
  "(disp in ('CLARIFY','PARTIAL')) or (disp=='SERVED' and len([g for g in d.get('requestedGroups',[]) if g.get('routingEvidence')=='entity-facet'])>=2)"

echo "── smoke-route: $PASS passed / $FAIL failed ──"
[ "$FAIL" -eq 0 ]
