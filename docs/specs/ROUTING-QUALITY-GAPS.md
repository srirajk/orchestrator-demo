# Routing Quality Gaps — Root-Cause Analysis (pre-fix)

Investigation of three routing-quality gaps via code reading + live `POST /debug/route` probes
(rm_jane, 2026-07-12, stack healthy). No fixes applied; this is the mechanism record Opus
implements from. All probes are single-turn unless noted; scores are cosine similarities from
the live HNSW index.

**Config in force** (`gateway/src/main/resources/application.yml`):
`conduit.resolver.confidence-floor=0.30` (yml:130), `domain-margin=0.05` (yml:138),
`conduit.routing.min-score=0.40` (yml:149), `min-margin=0.005` (yml:152),
`rerank.margin-threshold=0.13`, `abstain-adjacent-band=0.08`, `max-candidates=5`,
mask token `"the subject"`.

---

## Probe evidence table

| # | Query (single turn) | masked routing text | mentions/spans | relax | topScore | rerank | disp |
|---|---|---|---|---|---|---|---|
| P1 | Compare **Calderon's** concentration to **Whitman's** | *(unmasked)* | 0 / 0 | no | 0.310 | fired, LLM **picked** risk_profile | **ABSTAIN** |
| P2/G | compare the concentration of **Whitman and Calderon** | *(unmasked)* | 2 / 0 | no | 0.334 | fired, picked | **ABSTAIN** |
| R | …concentration of Whitman **with the** concentration of Calderon | *(unmasked)* | 0 / 0 | no | 0.312 | fired | **ABSTAIN** |
| H | What is **Whitman's** concentration? | *(unmasked)* | 3 / 0 | no | 0.378 | fired | **ABSTAIN** |
| Q | what is the concentration for **Whitman** | *(unmasked)* | 3 / 0 | no | **0.390** | fired | **ABSTAIN** |
| C | what is the concentration for **Calderon** | "…for the subject" | 3 / 1 | yes | 0.499 | fired, picked risk_profile | SERVED |
| A | compare the concentration of **Whitman Family Office and Calderon Trust** | "…of the subject and the subject" | 4 / 2 | yes | 0.467 | fired | **SERVED** |
| N | compare **Whitman's performance and risk profile** | **"compare the subject"** | 3 / 1 | yes | 0.305 | fired, abstained | **ABSTAIN** |
| E/F | give me a summary of the Whitman Family Office **holdings** (×2) | "…of the the subject **holdings**" | 3 / 1 | yes | **0.610** | fired | **SERVED** (stable ×2) |
| L | summarize the Whitman Family Office **relationship** | "…the the subject relationship" | 3 / 1 | yes | 0.502 | fired | SERVED (primary: corporate_actions — noisy pick) |
| J/O | give me an **overview** of the Whitman Family Office (×2) | "…overview of the the subject" | 3 / 1 | yes | 0.333 | fired, **LLM abstained** (×2) | **ABSTAIN** |
| K | can you **brief me on** the Whitman Family Office | "…brief me on the the subject" | 3 / 1 | yes | 0.360 | fired, LLM abstained | **ABSTAIN** |
| M | give me a **summary** of the Whitman Family Office *(no noun)* | "…summary of the the subject" | 3 / 1 | yes | **0.284** | **not fired** | **ABSTAIN** |
| D/I | compare the **performance** and the **risk profile** of Whitman [Family Office] | "…of the subject" | 3 / 1 | yes | 0.513 | fired → **multiple** `[performance, risk_profile]` | **SERVED**, **2 rerank-facet groups**, both SERVED |

Supporting logs: `wealth-coverage` logged `RESOLVE reference='Whitman'` (probe I — bare surname
**resolves fine** via alias substring match, `mock-agents/wealth-coverage/data.py:131-141`,
aliases include `"whitman"` at data.py:15) and `RESOLVE reference="Whitman's performance and
risk profile"` (probe N — the **greedy verbatim** resolved via substring). **No RESOLVE call at
all** was made for P1, P2, H, R, Q — those failed **before** RESOLVE. Gateway log for Q:
`IntentClassifier … "the user is asking for specific data about Whitman, likely a fund or
asset"` — the extractor mis-typed the mention (fund_reference vs relationship_reference), so
grounding never consulted the relationship resolver. Gateway log for P2: `Resolver rerank
picked meridian.wealth.risk_profile: "…can be used to compare Whitman vs Calderon"` — the
reranker chose **correctly** and the floor then discarded the route.

---

## Gap 1 — Multi-entity compare returns a generic clarify

### Mechanism (confirmed)

Two independent failure stages, **both upstream of the resolver**; the confidence machinery
then behaves exactly as designed on the degraded signal it receives.

**Stage 1 — LLM entity extraction is fragile on possessive / coordinated phrasings.**
The extractor (`prompts/entity-extractor.system.md`, field rules compiled from manifest
`entity_types`) fails three ways, all probe-confirmed:
- extracts **zero mentions** ("Compare Calderon's concentration to Whitman's" → mentionCount 0,
  probe P1; likewise R);
- extracts mentions that **never reach RESOLVE** — mis-typed (Q: bare "Whitman" typed as a
  fund) or otherwise ungroundable (H, G: mentions 2-3, `grounded: []`, zero RESOLVE calls in
  the coverage log);
- extracts a **greedy verbatim** that resolves but swallows the capability words (probe N,
  next section).

The coverage RESOLVE itself is *not* the problem: bare "Whitman" and "Calderon" both resolve
via alias substring match when the extractor actually emits them (probes C, I; data.py:15,24).

**Stage 2 — unmasked names dilute the embedding into the junk band, and the floor correctly
abstains.** With no resolved mention there is no masking and no relaxation
(`RoutePreparer.prepare`, `RoutePreparer.java:115-186`), so the proper names stay in the
routing text. `all-MiniLM-L6-v2` scores the name-laden compare queries 0.31-0.39 — inside the
documented junk band (yml:146: "known junk matches (~0.31-0.34)") and below
`routing.min-score=0.40`. In `AgentResolver.select`, the abstain fires at
**`AgentResolver.java:309-311`**:

```java
boolean routingAbstain = !entityKnown
        && (topScore < routingMinScore
            || (!rerank.suppressMarginAbstain() && topMargin < routingMinMargin));
```

Note the asymmetry: a rerank **pick** suppresses only the *margin* abstain
(`suppressMarginAbstain`), never the *absolute* `min-score` check — so the reranker's correct
pick on P1/P2 (log: "picked risk_profile … compare Whitman vs Calderon") was computed, then
discarded. The chat path then lands in the abstain triage (`ChatService.java:505-540`) and
emits the generic "needs more detail" clarify. That is the exact user-observed symptom.

**Two-entity masking is NOT the blocker for routing.** Probe A ("…of the subject and the
subject") routed fine at 0.467 SERVED — the compare intent survives both entities collapsing to
the same mask token, because the grounded entities stay distinguishable in
`GroundedReferenceSet` (masking only affects the *routing text*). **But** even the served case
cannot actually be fulfilled: disposition and binding consume a **single focal mention** —
`ReferenceGroundingService.java:208-218` picks one `focalIndex`, and the record contract says it
outright (`ReferenceGroundingService.java:456-457`: *"the interim disposition drives from the
FOCAL mention only … Non-focal mentions carry no disposition weight"*). Requested groups are
per-**capability facet**, never per-entity (`ChatService.buildRequestedPlan`,
`ChatService.java:1377-1394`). So "compare A and B" executes as "fetch for the focal client
only" — there is no per-entity fan-out anywhere in the pipeline.

### Fix direction

1. **TUNING (extractor prompt)** — harden `entity-extractor.system.md` (the static skeleton;
   domain-neutral, World-B clean): possessive forms extract the base name ("Calderon's" →
   "Calderon"); coordinated names ("X and Y", "X with Y") emit one mention per name; a bare
   personal/org name defaults to the *resolvable reference* fields rather than a guessed type;
   verbatim spans cover the NAME only, not trailing capability nouns. This alone flips P1, P2,
   H, Q, R into the probe-C/I shape (masked → 0.47-0.53 → SERVED). Re-baseline via
   `eval/goal-pick` (`rebaseline.py`; add possessive/conjunction rows to
   `name_invariance.json`) and `scripts/smoke-route.sh` S1-S9.
2. **LOGIC (per-entity fan-out — the real compare)** — Piece-4 extension: when the grounded set
   holds ≥2 distinct `RESOLVED_ALLOWED` references of the same focal entity type, form one
   requested group **per entity id** over the routed capability (mirror of the existing
   per-facet `rerank-facet` groups), run coverage CHECK per entity (deny one / serve the other →
   honest PARTIAL, preserving rules d/f), bind each id, merge at synthesis. No reranker change —
   "multiple" stays per-facet. This is the only way "compare A to B" ever returns two clients'
   data; everything else just gets the route right.

---

## Gap 2 — Borderline conversational phrasing abstains

### Mechanism (confirmed — and the reported repro is already fixed)

The reported phrase "give me a summary of the Whitman Family Office holdings" is **SERVED
today** (0.610, stable across two probes) — the needle-tightening in
`RoutePreparer.java:108-134` keeps the action word "holdings" outside the mask, and 0.61
clears every gate. The *surviving* borderline class is framings whose masked residual carries
**no capability noun**, and the floor bites at three distinct, probe-separated points:

| Residual | topScore | Which gate bites | Evidence |
|---|---|---|---|
| "…of the the subject **holdings**" | 0.610 | none — SERVED | E/F |
| "summarize the the subject **relationship**" | 0.502 | none — SERVED (noisy leader) | L |
| "…**overview** of the the subject" / "**brief me on**…" | 0.333-0.360 | **LLM reranker abstains** (NEAR_TIE trigger, `AgentResolver.java:440-441`; abstain path `AgentResolver.java:393-397`). The score gates are *bypassed* here — relaxation granted ⇒ `entityKnown=true` skips both `min-score` and margin (`AgentResolver.java:309`) | J, O, K — deterministic across reruns |
| "…**summary** of the the subject" (no noun) | 0.284 | **`confidence-floor=0.30`** in the contextual confident-gate, `AgentResolver.java:248-249` — the one floor `entityKnown` does NOT bypass; abstains **before** select(), reranker never consulted (`rerankFired:false`) | M |

The `min-score=0.40` gate only bites this gap when relaxation was forfeited (the gap-1
extraction failures: H 0.378, Q **0.390** — 0.010 below the floor purely from the unmasked
name token).

So the real deficiency is **corpus vocabulary, not thresholds**: no manifest example teaches
the index that "summary / overview / brief me on <client>" means the holdings/relationship
capability. And the abstain is half-right — J's embedding *leader* was
`servicing.cash_management` at 0.333; serving it would have been a worse bug.

### Fix direction

**TUNING (manifest example corpus — zero gateway code).** Add conversational-summary phrasings
("give me a summary of the client", "overview of the relationship", "brief me on the account")
to the appropriate wealth agent manifests' skill examples, re-run registry ingestion, and
re-baseline `eval/goal-pick` (per `eval/goal-pick/REBASELINE.md`). Expected effect: residuals
like "overview of the subject" move from 0.33 into the 0.45-0.55 band (compare L at 0.502) and
the reranker then has a genuine candidate to pick instead of abstaining.
**Do NOT lower `min-score` or `confidence-floor`** — see over-correction risks.

---

## Gap 3 — Fan-out ("multiple") underused

### Mechanism (confirmed: the contract works; it is starved upstream)

The three-way contract fires correctly when the masked residual carries two facet nouns:
probes D and I returned `rerankSelectedIds: [meridian.wealth.performance,
meridian.wealth.risk_profile]`, and `buildRequestedPlan` formed **two `rerank-facet` groups,
both SERVED** (validated multi path: `AgentResolver.java:369-391` → `validMultiple`
`AgentResolver.java:469-484` → `ChatService.java:1377-1394`). "Multiple" is not broken.

It underfires for two upstream reasons, both shared with gap 1:

1. **Greedy masking swallows the facet nouns.** Probe N: extractor verbatim "Whitman's
   performance and risk profile" resolved as a whole (substring alias match), tightening
   *failed* — `RoutePreparer.java:122-124` requires the canonical name ("Whitman Family
   Office") to align as a proper sub-phrase of the verbatim, and it does not (only "Whitman"
   does) — so the whole span was masked and the routing text collapsed to **"compare the
   subject"** → 0.305 → reranker abstains (nothing to distinguish). The facet words never
   reach the reranker.
2. **Extraction failure → junk-band score → abstained before "multiple" can help.** Even if
   the reranker answered `multiple`, `RerankApplication.multiple` deliberately does **not**
   suppress the score/margin abstain (`AgentResolver.java:543-545` + `309-311`), and facet ids
   must be above the confidence floor to form groups (`ChatService.findManifest`,
   `ChatService.java:1425-1430`). With unmasked names at 0.31-0.39 and no relaxation, the
   query abstains regardless of the reranker's answer.

Also note: multi-**entity** compare queries correctly do *not* produce "multiple" — the
reranker treats them as one capability × two entities (log: single pick with reason "compare
Whitman vs Calderon"). Per-entity fan-out is gap 1's LOGIC fix, not a reranker prompt change.

### Fix direction

- Rides on gap-1 fix 1 (extractor prompt) for the starvation case.
- **LOGIC (small, RoutePreparer)** for the greedy-mask case: extend needle tightening with a
  deterministic fallback — when the canonical name does not align inside the verbatim, align
  the **longest prefix/token subsequence of the canonical that does** (here: "Whitman"), and
  mask only that. Same safety property as today: any miss ⇒ mask the full verbatim,
  byte-identical. No domain literal (`RoutePreparer.java:115-134`).
- No change to the reranker contract or to `multiple()`'s non-suppression of the abstain gates
  (that non-suppression is what keeps junk-band queries from fanning out).

---

## Shared-root verdict

**Gaps 1 and 3 share one root; gap 2 does not. The hypothesis "the confidence floor /
rerank-trigger is too conservative" is REFUTED.**

- The shared root of 1 and 3 is **entity-extraction/masking fragility on possessive,
  coordinated, and greedy-verbatim phrasings** — upstream of every threshold. When extraction
  works, the same queries route at 0.47-0.61 and even the three-way "multiple" path works
  end-to-end (probes A, C, D, I). The floors are biting *correctly* on the degraded routing
  text: every abstained probe's leader sat in or under the documented junk band (0.28-0.39),
  and in probe J the sub-floor leader was the *wrong agent* (cash_management for an overview
  ask) — a lower floor would have served it.
- Gap 2's residual failures are a **corpus vocabulary gap** (no summary/overview examples) plus
  one genuinely hard floor (`confidence-floor` 0.30 at `AgentResolver.java:248`, the only gate
  relaxation does not bypass) and the LLM reranker's own justified abstain.
- One genuine mechanical finding in the resolver: a rerank **pick** cannot rescue a sub-0.40
  leader (only the margin abstain is suppressed, `AgentResolver.java:309-311`), so below-floor
  NEAR_TIE reranks are computed and always discarded — wasted gpt-5-mini spend, worth a guard
  (skip rerank when `!entityKnown && topScore < routingMinScore`), but **not** a correctness
  fix: letting the pick override the floor would serve junk-band routes.

## Over-correction risks

- **Lowering `routing.min-score` (0.40) or `resolver.confidence-floor` (0.30):** the junk band
  is 0.31-0.34 (yml:146 + probes J/K/M); S6's off-topic abstain and the general
  abstain-on-junk guarantee die first. Probe J proves it concretely: at floor ≤0.33,
  "overview of Whitman" routes to **cash_management**. Do not touch either knob for these gaps.
- **Letting a rerank pick or `multiple` suppress the min-score abstain:** same junk-serve
  failure, plus the shortlist at low scores is contaminated (the yml documents this as the
  reason the conflict trigger ships OFF). Keep the floor authoritative.
- **Extractor prompt changes:** must not weaken the zero-fabricated-ID contract (rule 4b — keep
  "never invent an identifier" verbatim); extraction shifts can perturb the multi-turn
  switch/carry behaviour → rerun smoke-route **S8/S9** and the S3/S4 coverage denials, plus
  goal-pick `name_invariance`.
- **Manifest example enrichment:** moves *every* corpus vector; re-baseline goal-pick
  (REBASELINE.md) and rerun smoke-route S1-S9 (S6 especially — new examples must not pull the
  off-topic haiku above 0.40). No gateway code ⇒ world-b-check unaffected.
- **Per-entity fan-out:** keep RESOLVE principal-agnostic and run the coverage CHECK per entity
  (rule 4f); a denied entity must yield an honest PARTIAL, not cancel the sibling (rule 4d);
  don't regress S3/S4.

## Recommended implementation order (cheapest/safest first)

1. **Extractor prompt hardening** (TUNING, `entity-extractor.system.md` skeleton) — fixes the
   dominant failure mode of gaps 1 & 3 (5 of 7 abstained probes). Gate: goal-pick re-baseline +
   smoke-route S1-S9 green.
2. **Needle-tightening fallback** (small LOGIC, `RoutePreparer.java:115-134`) — recovers the
   greedy-verbatim class (probe N); deterministic, unit-testable, byte-identical on miss.
3. **Manifest summary/overview examples + re-ingest + re-baseline** (TUNING, zero gateway
   code) — closes gap 2's residual class.
4. *(Optional, efficiency only)* skip rerank when `!entityKnown && topScore < min-score`
   (`AgentResolver.rerankTrigger`) — saves LLM spend on routes that cannot survive.
5. **Per-entity requested groups** (LOGIC, `ChatService.buildRequestedPlan` +
   `ReferenceGroundingService` focal contract) — the only change that makes "compare A to B"
   actually return both clients' data. Do last: it depends on 1 landing so two entities
   reliably ground, and it touches the Piece-4 disposition machinery (S3/S4/S8/S9 full
   regression + a new smoke scenario).
