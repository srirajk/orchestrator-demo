# Multi-turn client-switch misroute — validated diagnosis + surgical fix

**Status:** validated by Fable (independent, adversarial, read-only) — ready to implement.
**Bug:** rm_jane, after Whitman turns, says "show me the Calderon Trust holdings" → routes to
`meridian.wealth.concentration` (previous turn's topic) instead of `meridian.wealth.holdings`.
Observed `maskedRoutingText` (via `POST /debug/route`):

```
give me a summary of the Whitman Family Office holdings
whats the concentration risk there
show me the the subject
```

---

## 1. Verdicts on the three hypothesis claims

| # | Claim (Opus) | Verdict |
|---|---|---|
| 1 | The entity mask over-captures: it replaced "Calderon Trust holdings" instead of just "Calderon Trust", deleting the capability word | **CONFIRMED in effect — but the locus is upstream.** The masker did not extend anything; it faithfully masked the LLM's *greedy verbatim extraction* "Calderon Trust holdings". Mechanism is (a), not (b)/(c)/(d) — proof in §2. |
| 2 | The near-empty → widen fallback is what pulls in the history; the blend is a consequence of the over-mask, not an independent bug | **CONFIRMED.** Deterministic trace in §3. With "holdings" preserved, the latest message routes alone (base window = 1, `HAS_ACTION`, no widen). |
| 3 | Fix = span boundary + a topic-switch guard so a new-client turn never inherits the prior capability | **HALF CONFIRMED, HALF REFUTED.** The span/needle tightening is right (§4). The topic-switch guard *as stated* would regress the designed, desirable facet-carry: "what about the Calderon Trust?" after a concentration turn **must** widen and inherit concentration for the new client. Do **not** suppress widening on entity switch (§4.4). |

---

## 2. The exact over-capture mechanism — LLM greedy extraction, preserved end-to-end

The masked output "show me **the the subject**" decodes as: user's own article `the` + the mask
token `the subject` (`conduit.routing.entity-mask-token`, default `the subject` —
`gateway/src/main/resources/application.yml:172`). So one merged span covered exactly
`Calderon Trust holdings` (3 words, starting at "Calderon"). Where each candidate mechanism stands:

**(a) LLM emits mention/reference text "Calderon Trust holdings" — THE CAUSE.**
Every mask needle originates from LLM extraction; there is no other source of a multi-word needle:

- `RoutePreparer.prepare` builds `resolvedNeedles` from `gm.mention().verbatimText()` plus resolved
  `canonicalId`s only — `gateway/src/main/java/ai/conduit/gateway/domain/chat/RoutePreparer.java:108-116`.
- `verbatimText` comes from `IntentClassifier.buildMentionSet`: (1) the focal scalar from
  `deriveFocalReference`, (2) the LLM's `mentions[].text` —
  `gateway/src/main/java/ai/conduit/gateway/domain/intent/IntentClassifier.java:435-468`.
- `deriveFocalReference` step 2 returns the LLM's value **verbatim** when it shares a word with the
  latest message (`IntentClassifier.java:595-597` — `sharesWord("…Calderon…", llmValue)` → true).
  Steps 1/3/5 return typed/transcript IDs (no trailing words). So the greedy phrase is LLM output.
- The prompt permits it: `gateway/src/main/resources/prompts/intent-classifier.system.md:27-30,42`
  demands "verbatim, exactly as the user wrote it" — "Calderon Trust holdings" IS a verbatim
  substring of the user's text; nothing forbids appending the facet noun to the reference. The
  compiled scalar rule (`IntentClassifier.java:134-138`) has the same gap. Model is
  `glm-4.5-flash` at temperature 0 (`application.yml:172ff`, `IntentClassifier.java:172,175`) — a
  small model doing greedy noun-phrase capture is entirely plausible and, once emitted, every
  downstream stage deterministically preserves it.

**(b) MentionAligner extends the span — REFUTED.** `MentionAligner.align` is an exact normalized
substring match, anchored on token boundaries; it can only return a span whose normalized content
equals the needle, never more —
`gateway/src/main/java/ai/conduit/gateway/synthesis/input/MentionAligner.java:50-72` (match loop),
`:79-83` (`onTokenBoundary`). `makeMention` (`IntentClassifier.java:479-484`) aligned the 3-word
LLM phrase in the latest message and correctly produced the 3-word span.

**(c) Span merge — REFUTED.** `mergeSpans` merges only overlapping/nested/touching spans
(`s.start() <= curEnd`, `RoutePreparer.java:247`). A separate span over "holdings" would have to
exist first; no needle produces one, and "Calderon Trust"→"holdings" have a 1-char gap anyway.

**(d) Alias needle with a trailing word — REFUTED (but the resolver is an accomplice).** Needles
are only verbatims + canonical IDs (`RoutePreparer.java:110-115`); aliases never become needles.
However, the coverage resolver's alias match is substring-in-either-direction —
`mock-agents/wealth-coverage/data.py:136-140`: `alias in ref_lower or ref_lower in alias` — so
`resolve("Calderon Trust holdings")` matches alias `"calderon trust"` (`data.py:24`), resolves
uniquely to `REL-00099`, and the CHECK allows (rm_jane's book). That's why the greedy phrase became
a *resolved* mention and got masked at all. The resolver tolerating the greedy tail is correct
behavior for RESOLVE; the information it returns (`canonical_name: "Calderon Trust"`) is exactly
what the fix uses.

**End-to-end failure trace:** LLM emits "Calderon Trust holdings" → focal scalar keeps it
(`IntentClassifier.java:595-597`) → aligner spans all 3 words (`:479-484`) → resolver resolves it
via alias substring (`data.py:138`) → RESOLVED_ALLOWED → needle + recorded span masked
(`RoutePreparer.java:110-115, 213-221`) → `applyMask` → `"show me " + "the" + " " + "the subject"`.

## 2a. Secondary observation — the Whitman leak in the widened window

"Whitman Family Office" appears **unmasked** in the widened routing text. Masking only covers this
turn's grounded mentions; the extractor evidently did not emit a Whitman mention this turn (the
prompt demands EVERY reference, `intent-classifier.system.md:40`) or it failed to resolve.
Consequence: `leaks()` (`RoutePreparer.java:159, 298-306`) → `maskingComplete=false` → relaxation
forfeited (diagnostics-only; routing text unchanged). Not the root cause — but it means **widen
safety is load-bearing on extractor mention recall**. Flagged in §6; a `leaked`/`tightened` count in
`MaskDiagnostics` (PreparedRoute.java:55-62) would make this visible in the glass box.

---

## 3. Claim 2 — the widen fallback, confirmed deterministically

With the over-masked latest residual `"show me the the subject"`:

1. Tokens `{show, me, the, subject}`: `show/me/the` are config stopwords
   (`application.yml:176-180`), `the/subject` are mask-token tokens → content = 0 <
   `min-residual-content-tokens` = 1 (`application.yml:182`) → `isNearEmpty` true
   (`RoutePreparer.java:329-339`).
2. `carryContext=true` (FETCH_DATA — `ChatService.java:1240`, and `:347` for the live path) →
   **widen** to `routing-context-turns` = 4 (`application.yml:209`) →
   `RoutePreparer.java:142-148` re-joins all 3 user turns, masked → exactly the observed text.
3. `resolver.resolveContextual(prepared.maskedRoutingText(), …)` (`ChatService.java:1259-1261`,
   same at `:485-486` on the fetch path) sees "holdings … concentration risk … the subject";
   the prior turn's "concentration risk" is the most distinctive capability text → concentration.

**Counterfactual (fix in place):** needle = "Calderon Trust" → masked latest =
`"show me the the subject holdings"` → content token `holdings` ≥ 1 → `HAS_ACTION`, **no widen**;
base window is already 1 (`entityKnownForWindow` true via grounded memo / extracted reference,
`latestHasId` false → `RoutePreparer.java:125-126`). Routing text is the masked latest alone —
the same shape as smoke S1 ("what are the holdings for **the subject**" →
`meridian.wealth.holdings`, `scripts/smoke-route.sh` S1). Claim 2's "would route correctly on its
own" holds.

**The widen policy itself is correct and must not change.** It exists for the genuine anaphoric
carry ("whats the concentration risk there" — content tokens `concentration, risk` → routes on the
latest without widening; and bare "what about X?" turns — genuinely empty → widen inherits the
facet). Both are desired.

---

## 4. The surgical fix

Principle: the gateway cannot know "holdings" is a capability word (World-B — no domain word lists
in gateway Java). But it holds deterministic, domain-data-driven evidence of the entity's true
name: the resolver's `canonical_name`, already deserialized into
`CoverageResolveResult.canonicalName` (`gateway/src/main/java/ai/conduit/gateway/domain/coverage/CoverageResolveResult.java:11`)
and then **dropped** in `runLattice`. Thread it through, and mask only the canonical name when it
provably sits inside the extracted phrase — the extra tokens are the user's action text.

### 4.1 Thread `canonicalName` through the grounding lattice
`gateway/src/main/java/ai/conduit/gateway/domain/coverage/ReferenceGroundingService.java`

- `LatticeOutcome` (line 367-373): add `String canonicalName`; factories become
  `allowed(id, name)` / `denied(id, name, reason)`; others pass null.
- `runLattice` (lines 285-286): pass `rr.canonicalName()` for both allowed and denied (DENIED
  mentions are masked too — RoutePreparer masks `isResolved()` = ALLOWED ∪ DENIED, so tightening
  must apply to both, or S3/S4-class denials keep greedy masks).
- `GroundedInterpretation` (record, line 406-419): add `String canonicalName` after `canonicalId`;
  `toInterpretation` (lines 301-309) carries `outcome.canonicalName()` for
  `RESOLVED_ALLOWED`/`DENIED`, null otherwise.
- Mechanical: test constructors of `GroundedInterpretation` gain one arg
  (`RoutePreparerTest.java:75-79`, `ReferenceGroundingMentionsTest`, etc.).

### 4.2 Needle tightening in RoutePreparer (the actual fix)
`gateway/src/main/java/ai/conduit/gateway/domain/chat/RoutePreparer.java`

**(i) Needle build, lines 108-116** — per resolved mention, compute the *effective mask needle*:

```java
Set<String> resolvedNeedles = new LinkedHashSet<>();
Set<String> tightenedVerbatims = new LinkedHashSet<>();   // greedy verbatims replaced by canonical
for (GroundedMention gm : groundedSet.mentions()) {
    if (!isResolved(gm)) continue;
    String verbatim = gm.mention().verbatimText();
    String canonical = soleResolvedCanonicalName(gm);     // exactly ONE distinct resolved canonicalName, else null
    boolean tightened = verbatim != null && canonical != null
            && !MentionAligner.normalizeNeedle(verbatim).equals(MentionAligner.normalizeNeedle(canonical))
            && MentionAligner.align(verbatim, canonical) != null;   // canonical is a proper sub-phrase
    if (tightened) {
        resolvedNeedles.add(canonical);
        tightenedVerbatims.add(verbatim);
    } else if (verbatim != null) {
        resolvedNeedles.add(verbatim);
    }
    for (GroundedInterpretation gi : gm.interpretations()) {
        if (gi.isResolved() && gi.canonicalId() != null) resolvedNeedles.add(gi.canonicalId());
    }
}
```

Rules baked in:
- Tighten **only** when the canonical name aligns as a *proper sub-needle* of the verbatim
  (reusing `MentionAligner.align` — exact, token-boundary-anchored, so this is provable, not
  heuristic). Alignment miss or equality → current behavior, byte-identical.
- If a mention has **multiple distinct** resolved canonical names (cross-domain homonym), skip
  tightening (deterministic; avoids picking a name arbitrarily).
- Do **not** add `canonical` as a needle for non-tightened mentions: a pathological canonical name
  that is itself a generic word must not grow the masked surface beyond today's.

**(ii) `collectMaskSpans`, lines 213-218** — skip the mention's *recorded greedy span* when its
verbatim was tightened (the `allOccurrences(original, canonical)` pass at lines 219-221 masks
exactly the name region inside it, all occurrences):

```java
if (gm.mention().messageIndex() == idx && gm.mention().span() != null
        && !tightenedVerbatims.contains(gm.mention().verbatimText())) {
    spans.add(gm.mention().span());
}
```

Downstream is automatically consistent: `leaks()` (line 298) now checks the tightened needle set
(the masked text no longer contains "calderon trust", so no false leak on the residual
"holdings"); `alignmentMissCount` (line 309) keys on `span() == null`, unaffected;
`mergeSpans`/`applyMask` untouched; the widened path (`maskedJoin` at 144) reuses the same needles.

**(iii) No change** to `min-residual-content-tokens` (=1) or the widen policy (142-148). Raising
the threshold widens MORE turns (wrong direction); the policy itself is correct (§3, §4.4).

### 4.3 Prompt hardening (belt, not load-bearing)
- `gateway/src/main/resources/prompts/intent-classifier.system.md:42` (mentions `text` rule) and
  the compiled scalar rule in `IntentClassifier.java:134-138`: append —
  *"The reference text is ONLY the entity's name or identifier — never include surrounding request
  words or the words for WHAT data is being asked about it."*
  Generic English, zero domain vocabulary — World-B clean. This reduces greedy capture at the
  source; §4.2 is what guarantees correctness when the model ignores it.

### 4.4 The "topic-switch guard" — deliberately NOT a widen suppressor

Refuting claim 3's guard as stated: "latest names a new resolved client ⇒ route on the latest even
if masking left little" would break the **designed facet-carry on switch** — after
"whats the concentration risk there" (Whitman), the turn "what about the Calderon Trust?" masks to
a genuinely near-empty residual and **should** widen and inherit `concentration` for the NEW
client. The gateway is stateless (`ADR-STATELESS-GATEWAY.md`); the transcript is the only facet
memory, and widening is precisely how a facet survives an entity switch. With §4.2 in place the
dichotomy is already correct by construction:

- switch **with** a capability word → residual keeps it → routes on the latest alone (no widen);
- switch **without** one → widen → inherits the prior facet with the new (masked) entity —
  entity-blind by masking, so the old client cannot re-assert itself.

The one residual bad case — over-capture that tightening cannot prove (user typed an alias-tail
phrasing, e.g. "show me the Calderon account holdings"; canonical "Calderon Trust" does not align
inside it) — gets an **optional second-stage trailing-token trim** (ship only if evals show the
phrasing matters): drop trailing verbatim tokens absent from the canonical name's token set,
keeping the contiguous head with ≥1 canonical-token overlap ("calderon account holdings" → mask
"Calderon"; residual "account holdings"). Cost: may leak a generic alias tail word ("account")
into routing text — entity-identifying power ~nil, and capability-first routing prefers keeping
user action text. Not required for this bug.

### 4.5 Observability (1-line, recommended)
Add `tightenedSpans` (and optionally a `leaked` boolean) to `PreparedRoute.MaskDiagnostics`
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/PreparedRoute.java:55-62`) so the glass-box
panel and `/debug/route` show the fix firing and surface §2a extractor-recall leaks.

---

## 5. Regression tests

### 5.1 Unit — `RoutePreparerTest` (no LLM; fixtures at lines 59-95)
1. `greedyExtraction_masksOnlyCanonicalName_keepsFacetWord` — mention verbatim
   `"Calderon Trust holdings"` aligned in `"show me the Calderon Trust holdings"`, interpretation
   RESOLVED_ALLOWED (`REL-00099`, canonicalName `"Calderon Trust"`) → masked text **contains**
   `holdings`, **not** `Calderon`; `residualClass == HAS_ACTION`; `maskMode == "masked-base"`.
2. `greedySwitch_multiTurn_routesOnLatestNotHistory` — window
   `["give me a summary of the Whitman Family Office holdings", "whats the concentration risk there",
   "show me the Calderon Trust holdings"]` with the greedy Calderon mention → chosen
   `maskedRoutingText` is the masked LATEST only (no `concentration` in it).
3. `bareSwitch_noFacet_stillWidens` (over-correction sentinel) — latest
   `"what about the Calderon Trust?"`, verbatim == canonical → `maskMode == "masked-widened"`,
   `residualClass == WIDENED` (protects the genuine facet-carry).
4. Existing pins must stay green untouched: `:99` (capability preserved + relaxation — the
   bug-261 capability-first contract), `:117` (name+id merge), `:132` (genuine widen), `:151`
   (empty→clarify), `:165` (alignment-miss forfeits relaxation).
5. `ReferenceGroundingMentionsTest`: canonicalName present on RESOLVED_ALLOWED **and** DENIED
   interpretations, null on NOT_FOUND/AMBIGUOUS/UNAVAILABLE.

### 5.2 Live trace-truth — `scripts/smoke-route.sh`
`assert_route` builds a single-message body (lines 23-30); add an `assert_route_msgs` variant
taking a full JSON `messages` array (assistant turns should carry the `"<Name> (<REL-id>)"` shape,
since `deriveFocalReference`/`lastFocalSingleId` read ids from the transcript). Then:

- **S8 SWITCH** (this bug): messages =
  `[u:"give me a summary of the Whitman Family Office holdings",
    a:"Whitman Family Office (REL-00042) holdings summary …",
    u:"whats the concentration risk there",
    a:"Concentration for Whitman Family Office (REL-00042) …",
    u:"show me the Calderon Trust holdings"]`
  → `disp=='SERVED' and primary=='meridian.wealth.holdings'`
  (hard floor if holdings-vs-summary flaps: `primary!='meridian.wealth.concentration'`).
- **S9 CARRY** (guard sentinel): first three of those messages, latest =
  `"whats the concentration risk there"` → `disp=='SERVED' and
  primary=='meridian.wealth.concentration'` — pins that the fix never breaks the anaphoric carry.
- S1–S7 unchanged and must stay green — S1 pins single-turn masking, S3 pins bug-261
  capability-first STRUCTURAL_DENIED, S4 pins DENIED masking/deny copy.

### 5.3 Definition of done
`scripts/world-b-check.sh` CRITICAL count unchanged at 0 (no new gateway literals — canonical
names flow from coverage data at runtime), `mvn test` (Testcontainers), `scripts/smoke-route.sh`
9/9 against the live stack.

---

## 6. Over-correction risks (flagged)

1. **Alias-tail phrasings stay greedy** ("Calderon account holdings"): tightening requires
   canonical ⊂ verbatim alignment; on a miss it falls back to today's full-verbatim mask, so this
   bug persists for that phrasing until the optional trailing-trim (§4.4) ships. Visible via the
   `tightenedSpans`/near-empty diagnostics.
2. **Multi-interpretation mentions**: tighten only on a *sole* distinct resolved canonical name;
   ambiguity keeps the greedy mask (safe, byte-identical to today).
3. **DENIED-path parity**: canonicalName must be threaded for DENIED too (§4.1), or denial-path
   masking silently diverges from allowed-path masking.
4. **Genuine anaphora**: untouched by construction — a turn with no explicit mention has no
   verbatim to tighten; CLARIFY turns (`carryContext=false`) still never widen
   (`RoutePreparer.java:123-124,142`).
5. **Extractor mention recall is still load-bearing for widen safety** (§2a — the Whitman leak):
   a dropped history mention leaks an unmasked prior entity name into the widened routing text.
   Out of scope here (needs either extractor eval pressure or masking known-resolved transcript
   names); do not let this block the fix, but track it.
