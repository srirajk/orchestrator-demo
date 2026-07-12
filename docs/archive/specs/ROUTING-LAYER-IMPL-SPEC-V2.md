# Routing layer — implementation spec V2 (capability-first routing, SOTA)

Supersedes v1. Incorporates the reconciled Fable + Codex-SOL reviews. **The core correction: grounding /
masking / continuity are CONTROL-FLOW changes, not fields on existing records.** World-B: no domain literal,
id-pattern, entity-type word, or client name in gateway Java — only manifest keys, agent/domain/subdomain
ids, generic status enums, character offsets, and score arithmetic. `world-b-check` CRITICAL stays 0
(report actual before/after; manual World-B review is the real gate since the grep is stale).

## Goal & proven evidence
Route on the **capability asked**, not the **entity named**; close bug-261 as a CLASS. Proven: de-entiting
the corpus (Stage 0, done, local) fixed the mis-route (`settlements for Continental` → settlement_status
0.637, no insurance); masking a query recovers the recall it costs (`Whitman's goals funded` 0.393 → masked
0.798). Both halves validated; V2 builds them correctly plus the group model, guard, and production-path eval.

## V2.1 resolutions (Fable-validated 2026-07-11 — build-ready after these)
**4 required edits:**
1. **Interim disposition (so Pieces 1-3 land before 4):** while the group model (Piece 4) doesn't exist yet,
   the **focal mention's verdict drives the existing single-`GroundingResult` terminal lattice**
   (`ChatService.java:396-432`); **non-focal mentions contribute masking + relaxation ONLY** (never a
   request-global deny). This keeps the DO-NOT "no deny-on-any" while 4 is pending.
2. **Spans are gateway-DERIVED, never LLM-emitted.** The extractor returns `verbatimText` + `messageId`;
   the gateway computes `[start,end)` by deterministic alignment of `verbatimText` within that message
   (normalization-to-original index map). LLM char offsets are unreliable; alignment-miss semantics depend
   on the gateway owning the span.
3. **Focal compatibility-view rule:** when ≥2 mentions share one `entityKey`, the compat scalar =
   latest-turn **explicit** mention, else carried **anaphora** (mirror `deriveFocalReference`,
   `IntentClassifier.java:484-508`).
4. **Non-coverage-scoped resolvable ids keep relaxation (deterministic, not LLM-presence trust):** a type
   that is resolvable but `resource_scoped:false` (e.g. `corporate-actions` `fund_id`/`FND-\w+`) can never
   RESOLVED_ALLOWED-ground, so a deterministic **manifest `id_pattern` match** on such a type earns the
   routing relaxation (this is deterministic, unlike the syntactic-presence trust we removed). Add a
   `FND-` terse-follow-up eval row (`multiturn-routing.json` only has REL-).

**5 minors:** DENIED-but-resolved mentions ARE masked (both statuses have a canonical resolution) · the
"near-empty" stopword/tokenizer policy is **config-driven** (no Java static list, CLAUDE.md §5) · "the 4
thresholds" → **all interacting resolver knobs enumerated from config** (confidenceFloor, decisiveScore,
domainMargin, routingMinScore, routingMinMargin, relativeFloorFactor + rerank triggers) · the Piece-5
**conflict trigger ships config-OFF, enabled only after the top-k recall gate passes** (needs the Piece-6
endpoint) · DoD reads "≥ the frozen **C-baseline** target (report numerator/denominator)", 96.3% aspirational.
**DAG closure timing:** derive `producerClosure` intra-group (ground/bind → closure → authz), not at group formation.

## DO-NOT list (both reviews block these)
- **NO v1 domain circuit-breaker** — it denies legitimate mixed requests when the pruned domain leads.
- **NO request-global "deny on any interpretation"** — verdicts attach to requested GROUPS.
- **NO fail-open masking that keeps the trusted routing relaxation** — an unmasked mention loses relaxation.
- **NO inferring requested groups from every floor-passed candidate** — that turns embedding noise into intent.
- **NO Java precedence table / domain enum / type-word mask token** (ban `EntityType.display/resolveType/key`).
- **NO silent flat-fallback after an authorization producer prune in the DAG path.**

---

## Piece 1 — Extractor mention/provenance model (foundation; do FIRST)
**Problem:** `IntentClassifier` returns one scalar per resolvable `extract_as` (`IntentClassifier.java:370-410`);
`EntityBag` is `Map<String,String>` (`EntityBag.java:18-33,47-50`) — one value per key, no offsets. Multi-name
and span-precise masking are impossible.
**Change:** extractor returns a generic **list of mention records**, keyed by manifest entity key:
`{ entityKey, verbatimText, messageId, [start,end) span, source ∈ {explicit, anaphora} }`. Keep the focal
scalar as a derived compatibility view. Map/list-based, no per-type Java field. Prompt is manifest-compiled
(World-B rule 4) — this is an output-contract change, separately testable.

## Piece 2 — Grounding over mentions (multi-reference, all interpretations, full verdict data)
**Problem:** `ReferenceGroundingService.ground` grounds one strongest reference, span-less
(`:61-66`, `GroundingResult:117-124`); `identifyByReference` returns first HashMap match (`DomainManifestStore.java:365-384`).
**Change:** ground **each mention**; for each, return **all eligible manifest interpretations** (a name valid
in several sub-domains → several), each `{ canonicalId, entityKey, subDomain, coverageContract, status ∈
{RESOLVED_ALLOWED, DENIED, AMBIGUOUS, NOT_FOUND, UNAVAILABLE}, denialReason, sourceKind, messageKey, span,
mentionKind }`. **Retain UNAVAILABLE (fail-closed) + denialReason** (v1 dropped them). Determinism: return-all
makes ordering non-semantic; sort by a stable key (or a manifest-declared priority — never a Java table).
**Budgets (required):** max mentions, max interpretations/mention, dedupe key `(coverageEndpoint, resolveType,
reference)`, **bounded concurrency**, stage deadline + cancellation, deterministic aggregation when a coverage
service is down. Coverage RESOLVE+CHECK each block ≤5s (`CoverageClient.java:85-147`) — naive N×M is a latency
bomb (VT history); parallelize + cap + dedupe. Hoist compiled regexes at manifest load (v1 wrongly claimed
they're precompiled — `DomainManifestStore.java:340-344`, `ChatService.java:1403-1407`).

## Piece 3 — Shared pre-routing preparation pipeline
**Problem:** entity/window/mask/route logic differs across FETCH_DATA, FOLLOW_UP (`handleFollowUp:1152-1167`
grounds AFTER an unmasked probe), and the eval endpoint — three drifting copies. Removing syntactic
`entityKnown` (`:446-447`, `:1429-1444`) alone regresses the follow-up fetch path (`multiturn-routing.json`).
**Change:** one component `PreparedRoute` used by all three, ordered: **derive mentions+focal → choose bounded
turns → ground mentions in those turns → mask resolved spans per message → residual/action policy → routing
relaxation signal**. Rules:
- **Relaxation trust ONLY on RESOLVED_ALLOWED** (kills the syntactic-presence trust). FOLLOW_UP passes the
  same prepared memo through — no double resolve/check.
- **Masking is message-local:** spans index the ORIGINAL message; mask each selected message, THEN join
  (never translate offsets through trim()+newline concat, `:1374-1386`). Merge overlapping/nested/duplicate
  spans (name + id: "Calderon Trust (REL-00099)"); mask all occurrences of a resolved id in-window.
- **Carried focal reference** (anaphora) has no latest-turn span → mask its prior mention if that turn is
  in-window; if not maskable, it may still authorize relaxation but see next rule.
- **Alignment miss / unmasked mention → masking INCOMPLETE:** that mention gets NO relaxation, normal
  score/margin abstention applies; prefer capability clarify/abstain over trusting the raw entity route.
- **Empty/near-empty residual:** first **widen** to the full masked window (`buildRoutingQuery` all turns
  masked); clarify only if the full masked window still has no action text. "Near-empty" via a generic
  tokenizer/stopword policy (not a char count, not a domain word list).
- **Mask token** = config-driven neutral deictic; sanitize user-typed occurrences of the token first.
- **Trace:** record mask mode, #mentions, masked spans, alignment misses, residual class, raw+masked text
  hash (for glass-box/audit) — do not log new raw entity values gratuitously.

## Piece 4 — Requested-capability-group model + per-group disposition (replaces first-survivor + v1 guard)
**Problem:** post-prune proceeds with survivors, no capability check (`ChatService.java:521-542`); coverage
checks only the FIRST resource-scoped survivor (`:584-614`) and binds one id into one shared bag (`:789-814`);
DAG producer prune silently flat-falls-back (`:1043-1053`). The reranker can't name groups: output is
one-id/`multiple`/`abstain`, `multiple` keeps all candidates (`AgentResolver.java:323-338`).
**Change — `RequestedPlan(groups[])`**, each group: `{ requestedCapabilityIds, kind ∈ {flat, dag}, goalId?,
producerClosure?, requiredEntityKeys, routingEvidence }`. Groups derived from the **masked** query's
above-floor candidates by capability/domain + the expanded reranker (Piece 5) — NOT from raw noise.
- **Per-group disposition:** structural auth + coverage + binding + execution + withhold run **per group**,
  then merge allowed groups. All groups denied → structural denial; some denied → serve allowed, report
  denied-group copy; DAG required producer denied → deny that group (never flat-fallback); noise/optional
  candidate denied → no user-visible denial.
- **`ResolverResult.primaryCandidate`** (agent_id + **subDomain** + domain) = diagnostic leader after rerank
  reorder, before filter (`:340-351`); it is NOT the fulfillment contract — the group is.
- **Denial copy:** new **generic manifest `messages` key** for "requested capability unavailable/unauthorized"
  (schema already accepts arbitrary keys, `sub-domain-manifest.schema.json:56-59`), delivered via `msg(key,
  fallback, subDomainId)` (`:1229`); derive subDomain from the retained pre-authz candidate — never HashMap
  roulette (`:250-273`, bug-238 class). Fallback string stays generic.
- Preserve the shipped bug-260 withheld-scoping (`ChatServiceWithheldScopingTest`) — it becomes the
  all-groups-denied vs some-served distinction.

## Piece 5 — Expanded bounded reranker contract (shared with Piece 4 + conflict trigger)
**Change:** `RoutingRerankerClient` returns `single(id)`, `multiple([ids])`, or `abstain` — `multiple` now
carries **explicit shortlist ids** (validated: in-shortlist, unique, non-empty, capped; each = a distinct
requested facet). Invalid/error on a conflict path → **abstain** (do not blanket-revert the near-tie suppress
— thread a trigger-reason tag into `RerankApplication`; `embeddingFallback` currently suppresses margin
abstain uniformly, `:352-356,393-395`). Feed the **masked** query + de-entitied examples
(`LlmRoutingRerankerClient.java:72-95`). Conflict trigger (leader-domain ∉ grounded refs' domain SET) is a
**rerank/abstain hint only** — never a denial or correctness rule (bug-261 is a *legit* cross-domain case).
Gate **top-k recall** before trusting rerank — a contaminated shortlist can exclude the right capability
(`rerankMaxCandidates`, `:320-324`); the LLM can't recover an absent candidate.

## Piece 6 — Production-path eval as the release gate
**Change:** extract the Piece-3 preparation behind a **test-profile decision endpoint** (same message DTO +
authenticated principal + caller token; stops before agent invocation; returns prep version, grounded
statuses, masked text, resolver decision, requested groups, post-auth disposition — all candidates incl.
below-floor). Harness asserts `path == production` + masking version. Use **each row's persona token** (cache
per persona, `measure_goal_pick.py:303-325`); put confusers IN the gate; **gate exact-capability accuracy**
(today only domain/abstain/poaching gate, `:275-299`). New datasets: `capability_entity_conflict`,
`name_invariance`, multi-reference, same-name-cross-domain, alignment-miss, entity-only, multi-turn/facet-carry.
**Re-baseline:** freeze {model id, index hash, reranker cfg, dataset rev}; save **A** (pre-Stage-0 raw),
**B** (post-Stage-0 raw), **C** (post-mask production) score vectors; **joint** grid-search the 4 thresholds
(they interact — `AgentResolver.java:212-230,261-300`) under hard constraints (wrong-domain substitution=0,
OOS abstain=100%, no denied-group invocation, min per-capability recall), then maximize held-out exact
capability accuracy on a stable plateau; grouped calibration/held-out split; report numerator/denominator.
Stamp {mask token, corpus hash, model id} into `eval/goal-pick/baselines`.

---

## Build order (dependency-correct; stages 1-2-3 land TOGETHER — not independently shippable)
1. **Piece 1 (mention model)** + **Piece 2 (grounding)** — the foundation; unit tests for multi-name, two-ids,
   one-name-two-domains, allow+deny across interpretations, negated-clause denial, one-unavailable, budgets.
2. **Piece 3 (prepared pipeline)** — wire FETCH_DATA + FOLLOW_UP through it; `multiturn-routing.json` is a
   production-path regression guard (mt_04/05/07/09).
3. **Piece 4 (group model)** + **Piece 5 (reranker contract)** — together; per-group disposition + primaryCandidate.
4. **Piece 6 (eval)** — decision endpoint + gates + A/B/C re-baseline; then re-run and lock thresholds.
5. Retire the de-entity local edits into the same commit; run full suite + world-b-check.

## Definition of done (one green commit)
bug-261 → capability-clarify or denial, **no insurance agent invoked, no insurance facts** (assert invoked
ids + fact absence, not just text); entitled servicing persona → settlement_status invoked; genuine mixed
insurance+servicing → entitled group served, denied group withheld honestly; goal-pick **production path** ≥
96.3% baseline with wrong-domain substitution=0 and OOS abstain=100%; top-k recall gated; full gateway suite
green; `world-b-check` CRITICAL 0 + manual World-B review clean.

## 15 failure modes that MUST have tests (from Codex-SOL)
multi-reference same type · one mention/multiple interpretations · negation/incidental entity · repeated/
overlapping/Unicode spans · anaphora outside the routing window · adversarial alignment-miss · entity-only /
entity+stopwords · capability absent from top-k · same-domain substitution · mixed requested vs noisy domains ·
DAG producer denial/unavailability · multiple resource-scoped groups · coverage latency/deadline exhaustion ·
stale index/model/token calibration · debug/prod drift.
