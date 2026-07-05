# Plan — Context-aware agent routing (fix keyword-less misroute)

## Problem (verified live through the BFF, model-independent)
Chat routing embeds only the **bare last user message** and passes **no domain** to the Redis KNN.
Keyword-less follow-ups ("summarize for Calderon Trust (REL-00099)?") score in the noise band (~0.30);
the porous **absolute floor 0.30** leaks one spurious out-of-domain agent (`insurance.claim_status @0.312`),
the correct wealth agents get pruned "below-confidence-floor", and the segment gate then denies →
"You do not have access to any of the required services." Calderon (REL-00099) **IS** in rm_jane's book.
gpt-4o-mini and gpt-5.4-mini behave identically → **not** a model/intent problem.

## Root-cause locations (confirmed by reading the code)
- `domain/chat/ChatService.java:287` — `resolver.resolve(latestPrompt)` (no domain passed)
- `domain/chat/ChatService.java:161` + `:724-731` — routes on `extractLatestUserMessage` (bare last turn only)
- `resolver/service/AgentResolver.java:58-71` — two-tier floor: relative (`topScore*0.65`) only if `topScore>0.55`, else absolute `0.30`
- `resolver/service/AgentResolver.java:~90-92` — `resolve(prompt, domain)` overload EXISTS (used by `admin/DebugResolverController.java:37`)
- `.../VectorIndex.java` — `search(queryText, **domain**, topK, loader)` builds Redis `@domain:{...}` filter; agent manifests are indexed WITH their domain. Fully built, unused from chat.
- `domain/chat/ChatService.java:300-307` — empty-selection → CLARIFY fallback (starved when a noise hit keeps `selected` non-empty)

## The fix (generic, World-B-clean)
Cerbos is a **PDP only** (the entitlement CHECK). It is NOT a mapping source — do not read Cerbos policy
for routing. The routing domain scope comes from the **domain manifests** (`registry/domains/*.json`:
`domain_id` matches the agent `@domain` namespace) via the coverage/entity resolution.

1. **Domain-scope the routing.** Pass an agent-domain into `vectorIndex.search` from the chat path.
   Derive it from the **resolved entity's business domain** (the domain manifest whose coverage service
   owns the entity; `domain_id` == the agent `@domain`). No domain literals in gateway Java.
   - **Determine from the code whether entity resolution precedes agent routing.** If it does, use the
     resolved entity's domain. If routing runs first, either (a) do a lightweight pre-routing domain
     resolution, or (b) carry the conversation's established domain (last successful turn's domain) forward.
     Pick the cleanest and document the choice in the commit.
2. **Enrich the routing text with conversation context** (recent user turns / carried topic) so
   keyword-less follow-ups inherit the prior turn's domain vocabulary — complements #1 for entity-less queries.
3. **Abstain → CLARIFY on low confidence.** When the top-vs-noise **margin** is low (no confident route),
   emit the deterministic CLARIFY (copy from the manifest) instead of trusting one noise hit. Fix the
   starved fallback: a low-confidence non-empty selection must still be treated as "not confident".
   Prefer a margin-based signal over a raw absolute cutoff.

## Verification (REQUIRED — this is a routing change; it regresses silently otherwise)
Re-run the BFF multi-turn harness (`/private/tmp/claude-501/.../scratchpad/bff-multiturn-intent.py --user rm_jane`)
and pull the decision trace (`GET /v1/insights/conversations/{id}/trace`, admin token):
- Turn 1 "…Whitman holdings" → still works ✅
- Turn 3 "summarize for Calderon Trust (REL-00099)?" → **now routes to wealth agents → succeeds** (Calderon in book) ✅ [the fix]
- Turn 5 bare "Calderon Trust?" → clean **CLARIFY**, not the noise loop ✅
- "Show me Okafor holdings" (out of coverage) → clean deny/clarify, not misrouted to insurance ✅
- Trace shows `agents_resolved` = wealth agents for Calderon; NO `insurance.claim_status @0.31`.
- Commit these as a **multi-turn eval fixture** (regression guard) alongside the change.
- `scripts/world-b-check.sh` CRITICAL **0 before AND after**.
- Gateway builds + healthy.

## Constraints
- World-B: no domain names / entity-type literals / `REL-` patterns / domain copy in gateway Java.
- RESOLVE stays **principal-agnostic** (never filter entity resolution by the RM's book); the entitlement
  CHECK stays the only gate. Domain-scoping is on the AGENT candidates, not on entity resolution.
- Cerbos = PDP only. Commit on `feat/conduit-chat`, message ending "Approved by Sriraj.", no AI attribution.
