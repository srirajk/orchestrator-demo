# Spec: Decouple request disposition from routing confidence (clarify-path class fix)

**Status:** designed (Fable) + verified against code (Opus). Awaiting go to implement.
**Fixes the recurring class:** A2 (#41), A6 (vague clarify), S7-injection (deny dodged), Tesla (wrong-type entity → false "outside your access"). Supersedes the `rerankPickScoreTolerance` band-aid.

## Root cause (verified against ChatService.java)
Deterministic, security-relevant dispositions — **CLARIFY** (`required ∩ extracted = ∅`) and **coverage DENY** — live *inside* the coverage pipeline (ChatService:581–757), which is gated behind the **terminal route-abstain branch** (ChatService:455–481). A continuous, phrasing-sensitive embedding score is therefore a hard precondition for discrete decisions whose inputs (the extracted entity bag from step 1, manifest `required_context`, the coverage service) **do not depend on routing at all**. Any input that depresses the score below any of the three stacked abstain gates (possessive apostrophe, bare facet word, injection prose) silently converts a would-be DENY/CLARIFY into the no-service message. That's why every prior fix was a threshold and the class kept recurring.

Tesla is the same gap from the other side: `DagPlanExecutor.evaluateNodeCoverage` converts a *blank/unresolved* entity-ref into `coverageDenied(...)` — an **unresolved reference becomes an access verdict** — which synthesis honestly renders as "outside the user's access." CHECK can't tell "not a client at all" (Tesla) from "a client not in your book" (Sterling).

## The fix (one sentence)
**The semantic score may only ever choose between safe outcomes (which agents / clarify wording / no-service); it must never sit between the user and a DENY or a CLARIFY, and CHECK must only ever receive IDs that RESOLVED.**

### Stage 1 — Reference grounding (deterministic, pre-routing)
Generalize the typed-ID pre-check (367–425) to all reference sources, per manifest resolvable `entity_type`. Verdict lattice:
- RESOLVED → CHECK denies → **terminal DENY** (owning sub-domain copy), no routing.
- RESOLVED → CHECK allows → record `GroundedReference`, route with `entityKnown=true`, memoize (no double resolve).
- AMBIGUOUS → fall through to existing `discover ∩ candidates` clarify.
- NOT_FOUND → **demote to content** (stays in the agent prompt; never fills a coverage slot, never a CHECK, never a denial/WITHHELD). ← fixes Tesla.

### Stage 2 — Semantic routing (unchanged mechanics, demoted authority)
`resolveContextual` unchanged; its abstain is now a *routing outcome*, not a *request disposition*. A grounded+allowed reference enables a deterministic domain-filtered narrowing retry.

### Stage 3 — Abstain triage (replaces the terminal branch 455–481)
1. (existing) history synthesis when `carryContext && hasPriorAssistantData`.
2. **Deterministic required-entity CLARIFY:** from the broad candidate list (`ResolverResult.skipped`), filter through `entitlementService.filterAgents` (fail-closed), take the best *allowed* resource-scoped candidate with a declared `required_context`; if `extracted ∩ required = ∅` and score ≥ existing `confidenceFloor` (reused, not a new knob) → `coverageClient.discover` + `buildClarificationQuestion`. ← fixes A6.
3. Clean no-service only when neither applies.

### Stage 4 — Routed pipeline
Coverage pipeline consumes the Stage-1 memo instead of re-resolving. **DAG fix:** `evaluateNodeCoverage` stops returning `coverageDenied` for blank/unresolved entity-ref inputs — an entity-ref binds only from the resolved-values map; an unbound required ref is a bind-failure → flat-fallback/gap, never a denial. Legitimate Cerbos-pruned WITHHELD path untouched.

## Why it kills the class (not a symptom)
- **A2:** "Okafor" grounds pre-routing → resolves → denies, at any score. Band-aid retired.
- **A6:** no ref grounds → abstain triage finds holdings candidate w/ unmet required entity → CLARIFY with in-book options.
- **S7-injection:** injection prose only dilutes the *embedding*; grounding sees extracted "Sterling Capital Partners" → resolves → denies. Deny never transits the router → injection-robust by construction.
- **Tesla:** "Whitman" resolves+allows; "Tesla" doesn't resolve as a relationship → demoted to content → answered from holdings ("no TSLA position"). Instrument-level entities later = a manifest `entity_type`, no gateway change.

No new threshold anywhere; one reused; one band-aid removable.

## Invariants (verified)
CLARIFY stays deterministic (same Java set-intersection over manifest data). RESOLVE stays principal-agnostic (same `coverageClient.resolve`). CHECK remains the only gate — grounding moves *when* it runs, never bypasses it; fails closed on `CoverageUnavailableException`; and CHECK now only ever sees resolved entities. Zero domain knowledge added (all inputs manifest-derived). `scripts/world-b-check.sh` CRITICAL must stay 0.

## Implementation order (independently shippable)
1. `DomainManifestStore.identifyByReference(EntityBag, prompt)` beside `identifyByIdPattern`.
2. `ChatService`: extract 367–425 into a grounding method (or `ReferenceGroundingService`); add extracted-ref source + 4-way lattice + memo. **← ships S7 + A2 first (security-visible).**
3. `ChatService`: rewrite abstain branch 455–481 into 3-step triage. **← A6.**
4. `ChatService`: coverage pipeline consumes memo; backstop unchanged.
5. `DagPlanExecutor.evaluateNodeCoverage`: unresolved entity-ref → bind-failure, not `coverageDenied`. **← Tesla.**
6. Retire `rerankPickScoreTolerance` once tests prove A2 covered.
7. Tests (extend `RedisContainerTest`): A2, A6, S7-injection, Tesla-in-portfolio, off-topic no-service, in-book happy path, injection-wrapped in-book client (never leak/deny), coverage-unavailable fail-closed. world-b-check before/after = 0.

## Risks (from design, all with existing precedent)
Extractor false-positive fill (mitigated by unique-resolution-only backstop); multi-ref mixed verdicts (v1: any resolved-denied → terminal deny naming it; partial-fulfil = follow-up); pre-routing CHECK vs Cerbos ordering (precedent: typed-ID pre-check already pre-Cerbos); existence-oracle (pre-existing, not new); +1 resolve/turn offset by memoization; DAG bind-failure must surface as MISSING, not vanish.
