# Routing layer — implementation spec (capability-first routing, SOTA)

One spec, reviewed independently by Fable and Codex-SOL, then Opus reconciles and implements. This is
the GATEWAY (the product's selling point). World-B: **no domain literal, id-pattern, or client name may
enter gateway Java** — everything from manifests/config/eval. `scripts/world-b-check.sh` CRITICAL must
stay 0 (note: the checker itself is stale — separate fix, out of scope here).

## Goal
Route on the **capability asked**, not the **entity named**. Close bug-261 (uw_sam "pending settlements
for Continental Freight" → insurance) as a CLASS. Empirically validated design: de-entiting the corpus
already fixes the mis-route (`settlements for Continental` now → `settlement_status` 0.637); masking the
query recovers the recall it costs (`Whitman's goals funded` 0.393 → masked 0.798). Both halves proven;
this spec builds them properly plus the safety catch, eval gate, and grounding foundation they depend on.

## Ordered stages (each independently shippable; later stages depend on earlier)

### Stage 0 — DONE (local, not committed): de-entity the corpus
17 manifest `examples` rewritten to neutral placeholders; index rebuilt. Lands with the rest as one unit.

### Stage 1 — Grounding foundation (masking depends on this; do FIRST)
**Problem (verified):** `entityKnown` is set on mere syntactic presence — `hasGroundedResolvableReference`
(`ChatService.java:1429-1444`) returns true if the bag has an `extract_as` value OR the prompt matches an
`id_pattern`, WITHOUT the reference actually resolving+passing CHECK. And `DomainManifestStore.identifyByReference`
(`:365-385`) returns the FIRST HashMap-iteration sub-domain, and `ReferenceGroundingService.ground` (`:61-66`)
grounds only the single "strongest" reference with no source spans (`GroundingResult` `:117-124`).

**Changes:**
1. `ReferenceGroundingService.ground(...)` → return a **list** of grounded references, each carrying:
   `{referenceText, resolvedId, entityType, subDomainId, status ∈ {RESOLVED_ALLOWED, DENIED, AMBIGUOUS,
   NOT_FOUND}, [start,end) span}`. Zero/one/many. Preserve the existing terminal-DENY on any RESOLVED+denied.
2. Exact spans: for typed IDs, the `id_pattern` regex match offsets (already compiled — `latestContainsResolvableId`
   `:1401-1408`); for extracted names, a conservative literal alignment of the extracted string against the
   prompt (case/punct/possessive tolerant). If alignment fails → no span (don't guess).
3. `DomainManifestStore.identifyByReference` → deterministic (declaration order, not HashMap); return ALL
   eligible sub-domain interpretations (a name can be valid in several domains), not first-match.
4. Replace the `entityKnown` boolean with a structured signal: **trust the routing relaxation ONLY on
   RESOLVED_ALLOWED**. Remove/repair the syntactic-presence second branch at `ChatService.java:446-447`.

### Stage 2 — Query masking (P2)
**Change:** in `routingTextForFetch`/`buildRoutingQuery` (`ChatService.java:1362-1398`), before embedding,
replace each Stage-1 grounded span with a **neutral, config-driven token** (`conduit.routing.entity-mask-token`,
a domain-neutral deictic — NOT the entity-type word, which re-injects domain vocab; the token must be
empirically tested for neutrality). Mask **all** grounded spans across the **entire** routing window (every
user turn in the window, not just the latest), applied reverse-offset. Traps to handle:
- **Empty/near-empty residual** (entity-only ask) → deterministic **clarify**, NOT fail-open to the unmasked
  string. Only reuse prior *action* text that is explicitly in the bounded window.
- **Span alignment fails** → fail-open to the unmasked text for THAT span (masking is an enhancement, never
  a gate). Never mask ordinary words that merely equal a short entity name — require a resolved span.
- Feed the masked text to `resolveContextual` and to the reranker.

### Stage 3 — Post-prune capability-continuity guard (P0)
**Problem (verified):** after `filterAgents` (`ChatService.java:521`), if any candidate survives the code
proceeds with survivors (`:533-542`) with NO check that they still cover the asked capability — so a pruned
primary silently becomes another domain's answer.

**Changes:**
1. Add `primaryCandidate` (agent_id + domain) to `ResolverResult` (`resolver/model/ResolverResult.java`),
   populated as the resolver's final leader **after** any rerank, **before** entitlement filtering (don't
   rely on list position — the reranker reorders, `AgentResolver.java:340-351`).
2. After `filterAgents`: **v1 (narrow circuit-breaker)** — if the `primaryCandidate`'s **domain** is fully
   pruned (no surviving agent in that domain), do NOT proceed with other-domain survivors → deny.
   **Durable** — upgrade to *capability* continuity: the primary agent (flat) or DAG goal + its producers
   must survive; a same-domain sibling surviving is not enough. Represent `{primary, requestedGroups,
   dagGoal}` so a genuine multi-domain request still fulfils entitled groups and reports withheld ones.
3. Denial copy: there is **no** manifest "structural-denial" contract today (`denial_messages` are COVERAGE
   reasons `:276-308`; the all-pruned response is hardcoded generic `:522-530`). Add a **new generic manifest
   message key** for "requested capability unavailable/unauthorized" — do NOT reuse coverage copy. Copy
   selection may use the primary's sub-domain; no domain wording in Java.

### Stage 4 — Eval becomes the gate (measures the MASKED path)
`eval/goal-pick/measure_goal_pick.py` hits `/debug/resolve` (raw `resolve()`, no masking/grounding/prune).
Make it measure production behavior:
- exercise the masked routing path (add masking to `/debug/resolve` behind a flag, OR pre-mask eval queries,
  OR add a chat-path eval); use each row's **persona token** (not admin, `:303-325`).
- add `capability_entity_conflict` + `name_invariance` datasets; put confusers IN the gate (`:208-217`);
  gate exact-capability accuracy + wrong-domain-substitution = 0.
- re-baseline the 4 thresholds (`routing.min-score` 0.40, `min-margin` 0.005, `confidence-floor` 0.30,
  `domain-margin` 0.05) against the new masked distribution. Baseline pre-change = 96.3% (goal-pick).

### Stage 5 — Reranker (P3)
`LlmRoutingRerankerClient` (`:49-110`): feed the **masked** query + (post-Stage-0) de-entitied examples;
add a generic "match the action asked, not the names mentioned" line; fire on a domain-conflict trigger
(leader-domain ≠ a grounded reference's domain, computed from manifest metadata, never literals). Preserve
margin-abstain on reranker error — today `embeddingFallback` sets `suppressMarginAbstain=true`
(`AgentResolver.java:352-356, 393-395`); on the conflict path, keep abstention. Measure top-k recall
(a contaminated shortlist can EXCLUDE the right capability; the LLM can't recover it).

## Definition of done (one green commit)
- bug-261 → denial or capability-clarify, **no insurance agent invoked**, no insurance facts (verified via trace).
- goal-pick on the **masked** path ≥ 96.3% baseline; wrong-domain substitution = 0; out-of-scope abstain 100%.
- entitled servicing persona on the same query → settlement_status invoked.
- genuine mixed insurance+servicing request → entitled portion served, denied portion withheld honestly.
- full gateway suite green; `world-b-check` CRITICAL 0.

## What the reviewers must attack
1. Stage-1 grounding: is multi-reference + spans + RESOLVED_ALLOWED-only trust correct and sufficient? Any
   ordering/perf trap? Does removing the syntactic-presence branch regress the terse-follow-up/bias-to-fetch
   behavior (`eval/multiturn-routing.json`)?
2. Stage-2 masking: the neutral-token choice, empty-residual→clarify, multi-span reverse-offset, alignment
   failure fail-open — any hole that leaks an entity into routing or masks the wrong span?
3. Stage-3 guard: is the v1 domain circuit-breaker safe (no false denial of a legit multi-domain ask)? Is the
   durable capability-group model right, and is `ResolverResult.primaryCandidate` the correct place/time?
4. Stage-4 eval: is measuring the masked path the right call vs testing raw + masking separately? Threshold
   re-baseline method?
5. Anything that sneaks a domain literal into gateway Java (World-B), and any MISSING failure mode.
