# Compare-CLARIFY — Deterministic clarify on incomplete multi-entity resolution

When one request names ≥2 distinct entity references but the pipeline binds FEWER, the gateway
today either serves ONE-SIDED silently or abstains — verified live (rm_jane, `POST /debug/route`,
2026-07-12). This spec adds a deterministic CLARIFY for that state, mirroring the single-entity
invariant already held (unresolved reference → clarify, never guess — `.claude/rules/world-b.md`
rules 2/6). Read with `docs/specs/MULTI-ENTITY-COMPARE-DESIGN.md` (D1–D5, implemented and in the
working tree) and `docs/specs/ROUTING-QUALITY-GAPS.md` (gap 1). Line numbers are against the
CURRENT WORKING TREE (uncommitted compare feature included).

**Verdict up front: the detection is deterministic and World-B clean.** The reliable case reads
statuses grounding already computed; the hard case is confirmed by a principal-agnostic RESOLVE
(no LLM, no CHECK, no domain literal). It does NOT chase perfect extraction.

---

## 0. Probe evidence (all this session, stack healthy)

| # | Probe (rm_jane) | Result today |
|---|---|---|
| PC-1 | "compare the concentration of the **Whitman Family Office** and **the Calderon account**" | `mentionCount=4`, grounded rows = 3 × `RESOLVED_ALLOWED` — ALL belong to the focal Whitman mention; "the Calderon account" got **zero interpretations** (wealth-coverage log: only `RESOLVE reference='Whitman Family Office'` — Calderon never reached RESOLVE), left **unmasked** in routing text, ONE `single-selection` group → **SERVED one-sided**. Reproduced twice. |
| PC-2 | "compare **Whitman's** concentration and **Calderon's**" | `mentionCount=2`, grounded = {} (zero interpretations at all), 0 spans, unmasked → **ABSTAIN**. bindings=0. |
| PC-3 | "compare the concentration of the Whitman Family Office and **Calderon's**" | This run the extractor CAUGHT the possessive: 6 × `RESOLVED_ALLOWED`, 2 entity-facet groups, **SERVED two-sided**. Extraction is **flaky, not broken** — the same class of phrasing sometimes works. Whack-a-mole confirmed. |
| PC-4 | S10 shape ("… Whitman Family Office and Calderon Trust") | Run 1 **ABSTAIN** (top 0.274, extraction flapped), run 2 **SERVED** with 2 `entity-facet` groups of 3 capabilities. The compare feature works when both ground. |
| PC-5 | "compare the performance and the risk profile of the Whitman Family Office" | 1 binding, 2 `rerank-facet` groups, SERVED — the capability-only multipart shape. |
| PC-6 | Direct wealth-coverage `/entities/resolve` sweep (authed) | `Compare/What/How/Meridian/Aspen/January/Zurich/Review/Global Equities/Continental Freight` → **NOT_FOUND**. `Trust/Family/Account` → **AMBIGUOUS** (multi-client alias hits). `Calderon`, `the Calderon account`, `Whitman`, **`Whitman's`** (possessive survives), `Okafor`, `Sterling`, `Rivera Diversified` → **unique RESOLVED**. One measured leak: bare `Office` → RESOLVED REL-00042 (substring-either-way aliasing, `mock-agents/wealth-coverage/data.py:135-138`). |

PC-1 is the reliable-case detection target; PC-2 is out of scope (zero bindings — see §2.4);
PC-3/PC-4 show why the fix must not depend on extraction being deterministic; PC-6 measures the
false-positive surface of resolve-confirmation precisely.

---

## 1. Detection predicate (both cases, deterministic)

Substrate already in hand at the hook point: `EntityBindingSet.derive`
(`gateway/src/main/java/ai/conduit/gateway/domain/coverage/EntityBindingSet.java:77-138`) — only
EXPLICIT latest-turn mentions form bindings; `RESOLVED_ALLOWED/DENIED/UNAVAILABLE` bind,
`NOT_FOUND/AMBIGUOUS` never do (`:100-105`); statuses come from
`ReferenceGroundingService.groundMentions` (`ReferenceGroundingService.java:164-219`,
`toInterpretation:295-317` — `canonicalId` is null on `NOT_FOUND/AMBIGUOUS` `:301-305`).

New method — `ReferenceGroundingService.detectUnboundReferences(groundedSet, bindings,
latestPrompt, tenantId, callerToken)` → `List<UnboundReference>` (verbatim, resolvedId|null,
ambiguous flag, candidates). It has two tiers:

### Tier A — extractor-asserted, grounding-status case (zero new I/O)

A candidate is any mention `m` in `groundedSet.mentions()` with:
- `m.source() == EXPLICIT && m.messageIndex() == latest user turn` (same turn rule as
  `EntityBindingSet.derive:80-97` — S8/S9 carry can never trigger this);
- `m` produced **no binding** (its interpretations are all `NOT_FOUND` / `AMBIGUOUS`);
- interpretations are **non-empty** (the extractor typed it into a resolvable coverage slot —
  `DomainManifestStore.interpretationsForReference:527-547` returns empty for non-entity slots);
- the verbatim contains a proper-noun token (`ChatService.PROPER_NOUN` pattern, `:2527-2528`) —
  kills the mis-typed-capability-noun class ("performance" can never fire);
- verbatim does not normalized-substring-overlap any bound mention's verbatim or `canonicalName`
  (one entity named two ways can never fire; `MentionAligner.normalizeNeedle` is the existing
  normalizer, `IntentClassifier.java:506-515`).

Tier A is **detection without any new network call** — grounding already ran RESOLVE and recorded
the statuses. `NOT_FOUND` here is exactly the state the single-entity path already clarifies on
(`reference_not_found`, `ChatService.java:825-835`); `AMBIGUOUS` is exactly the state the
single-entity path clarifies on with candidates ∩ discover (`:809-824`). This tier just extends
those two existing verdict→clarify mappings to a NON-focal named reference.

### Tier B — extractor-dropped, resolve-confirmation case (the PC-1/PC-2-class fix)

Candidates (deduped by normalized text, capped):
- EXPLICIT latest-turn mentions with **zero** interpretations (PC-1's "the Calderon account" —
  typed under a non-resolvable slot, so tier A never sees it), and
- `properNounPhrases(latestPrompt)` (`ChatService.java:2535-2541` — maximal capitalized runs;
  matches "Calderon" inside "Calderon's", probed) from the RAW latest user message only,

minus any candidate that normalized-substring-overlaps a bound verbatim/canonicalName or falls
inside a masked span (`prepared.maskDiagnostics()` spans).

Each surviving candidate is RESOLVEd **principal-agnostically, no CHECK** — the same lattice-head
grounding already uses (`runLattice:278-293`, resolve only), against every distinct
`(resolveUrl, resolveType)` among the dominant binding's interpretations (dedupe exactly as
`dedupeKey`, `:221-224`), bounded + deadline'd like `executeTasks` (`:232-275`).

A candidate **confirms detection iff it uniquely resolves to an id ∉ bound ids.**
- `AMBIGUOUS` does NOT confirm in tier B (PC-6: bare `Trust/Family/Account` are ambiguous —
  requiring uniqueness eliminates the generic-noun class wholesale).
- Resolving to an already-bound id does NOT confirm (second guard on entity-named-two-ways —
  probed: "the Whitman account" → REL-00042 = bound id).
- `NOT_FOUND` does not confirm (PC-6: every junk capitalized word — Compare/What/How/Meridian —
  is NOT_FOUND). An unknown second entity the extractor also dropped remains undetectable — that
  is today's behavior, accepted residual, owned by the extractor-hardening workstream.
- `UNAVAILABLE`/exception → candidate ignored (**fail-open-to-today**: this is detection of a
  clarify, not an entitlement gate — a coverage outage must not convert a servable request into a
  clarify; nothing binds from this path, so nothing can fail open in the entitlement sense).

Precedent: this is the exact mechanism of the shipped single-entity backstop
`resolveNamedReferenceBackstop` (`ChatService.java:2492-2541`) — which goes FURTHER than we do
(it auto-BINDS on unique resolve). We only ever ask a question with it.

### The predicate

```
bindings  = EntityBindingSet.derive(prepared.groundedSet())        // exists (D1)
unbound   = referenceGrounding.detectUnboundReferences(...)        // tiers A+B above

fire CLARIFY  ⇔  bindings.allowedCount() ≥ 1                      // at least one entity resolved AND covered
              ∧  !unbound.isEmpty()                                // user named more than we bound
              ∧  !resolved.fallback() ∧ !resolved.selected().isEmpty()   // routing produced a route
```

Every conjunct is computed in gateway code from grounding statuses, resolver output, and
principal-agnostic RESOLVE results. No LLM judges anything. Same-input ⇒ same-output up to the
extractor's own variance, which the predicate is explicitly robust to: **whichever way the
extractor fails (mis-typed mention → tier B via verbatim; wrong-slot mention → tier B; dropped
mention → tier B via raw text; typed-but-unknown → tier A), the request lands in CLARIFY or a
correct two-sided serve — never a silent one-sided serve.** PC-3 (extractor caught it) → no
residual → SERVED two-sided; PC-1 (dropped) → tier B confirms REL-00099 ∉ {REL-00042} → CLARIFY.

## 2. Over-clarify guardrails — why each protected shape cannot fire

| Shape | Why it can't fire |
|---|---|
| Single-entity query (S1, E/F/L classes) | Residual candidates all overlap the bound entity → filtered; stray sentence-initial capitals (`What`, `Show`) are NOT_FOUND (PC-6). Worst case is ≤`max-resolves` wasted RESOLVEs, no behavior change. |
| Two-entity compare, both resolve (S10) | Both bind → no unbound mention, both raw phrases overlap-filtered → `unbound = ∅` → falls through to `expandPerEntity`/`disposeGroups` untouched. |
| One covered + one DENIED (S11/S12) | A DENIED entity **forms a binding** (`EntityBindingSet.java:101-104`) — it is not "unresolved". No residual → existing PARTIAL flow, byte-identical. |
| One entity named two ways | Same-id dedupe at derivation (`:106-108`) + tier-A/B overlap filters + tier-B bound-id check. Triple-guarded, probed. |
| Capability-only multipart (PC-5, D/I) | "performance"/"risk profile" carry no proper-noun token (tier A guard) and are NOT_FOUND on resolve (tier B guard). |
| Off-topic / chitchat (S6) | CHITCHAT never enters `handleFetchData`; and bindings=0 fails `allowedCount() ≥ 1`. ABSTAIN preserved. |
| Under-specified (S7) | bindings=0 → predicate false → existing abstain triage (`ChatService.java:572-608`), including `attemptRequiredEntityClarify`, unchanged. |
| Multi-turn SWITCH/CARRY (S8/S9) | Bindings and tier-A candidates are latest-turn EXPLICIT only; tier-B raw phrases come from the LATEST message only — a carried prior-turn client can neither bind nor appear as a residual. |
| named≥2 / resolved 0 (PC-2 possessive-both) | bindings=0 → predicate false → today's ABSTAIN/triage. Owned by extractor hardening (GAPS fix 1), NOT this feature. |
| Coverage outage during tier B | Candidate ignored → today's behavior. No new deny, no new clarify, nothing binds. |

**Named 3 / bound 2** (multiEntity + residual): predicate fires → CLARIFY before any agent runs.
Deliberate: comparing 2-of-3 silently is the same bug at n=3; a one-turn question beats a wrong
scope. (Distinct from `groups_capped`, which concerns RESOLVED-but-capped entities and keeps its
served-with-note behavior.)

## 3. Hook point + disposition composition

**`handleFetchData`** — single insertion between `ExpandResult expansion = expandPerEntity(...)`
(`ChatService.java:622`) and the `plan.isMultiGroup()` branch (`:624`), so it guards BOTH the
multi-group engine and the flat single-group path (PC-1 lands in a flat `single-selection` group —
the hook must sit before both):

```java
// COMPARE-CLARIFY: the user named more entity references than we bound — deterministic
// clarify (rules 4b/4e), never a silent one-sided serve. Fires only post-routing.
if (bindingSet.allowedCount() >= 1 && residualDetectionEnabled) {
    List<UnboundReference> unbound = referenceGrounding.detectUnboundReferences(
            prepared.groundedSet(), bindingSet, latestPrompt, tenantId, callerToken);
    if (!unbound.isEmpty()) {
        emitRequestOutcome("CLARIFIED");
        streamTextAndComplete(emitter,
                buildCompareClarification(bindingSet, unbound, resolved, principal, tenantId, callerToken, request),
                streamId);
        tracePublisher.publish(TraceEvent.of("request_complete", ...));
        return;
    }
}
```

Placement rationale, in pipeline order:
1. **After the D2 terminal lattice** (`:455-533`) — an all-denied / focal-DENIED verdict keeps
   absolute precedence (deny > clarify; S4 byte-identical; a lone DENIED binding is
   `!multiEntity` → old switch → terminal deny before this code is reached).
2. **After routing + abstain triage** (`:551-608`) — S6/S7 abstains and the
   history-answer/required-entity triage run first, unchanged; the clarify only fires when there
   is a real route (so its copy can be scoped to the routed sub-domain).
3. **Before `disposeGroups` and the flat path** — zero agent invocations, zero synthesis spend,
   no one-sided DATA ever reaches the model.

Composition table (the contract Opus implements against):

| Named | Bound (bindings) | Outcome | Path |
|---|---|---|---|
| 1 | 1 allowed | SERVED — byte-identical | untouched flat/facet path |
| 1 | 1 denied | COVERAGE_DENIED — byte-identical | `!multiEntity` switch `:499-533` |
| 2 | 2 allowed | SERVED two-sided | D3 expansion (S10) |
| 2 | 1 allowed + 1 denied | PARTIAL | D2/D3 (S11/S12) — **unchanged; detection can't fire (denied = bound)** |
| 2 | 1 allowed + 1 UNRESOLVED | **CLARIFY (new)** | this spec |
| 3 | 2 allowed + 1 unresolved | **CLARIFY (new)** | this spec |
| 2 | 0 | ABSTAIN / triage clarify — unchanged | `:572-608` |
| any | 0 | unchanged | predicate requires `allowedCount ≥ 1` |

**`decideRoute`** (`:1303-1384`) mirrors it: run the same detection after step 4 (`:1337-1339`),
surface `overallDisposition = "CLARIFY"` (new value, computed before the group loop's
served/denied composition in `overallDisposition:1406-1421`) plus a count-only field
(`unresolvedReferenceCount`) on `RouteDecision` — the projection stays verbatim-free like
`projectGrounded` (`:1423-1437`). This is what the smoke rows assert. Cost note: `/debug/route`
gains ≤3 parallel RESOLVE calls on affected shapes only (innocent single-entity queries filter
to zero candidates).

Config (CLAUDE.md §5 — no Java constants):

```yaml
conduit:
  grounding:
    residual-detection:
      enabled: ${CONDUIT_GROUNDING_RESIDUAL_DETECTION:true}     # kill-switch
      max-resolves: ${CONDUIT_GROUNDING_RESIDUAL_MAX_RESOLVES:3}
```

## 4. Copy source (World-B)

The wording is a **manifest message key**, resolved through the existing sub-domain-scoped
plumbing `msg(key, fallback, subDomainId)` → `manifestStore.message(key, subDomainId)`
(`ChatService.java:2155-2164`) — the same channel as `reference_not_found` (which already does
placeholder substitution, `:828-831` precedent) and `needs_more_detail`.

- **New key `compare_partial_resolution`** in each resource-scoped sub-domain's `messages` map
  (e.g. `registry/domains/wealth-management/private-banking.json`), with `{resolved}` and
  `{unresolved}` placeholders. Wealth copy example (lives in the MANIFEST, never in Java):
  *"I found {resolved}. I couldn't confidently identify the other client you mentioned
  ({unresolved}). Please give the client name or relationship ID."*
- **No schema change**: `sub-domain-manifest.schema.json` declares `messages` as
  `additionalProperties: {type: string, minLength: 1}` (verified) — both copies
  (`registry/`, `gateway/src/main/resources/`) stay untouched. Re-run registry ingestion after
  editing the manifest (CLAUDE.md §6.5).
- **Substitution values**: `{resolved}` = the ALLOWED bindings' `canonicalName`s (resolver data —
  discloses exactly what a SERVED answer would); `{unresolved}` = the user's **own verbatim**,
  never tier-B's resolved canonical name.
- **"Did you mean" options — the no-leak bound**: suggestions are rendered ONLY from
  `discover(principal) ∩ {tier-B resolved id | tier-A ambiguous candidates}` via the existing
  `buildDeterministicClarification` list renderer (`:2245-2255`) — identical to the ambiguous
  branch's candidates∩discover intersection (`:809-818`). An out-of-book resolution (extractor
  drops "the Okafor account" for rm_jane) yields the GENERIC ask with the user's verbatim — the
  gateway never confirms an out-of-book entity exists. Information disclosed ≡ what asking about
  that entity alone would disclose.
- **Java fallback (domain-neutral, precedented like every `msg()` fallback)**: *"I found
  {resolved}. I couldn't identify the other reference you mentioned ({unresolved}). Please
  provide the name or identifier."* — no "client", no domain noun.
- Scoping `subDomainId` = the routed primary candidate's sub-domain (available post-routing),
  falling back to the first ALLOWED binding interpretation's `subDomainId`.
- Optionally reuse `ClarificationComposer` (`domain/clarify/ClarificationComposer.java`) for the
  `clarify_style: composed` domains — same validate-or-fall-back-to-template contract as
  `buildClarificationQuestion:2221-2235`. Not required for v1; the template is the default style.

`scripts/world-b-check.sh` stays CRITICAL 0: every new symbol is a manifest key, config value,
resolver-produced id/name, or user verbatim; `PROPER_NOUN` is pre-existing language-generic code.

## 5. Tests

**Unit — `ReferenceGroundingService.detectUnboundReferences` (mock CoverageClient, pure):**
- tier A: latest-turn EXPLICIT mention, non-empty interps all NOT_FOUND, verbatim "Tesla" → detects (no resolve call made).
- tier A: "performance" (no proper-noun token) → never a candidate.
- tier A: AMBIGUOUS interps + capitalized verbatim → detects with candidates.
- tier B: zero-interp mention "the Calderon account" → resolve-confirmed → detects.
- tier B: raw phrase from possessive residual → detects; AMBIGUOUS resolve ("Trust") → does NOT; resolve to bound id → does NOT; NOT_FOUND ("Meridian") → does NOT.
- overlap filters (verbatim ⊂ bound canonicalName; phrase inside masked span) → excluded.
- cap honored; CoverageUnavailable → empty result (fail-open-to-today).
- prior-turn / ANAPHORA mention → never a candidate.

**`ChatService` full-context (extend `RedisContainerTest` — mandatory per CLAUDE.md §7):**
- named2/resolved1-covered → outcome CLARIFIED, question contains bound canonicalName + user
  verbatim, ZERO agent invocations, zero synthesis calls.
- named2/resolved2 one-denied → PARTIAL unchanged (detection silent).
- single-entity S1/S4 shapes → byte-identical (hash the streamed text).
- memo guard + expansion tests from MULTI-ENTITY-COMPARE §5 re-run untouched.
- `decideRoute` surfaces `overallDisposition=="CLARIFY"` + count for the same fixture.

**Smoke (`scripts/smoke-route.sh`), new rows — written flake-proof against extractor variance
(PC-3 proved the same phrasing can legitimately two-side-serve):**
- **S13 incomplete-resolution (alias-2nd, rm_jane)**: "compare the concentration of the Whitman
  Family Office and the Calderon account" → `disp=='CLARIFY' or (disp=='SERVED' and
  entity-facet groups >= 2)` — the assertion pins the INVARIANT (never one-sided), not the
  extractor's mood. A one-sided `SERVED` with a lone `single-selection` group FAILS.
- **S14 out-of-book dropped (rm_jane)**: "compare the concentration of the Whitman Family Office
  and the Okafor account" → `disp in ('CLARIFY','PARTIAL')` and never plain one-sided SERVED.
- S10/S11/S12 unchanged; S1–S9 unchanged; S6 stays `ABSTAIN` (assert unchanged row).

**E2E (one Playwright row):** rm_jane sends S13's query in Conduit Chat; assert the reply either
(a) contains both clients' figures, or (b) is a question naming Whitman and quoting "the Calderon
account" — and NEVER a Whitman-only data answer with no reference to the second ask.

**Gates:** `scripts/world-b-check.sh` before/after (CRITICAL 0 → 0), `scripts/verify.sh`.

## 6. Implementation order

1. `detectUnboundReferences` + `UnboundReference` + unit tests (pure; nothing consumes it — inert).
2. Manifest `compare_partial_resolution` keys + re-ingest (copy exists before code streams it).
3. `handleFetchData` hook + `buildCompareClarification` + config + full-context tests.
4. `decideRoute` mirror + `RouteDecision` field + S13/S14 + rerun S1–S12.

## 7. Top over-clarify risk (the one to watch)

**Lenient substring aliasing in the coverage resolver makes a stray capitalized token uniquely
resolve to a client nobody named.** Measured live: bare `Office` → REL-00042 (`data.py:135-138`,
alias-substring-either-direction). If a bound entity is Calderon and an innocent capitalized
"Office…" run appears elsewhere in the message, tier B confirms REL-00042 and fires a needless
clarify. Bounded by: unique-resolution requirement (generic nouns measured AMBIGUOUS → silent),
bound-id/overlap filters, and the failure mode being a polite QUESTION (one extra turn — never
wrong data, never a leak: the suggestion renders only through discover∩). Mitigation lever if it
shows up in eval traffic: resolver-side alias hygiene (coverage service, not gateway) or dropping
tier-B's raw-phrase source while keeping the zero-interp-mention source — both config/data-side,
no invariant change. Secondary risk: named3/bound2 now clarifies where some users may have wanted
the 2-way answer — deliberate, documented in §2.

## 8. Explicit non-goals

- No auto-bind of tier-B resolutions (the backstop precedent auto-binds, we deliberately do not:
  capitalization+substring evidence is weaker than extraction; a question cannot misattribute data).
- No extractor prompt change here (GAPS fix 1 is its own workstream; this feature makes its
  failures VISIBLE instead of silent, and S13 stays green through both).
- No reranker/threshold/mask change; no new disposition for bindings=0 shapes.
