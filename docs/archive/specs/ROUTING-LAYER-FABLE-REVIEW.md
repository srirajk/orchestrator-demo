# Routing layer impl spec — Fable review (for reconciliation with Codex-SOL)

**Verdict: revise the spec before building.** Every code citation verified except one error (spec claims
id-pattern regexes are pre-compiled — they `Pattern.compile` per call: `ChatService.java:1407,1440`,
`DomainManifestStore.java:342`; must hoist).

## Priority fixes (Fable)
1. **P0 — Stage 3 v1 circuit-breaker violates our own DoD.** Mixed "claims for X AND settlements for Y":
   if the pruned domain leads, v1 denies the WHOLE request incl. the entitled half — regresses bug-260's
   serve-survivors+withhold behavior. Fix: key on an entity-pull-signature (deny only when primary's domain
   fully pruned ∧ every surviving domain ∈ grounded references' domains ∧ no capability-driven survivor), or
   ship the durable capability-group model directly. Do NOT ship v1 as written.
2. **P0 — Stage 2 empty-residual→clarify breaks `multiturn-routing.json` mt_05** ("Calderon Trust?" expects
   ANSWERED; window is 1 turn by design at `:1392-1394`; masking leaves "?"). Fix: widen window (mask
   `buildRoutingQuery(request)` all turns) BEFORE clarify; clarify only if full masked window has no action text.
3. **P0 — Stage 1 multi-reference has no source.** `EntityBag.references` is Map<String,String> (one value
   per key, `EntityBag.java:19,48-50`); `identifyByIdPattern` is single `m.find()` (`:343-344`). "Zero/one/many"
   needs an extractor output-schema change + find-all loop, AND an explicit **verdict-merge lattice**
   (ALLOWED-in-one-domain + DENIED-in-another for shared `relationship_reference` across 3 sub-domains;
   UNAVAILABLE-among-many). Terminal-DENY-on-any as written contradicts partial-result rule (d) / DoD bullet 4.
4. **P1 — Span model incoherent.** Stage-1 spans are vs `latestPrompt`; Stage-2 masks the composed multi-turn
   window (`:1386`). Re-derive spans against the FINAL routing text, match ALL occurrences, merge overlapping/
   nested spans (name + id: "Calderon Trust (REL-00099)"), then reverse-offset.
5. **P1 — Stage 4: drop the /debug flag.** The debug path already forks (measures `resolve()` not
   `resolveContextual()` — different gates). Extract ground→mask→`resolveContextual` into ONE pipeline behind
   a new `/debug/route` (caller's own token; returns all candidates incl below-floor + grounding verdicts +
   masked text + primaryCandidate). Gives Stage-5 top-k recall + re-baseline data free. **96.3% was never a
   chat-path baseline** — add an intermediate post-Stage-0 (unmasked) baseline so masking's cost is isolated.
   Threshold re-baseline = offline grid-search over a full score dump under hard constraints (OOS abstain=100%,
   wrong-domain-substitution=0), then confirm live; stamp {mask-token, corpus-hash, model-id} into baselines.
6. **P1 — second syntactic-trust site + scope gap.** `handleFollowUp:1161` also calls
   `hasGroundedResolvableReference` + an unmasked probe `resolveContextual(...,true)` (`:1162`) upstream of
   grounding (`:396`) — hoist grounding above the intent switch or the FOLLOW_UP path keeps the trust bug
   (mt_07 rides it). And resolvable-but-not-coverage-scoped types (`corporate-actions` fund_id,
   `resource_scoped:false`) can never RESOLVED_ALLOWED-ground → terse fund-id follow-ups lose bias-to-fetch.
7. **P2 — declaration order undefined** (glob→HashMap, `:110-118`): name a sort key or a manifest priority
   field (not a Java precedence table). Hoist `Pattern.compile`. State N×M coverage-call latency policy (VT history).
8. **P2 — Stage 5 plumbing:** define ONE routing-input carrier for grounded-domain metadata (shared by Stage 3
   guard + Stage 5 conflict trigger); thread a trigger-reason tag into `RerankApplication` so "preserve abstain
   on conflict" doesn't become a blanket revert of the near-tie suppress.
9. **P3 — trace raw+masked text; sanitize user-typed mask-token; verify `synthesis.inputs()` keySet ordering
   before trusting `dagGoal` determinism; Stage-3 denial copy needs subDomain (not just domain) or falls to
   HashMap-order roulette (bug-238 class).**

## Fable's sharpest disagreements (reconcile vs Codex-SOL)
- **(a)** v1 circuit-breaker is unsafe as specified — fails our own DoD.
- **(b)** neither flag-on-debug nor pre-masked eval queries — extract the pipeline; the 96.3% was never a
  chat-path baseline (debug path measures different gates).
- **(c)** Stages 2 & 3 are NOT independently shippable — masking's fail-open is only safe because the guard
  exists; the guard's false-positive rate is only acceptable because masking exists. They ship together.
