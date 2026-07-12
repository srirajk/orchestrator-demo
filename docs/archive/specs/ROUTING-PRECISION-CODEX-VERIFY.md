# Codex verification brief — generic routing precision (flagship design)

Run this on **SOL**. Task = **independent architecture verification** (design only; do NOT edit code).
Repo: this repo, branch `conduit-platform`. This is a **World-B** gateway: the Java/Spring gateway
carries **zero domain knowledge**; domains onboard via manifest JSON (`CLAUDE.md`, `.claude/rules/world-b.md`).
The gateway's **generic routing is the product's selling point**, so any fix MUST be generic
(manifests / config / eval — **no per-domain gateway Java**). Verify the design below against the ACTUAL
code. **Be a harsh independent critic — do NOT defer to it. Where you DISAGREE is the most valuable output.**

## The bug (bug-261, verified via runtime trace)
Persona `uw_sam` (insurance underwriter; owns policy "Continental Freight"; has **no** asset-servicing
segment) asks **"pending settlements for Continental Freight"** → answered with **INSURANCE** data
(claim/policy/renewal), not settlements. Trace: asset-servicing agents `settlement_status`/`settlement_risk`
were SELECTED by routing, then GATE-DENIED by entitlements (no servicing segment), and the pipeline
proceeded with the surviving **insurance** agents. Not a data leak — but the **capability asked**
(settlements = asset-servicing) lost to the **entity signal** (Continental = insurance policy).

## Code facts to verify yourself (cite file:line; confirm or refute each)
1. Routing embeds the RAW query into an HNSW vector search over each agent manifest's `examples`
   (`registry/manifests/*/*.json`), score-gated (`routingMinScore`~0.40), with an LLM re-ranker for
   near-ties (`gateway/.../resolver/service/AgentResolver.java`, `LlmRoutingRerankerClient.java`,
   `VectorIndexWriter.java`, `VectorIndex.java`).
2. **The corpus is entity-contaminated:** "Continental Freight" appears literally in the insurance
   agents' `examples` (claim_status/policy_details/renewal_risk), while settlement_status's examples use
   a different client. One vector per raw example string. So the entity name lexically anchors routing.
3. A recent "grounding" change resolves the named entity PRE-routing (`ReferenceGroundingService.java`)
   and sets `entityKnown=true`, which at `AgentResolver.java` ~216 and ~276 **bypasses** the domain-margin
   and min-score/min-margin abstain gates — i.e. makes routing *more* credulous when an entity is named.
4. After routing, `ChatService.java` ~521 (`entitlementService.filterAgents`) prunes agents the principal
   can't invoke; it only checks `allowedManifests` is non-empty, **not** that survivors still cover the
   asked capability. So even perfect routing + a prune reproduces bug-261.
5. `DomainManifestStore.identifyByReference` (~365-385) returns the FIRST sub-domain (map iteration order)
   whose `extract_as` slot the entity fills — so "the entity's domain" is a nondeterministic first-match.
6. Routing eval: `eval/goal-pick/labeled_queries.json` + `measure_goal_pick.py` (hits `/debug/resolve` —
   routing only, no entitlement prune, no synthesis). Claim to verify: its queries pair each entity with
   its HOME-domain capability (zero conflict cases) and reuse the same client names that sit in the
   corpus — so the gate is inflated by the same anchoring that causes the bug and is structurally blind to it.

## The design to verify (produced by another model — Fable; treat skeptically)
- **P0 — post-prune capability honesty (claimed the biggest fix):** after `filterAgents`, if EVERY
  candidate sharing the routing *leader's* domain was pruned, do NOT proceed with lower-ranked survivors
  from another domain — emit the manifest structural-denial copy. Compare domain-of-leader vs
  domain-of-survivors only (no domain literal).
- **P1 — de-entity the corpus (the World-B lever):** rewrite instance names out of manifest `examples`
  → neutral placeholders ("pending settlements for this relationship"); add an ingest-time collision lint
  (per-example nearest cross-agent neighbor similarity); re-baseline score thresholds after the shift.
- **P2 — query-span masking + conditional `entityKnown` (defense in depth):** mask the grounded entity
  span in the routing text with a NEUTRAL config token (NOT the entity-type word, which re-injects the
  domain); residual-content floor with fail-open to the context-enriched query. Make the `entityKnown`
  bypass conditional on leader-domain == grounded-entity-domain; on disagreement, re-arm the normal
  gates + reranker (do NOT override or force a clarify — let the existing deterministic `required_context`
  clarify fire if needed).
- **P3 — reranker:** feed it the masked query text + fire it on a domain-conflict trigger + one generic
  prompt line ("match the action asked, not the names mentioned").
- **Eval upgrade:** add `capability_entity_conflict` cases; a **name-invariance** metric (same capability
  query with corpus-name vs novel-name vs bare-id must route identically); per-agent precision/recall +
  confusion matrix; one persona-scoped e2e asserting the bug-261 RESPONSE is a denial/clarify, not another
  domain's data.

## Deliverable (the research report)
1. Verify each code fact (1-6) with file:line — right/wrong?
2. **P0:** is it correct, safe, and the right top priority? Precisely how should "leader's domain fully
   pruned" be defined so it does NOT break legitimate multi-domain fan-out (where the leader's domain is
   entitled AND a second domain is also legitimately asked)?
3. **P1:** is de-entiting the corpus the right PRIMARY fix? Does the eval seed's reuse of corpus client
   names actually inflate it? Is per-example nearest-neighbor the right collision metric (vs centroid)?
4. **P2:** any trap in span-masking (empty residual for entity-only asks; neutral vs type-word token;
   multi-entity; surface-form drift)? Is "disagreement re-arms gates, never overrides/clarifies" correct?
5. What is WRONG, RISKY, or MISSING in P0-P3 + the eval plan — and what would YOU do differently?
6. Confirm the whole design stays World-B (no domain knowledge in gateway Java; `scripts/world-b-check.sh`
   CRITICAL unaffected). Flag anything that would sneak a domain literal into gateway code.

Return a concrete report with file:line citations and an explicit list of DISAGREEMENTS with the design.

## Output location (IMPORTANT)
**Write the full report to `docs/specs/ROUTING-PRECISION-CODEX-SOL-REPORT.md`** in this repo (create it).
Use plain Markdown. Include, near the top: the model you ran as (should be SOL), a one-line verdict
(design sound / needs changes / reject), and then the section-by-section findings. Also print a short
summary to stdout, but the file at that path is the deliverable Claude will read to reconcile.
