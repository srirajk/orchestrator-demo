# Multi-Entity Compare — Design Spec

One query naming two (or more) resolvable clients ("compare Whitman's concentration to
Calderon's") must route once, entitlement-CHECK **each** client independently, fan out with each
client's own bound id, and synthesize a side-by-side answer. Today only the FOCAL mention is
grounded into disposition and binding (`ReferenceGroundingService.java:454-459`); the others carry
no weight. This spec is the mechanism Opus implements from. Read with
`docs/specs/ROUTING-QUALITY-GAPS.md` (gap 1, fix direction 2 — this is that LOGIC fix, fully
specified). All line numbers are against `conduit-platform` @ 2f7cfa6.

**Verified live** (rm_jane, `POST /debug/route`, stack healthy, 2026-07-11):

| Probe | Query | Result today |
|---|---|---|
| MC-1 | compare the concentration of **Whitman Family Office and Calderon Trust** | masked "…of the subject and the subject", 0.467, **SERVED** — 2 mentions × 3 sub-domain interpretations each, ALL `RESOLVED_ALLOWED`, but **one** requested group (`single-selection`, DAG goal `concentration_review`) → executes for the focal client only |
| MC-2 | compare … of **Whitman** Family Office and **the Okafor account** | extractor emitted 4 mentions but only Whitman's carry interpretations; "the Okafor account" ungrounded, **left unmasked**, disposition **SERVED** — silently one-sided |
| MC-3 | compare … of **the Okafor account** and **the Whitman Family Office** | BOTH ground: Okafor `DENIED` (focal, first-recorded), Whitman `RESOLVED_ALLOWED` (non-focal) → **`COVERAGE_DENIED` for the whole request** — the covered client is denied too |
| MC-4 | compare the **performance** of Whitman … with the **risk profile** of Calderon | 2 mentions, 0 grounded, 0 masked spans, 0.284 → **ABSTAIN** (gap-1 extractor fragility; upstream dependency, not fixed here) |

MC-2 vs MC-3 is an **order-dependence bug live today**: same two clients, opposite outcomes
depending on which name comes first. MC-3 is the sharper wrong: a focal `DENIED` verdict is
terminal for the request (`ChatService.java:433-448`), so naming one out-of-book client kills the
in-book half. MC-1 proves the target substrate already exists: masking, routing, and per-mention
RESOLVE+CHECK all already work for two entities — only **disposition and binding** are focal-only.

---

## 1. Full path map (file:line, all verified by reading)

**Extraction.** `IntentClassifier.classify` → `EntityBag` (`synthesis/input/EntityBag.java:23-30`)
carrying `MentionSet` (`MentionSet.java:22`). `buildMentionSet`
(`IntentClassifier.java:437-469`) records the focal scalar first, then every LLM `mentions[]`
entry; `makeMention` (`:478+`) aligns spans (EXPLICIT in latest turn, ANAPHORA in earlier turns).
It DOES emit ≥2 mentions for coordinated names (MC-1: 4 mentions) but drops/mis-types possessive
and "the X account" forms unpredictably (MC-2, MC-4) — the gap-1 extractor-prompt hardening is a
**hard dependency** for order-independent compare.

**The scalar bottleneck.** `EntityBag.references` is `Map<String,String>` — ONE value per
`extract_as` key (`EntityBag.java:24`, view built by `MentionSet.compatScalar` `:69-97`, focal
wins ties). Everything that binds an id into an agent input flows through this map
(`ChatService.injectCoverage:1768-1780`, flat path `:862-872`). Two clients of the same entity
type therefore cannot both bind **in one bag** — but each requested group already gets its OWN
bag copy via `injectCoverage`. That per-group bag override is the binding mechanism this design
reuses; no new binding machinery is invented.

**Grounding.** `ReferenceGroundingService.groundMentions` (`:164-219`) grounds EVERY mention ×
every manifest interpretation with budgets (`GroundingBudget`, `conduit.grounding.*`,
max-mentions 8), dedupe key `(resolve_url, resolve_type, reference)` (`:221-224`), bounded VT
fan-out, stage deadline → `UNAVAILABLE` fail-closed (`:232-275`). Each
`GroundedInterpretation` (`:416-436`) carries `canonicalId`, `canonicalName` (recent fix),
`entityType`, `subDomainId`, per-interpretation `GroundStatus`, `denialReason`. So **per-entity
RESOLVE+CHECK results for ALL mentions already exist after grounding** — the design consumes
them; no additional coverage calls on the happy path.

**The focal collapse (the thing to generalize).** `focalIndex` (`:209`, `:332-347`) marks one
mention focal (EXPLICIT > ANAPHORA, later turn wins, first-recorded wins ties);
`toFocalVerdict` (`:350-362`) maps its LEADING interpretation to the single `GroundingResult`;
`GroundedReferenceSet` (`:454-460`): *"the interim disposition drives from the FOCAL mention
only … Non-focal mentions carry no disposition weight."*

**Terminal lattice.** `ChatService.handleFetchData:383`; prepared route `:425-427`; focal
verdict + scalar fallback `:428-431`; the switch `:433-466` — `DENIED` and `UNAVAILABLE` are
**request-terminal** (MC-3's bug), `RESOLVED_ALLOWED` memoizes `groundedMemo`.

**Routing.** `RoutePreparer.prepare` (`RoutePreparer.java:98-187`): masks ALL resolved mentions
(needles `:115-134`), relaxation = focal allowed AND masking complete (`:174-180`). Routing text
is the masked join; **binding never reads it** — MC-1 confirms two identical mask tokens route
fine at 0.467.

**Plan.** `buildRequestedPlan` (`ChatService.java:1377-1394`): one group per reranker facet
(`rerank-facet`) else one flat group (`single-selection`); `groupOf:1397-1405`;
above-floor-only `findManifest:1425-1430`. Groups are per-**capability**, never per-entity.
`RequestedPlan.RequestedGroup` (`RequestedPlan.java:56-76`).

**Per-group disposition.** `disposeGroups` (`:1486-1629`): per group — structural gates
(`:1503-1519`), coverage via `coverageForGroup` (`:1678-1739`, scans
`groundedSet.allInterpretations()` and takes the FIRST sub-domain/entity-type match; focal memo
fallback via `consumeGroundingMemo:2110-2118`), `injectCoverage` (`:1768-1780`, group-local bag,
strips unchecked refs `:1771-1776`), bind+execute (`:1543-1573`), merge; all-denied copy
`:1584-1601`; withheld domains → synthesis `:1603-1622`. `GroupCoverageOutcome`
PROCEED/DENIED/CLARIFY/WITHHOLD (`:1632`); UNAVAILABLE interpretation fails closed
(`:1700-1711`).

**Reranker.** `LlmRoutingRerankerClient.rerank` (`:64-162`): pick / abstain /
`multiple([ids])` (`:144-160`) — capability facets only. **No change to this contract.**

**Fan-out + synthesis.** `PlanNode(nodeId, agent, input, …)`
(`orchestration/model/PlanNode.java` — `nodeId` is already a distinct field; today it equals
`agentId`, `ChatService.java:921-925`, `:1556-1559`). `NodeResult(nodeId, agentId, …)`.
`AnswerSynthesizer.buildUserContent` (`AnswerSynthesizer.java:335-382`) labels DATA blocks by
`agentId` ONLY (`:340`) — two calls to the same agent for two clients would be
indistinguishable; WITHHELD block `:359-367`; grounded figures keyed `sourceAgent = agentId`
(`GroundedFigureRenderer.java:40-49`). Cross-entity aggregation is already prohibited in the
prompt (`prompts/answer-synthesizer.system.md:6` — never add/sum/average across DATA sections).

**Probe engine.** `decideRoute` (`:1232-1301`) runs the identical pre-routing pipeline;
`overallDisposition:1304-1315` maps focal `DENIED` → `COVERAGE_DENIED` first — must gain the
same multi-entity gate. `scripts/smoke-route.sh` asserts on it (S1-S9).

---

## 2. Design

### D1 — EntityBindingSet: derive per-entity bindings from the grounded set (new, pure)

New value derived deterministically from `PreparedRoute.groundedSet()` (helper on
`GroundedReferenceSet` or a small `EntityBindingSet` class next to it — package
`domain.coverage`, respecting the existing feature-package layout):

```
EntityBinding {
  String canonicalId;            // resolver-produced, never LLM (rule 4b)
  String userVerbatim;           // the mention's own words (for withheld copy attribution)
  String entityKey;              // manifest EntityType.key()
  List<GroundedInterpretation> interpretations;  // this id's per-sub-domain statuses
  int mentionOrder;              // recorded order = user order (deterministic)
}
```

Derivation rules (all deterministic, no LLM):
1. Consider only mentions with `source() == EXPLICIT` **and** `messageIndex() == latest user
   turn`. Anaphora/carried mentions NEVER create a binding — this is what keeps S8 (SWITCH) and
   S9 (CARRY) byte-identical: a carried prior-turn client can be the focal subject but can never
   multiply the fan-out. (Verified: S8's grounded set today has only the Calderon mention
   grounded; the rule makes that robust rather than accidental.)
2. Group interpretations by `canonicalId` within the same `entityKey`; statuses
   `RESOLVED_ALLOWED` / `DENIED` / `UNAVAILABLE` form a binding (they have a resolution or a
   fail-closed obligation); `NOT_FOUND` / `AMBIGUOUS` never do (existing demote/clarify
   semantics unchanged). Two mentions resolving to the SAME id ("Whitman" twice) dedupe to one
   binding — grounding already dedupes the coverage task (`:221-224`).
3. Per-binding verdict is **per sub-domain interpretation**, not flattened — group matching
   stays sub-domain-scoped exactly as `coverageForGroup` is today.
4. `multiEntity = bindings-with-same-entityKey count ≥ 2`. Size ≤ 1 → the entire feature is
   inert; every existing path byte-identical.

### D2 — Generalize the terminal lattice (the MC-3 fix)

`handleFetchData` `:428-466`: compute `bindings` once (from `prepared.groundedSet()`, no new
coverage calls). Gate the switch:

- `!multiEntity` → **unchanged, byte-identical** (single-entity requests, S1-S9, the
  overwhelming common case).
- `multiEntity`:
  - **ALL bindings DENIED** (or DENIED+UNAVAILABLE, zero ALLOWED) → terminal
    `COVERAGE_DENIED`: emit one `gate`/`check_denied` trace pair per binding (as `:434-448`
    does for one), stream `mapDenialReason(...)` (`:1883`) once. Matches S4 semantics —
    comparing two clients you cover neither of is a coverage deny, same copy.
  - **ALL bindings UNAVAILABLE** → existing UNAVAILABLE terminal (`:449-460`), fail closed.
  - **≥1 ALLOWED** → proceed to routing. `groundedMemo` stays the focal verdict (unchanged);
    per-entity binding is owned by D3, and D3's memo guard prevents the focal memo from ever
    leaking into a non-focal group.

Same gate in `decideRoute`/`overallDisposition` (`:1304-1315`): focal-DENIED short-circuits to
`COVERAGE_DENIED` only when `!multiEntity`; otherwise disposition falls through to the group
verdicts (→ `PARTIAL` when some entity groups are withheld).

### D3 — Per-entity requested groups (SECURITY-CRITICAL)

**Expansion.** After `buildRequestedPlan` (`:552`, `:1265`), one new pure step:

```
plan = expandPerEntity(plan, bindings);   // no-op when bindings.size() < 2
```

For each existing group G (facet or single-selection) × each binding B (mention order):
a new `RequestedGroup` = G's candidates/kind/goalId/requiredEntityKeys, `routingEvidence =
"entity-facet"`, plus a new nullable field `RequestedGroup.binding` (null everywhere today —
constructor-compatible, all existing call sites unchanged). ≥2 groups →
`plan.isMultiGroup()` → `disposeGroups` runs, which is already the per-group
authz+coverage+bind+execute+merge engine.

**Independent entitlement (the gate, rule 4f).** `coverageForGroup` (`:1678-1739`) changes in
exactly one place: when `group.binding() != null`, the interpretation scan `:1687-1719` adds
the filter `binding.canonicalId().equals(gi.canonicalId())` — the group's coverage verdict is
THIS entity's own CHECK result for the routed sub-domain, nothing else. Outcomes per binding:

| Binding status (routed sub-domain) | Group outcome | User-visible |
|---|---|---|
| `RESOLVED_ALLOWED` | `PROCEED(canonicalId)` → bind + execute | served |
| `DENIED` | `GroupCoverage.denied` → withheld | standard coverage copy, attributed by the user's verbatim (D5) |
| `UNAVAILABLE` | `denied` (existing `:1700-1711` fail-closed) | withheld, "could not verify" |
| no interpretation for this sub-domain | existing CLARIFY/WITHHOLD path — never binds | clarify/withheld |

**Memo guard (new, mandatory).** `:1720-1722` currently falls back to the focal
`groundedMemo`. When `group.binding() != null`, consume the memo ONLY if
`memo.resolvedId().equals(binding.canonicalId())`. Without this, Calderon's group would bind
Whitman's memoized id — wrong data under a right entitlement. One line, one dedicated test.

**Binding.** `injectCoverage` (`:1768-1780`) is reused verbatim: each entity group's bag gets
its own `withReference(extractAs, canonicalId).withResolvedValue(key, canonicalId)`, the
resolver short-circuits on the id pattern, and the agent call carries that entity's id. The
strip branch (`:1771-1776`) keeps holding for id-less PROCEEDs.

**Merge copy fix.** `disposeGroups` all-denied branch (`:1592-1600`) streams structural
`capability_unavailable` copy. When every denial was an entity-coverage denial, stream
`mapDenialReason` coverage copy instead (track a `coverageDenied` flag alongside
`deniedUserVisible`). Both-entities-denied must read as S4, not as "you lack the service".

**Disposition composition** (surfaced in `decideRoute` and trace):
both ALLOWED → `SERVED` compare; one ALLOWED + one DENIED/UNAVAILABLE → `PARTIAL` (served +
explicitly withheld); all DENIED → `COVERAGE_DENIED` (D2, before routing); routing abstain
unchanged → `ABSTAIN`.

### D4 — Capability × entity composition + the fan-out cap

Expansion composes with the reranker's facets by **cross product**: facets F (from
`rerank-facet` groups, or the single `single-selection` group) × bindings E → F·E groups.

- "concentration of A and B" → 1 × 2 = 2 groups (MC-1's shape).
- "A's performance and B's risk profile" → 2 × 2 = 4 groups. The reranker contract
  (`Decision.multiple(ids)`) carries **no capability→entity association**, and inventing one
  would be an LLM-judged pairing (violates the deterministic-disposition rule). Decision:
  **run the full product.** It is safe (every entity is independently CHECKed; extra covered
  data is never wrong data), deterministic, and bounded. The pairing optimization is a
  reranker-contract seam, explicitly deferred (rule 4g).
- No reranker change; `multiple` stays per-facet; its non-suppression of the score floor
  stands (GAPS doc, over-correction risks).

**Bound (config, CLAUDE.md §5 — no Java constants):**

```yaml
conduit:
  groups:
    max-entity-bindings: ${CONDUIT_GROUPS_MAX_ENTITY_BINDINGS:3}   # entities per request
    max-total-groups:    ${CONDUIT_GROUPS_MAX_TOTAL:6}             # F·E after expansion
```

Capping is deterministic (mention order = the order the user named them) and **never silent**:
`log.warn`, a `groups_capped` trace event (count requested vs kept), and the synthesizer is told
(same channel as withheld notes, D5) so the answer states "compared the first N; ask again for
the rest". Grounding's own `max-mentions: 8` already bounds the upstream RESOLVE fan-out.

### D5 — Synthesis: side-by-side, per-client attribution, no leak

Two entity groups can invoke the SAME agent; today's DATA label (`agentId` only, `:340`) and
figure key (`sourceAgent = agentId`) would collide. Changes:

1. **Entity-qualified node id.** Entity groups create `PlanNode(nodeId = agentId + "#" +
   canonicalId, …)` (`nodeId` is already a separate field; `agentId` keeps feeding manifest
   lookups and trace tags). Flat/single-entity paths keep `nodeId = agentId` byte-identical.
2. **DATA block header carries the entity** (`buildUserContent:340`):
   `--- DATA: meridian.wealth.concentration (http) [entity: REL-00042 "Whitman Family Office"] ---`.
   The id/name are resolver-produced DATA (World-B clean — no literal in Java), and the
   synthesis prompt already forbids cross-DATA-section arithmetic
   (`answer-synthesizer.system.md:6`), which now automatically means **no cross-client
   aggregation** — the existing prohibition does the compare-safety work unchanged. Add one
   static, domain-neutral sentence to the prompt skeleton: every figure must be attributed to
   the entity named in its own DATA header; comparison is restating each entity's own figures
   side by side, never combining them.
3. **Figures per client.** `GroundedFigureRenderer.render` keys by `result.agentId()`
   (`:40-49`) — switch to `nodeId` for the `sourceAgent` label so each figure row is
   attributed `agent#entity`; the validator's numeric checks are per-figure and unaffected.
4. **Withheld entity block.** Extend the existing WITHHELD mechanism (`:359-367`) with an
   entity variant fed from `disposeGroups`:
   `--- WITHHELD ENTITY: "<user's verbatim mention>" --- <the exact mapDenialReason(subDomain)
   manifest copy> ---`. Attribution uses the **user's own words**, never the canonical
   name/id — the resolver's knowledge of the full legal name is not disclosed, and the copy is
   byte-identical to what a standalone ask for that client streams (S4 parity). New
   `AnswerSynthesizer.synthesize` overload parameter (`withheldEntities`), empty everywhere
   today.

---

## 3. Entitlement / no-leak proof

The property: *a principal covering only A who asks "compare A and B" gets A's data, an explicit
withhold for B using the standard coverage message, and not one bit more about B.*

- **P1 — every served id passed its own CHECK.** The ONLY way an entity group reaches
  `PROCEED(id)` is a `RESOLVED_ALLOWED` interpretation **for that group's own canonicalId**
  (D3 filter) — produced by `runLattice` (`ReferenceGroundingService.java:278-293`), where CHECK
  is called per `(principalId, resolvedId)` — independent per entity by construction — or the
  focal memo under the id-equality guard, which was itself CHECKed (`:124-129`). RESOLVE stays
  principal-agnostic (rule 4f untouched — no resolve call changes anywhere in this design).
- **P2 — a non-allowed entity never binds.** DENIED → group withheld before binding;
  UNAVAILABLE → withheld (fail-closed, existing `:1700-1711`); no matching interpretation →
  CLARIFY/WITHHOLD, never bound (`:1723-1736`); and `injectCoverage`'s strip (`:1771-1776`)
  keeps the EntityResolver verbatim-self-bind hole closed for entity groups exactly as for
  facet groups. B's id therefore never reaches an agent call.
- **P3 — the prose cannot leak B.** Agent outputs are the only ground truth (hard rule 4c) and
  B's group never executed, so no B data exists in the synthesis prompt. The only B-referring
  content is the WITHHELD ENTITY block: the user's own verbatim + the same manifest denial copy
  the flat S4 path streams. Information disclosed about B ≡ information disclosed by asking
  about B alone — the "never confirm/deny beyond the standard coverage message" bound, exactly.
- **P4 — fail closed.** A coverage probe failure or grounding-deadline expiry is an
  `UNAVAILABLE` status (`:264-273`), which D2/D3 map to withheld (one entity) or terminal deny
  (all entities) — an unchecked entity is never served. No new network path is introduced that
  could fail open: the design consumes statuses grounding already computed.
- **P5 — order independence** (post extractor hardening): disposition is derived from the SET
  of bindings, not from which mention is focal — MC-2 and MC-3 converge on the same `PARTIAL`.

## 4. Must-not-regress — mechanism per item

| Guard | Why this design preserves it |
|---|---|
| **bug-261 capability-first (S3)** | Expansion runs strictly AFTER routing on the untouched masked text; routing/masking/floors byte-identical. S3 grounds one entity (Continental Freight → insurance interpretations, servicing route) → `bindings ≤ 1` → whole feature inert; structural deny path unchanged. |
| **S8 SWITCH / S9 CARRY** | D1 rule 1: only latest-turn EXPLICIT mentions form bindings — a carried anaphora entity can never spawn a second entity group or widen the fan-out. Window/mask logic untouched (`RoutePreparer:136-171`). Rerun both. |
| **Single-entity routing/masking byte-identical** | Every change is gated on `bindings ≥ 2`: D2's switch falls into the existing branches, `expandPerEntity` is a no-op, `RequestedGroup.binding` is null so `coverageForGroup`/memo behave verbatim, `nodeId == agentId`, no new synthesis blocks. Assert with `maskedTextHash`/plan equality tests + S1-S9. |
| **Coverage denials + no-leak (S3/S4)** | S4 (one entity, denied) stays the terminal `COVERAGE_DENIED` with identical copy (D2 keeps the `!multiEntity` switch verbatim). The PARTIAL withhold reuses `mapDenialReason` copy — no new denial wording anywhere. |
| **Abstain on off-topic (S6)** | No threshold, corpus, reranker, or relaxation change. An abstained route never reaches expansion (`:505-541` runs first). |
| **Fail-closed entitlement** | P4 above; plus the memo guard closes the one NEW cross-entity hole this feature could have opened (focal memo binding a non-focal group). `CLARIFY` remains deterministic set-intersection — unchanged code path. |
| **Zero fabricated IDs (rule 4b)** | Bound ids originate exclusively from `CoverageResolveResult.id()` via `GroundedInterpretation.canonicalId` — same provenance as today, per entity. |
| **Partial-tolerance (rule 4d)** | Entity groups ride `disposeGroups`' merge: a failed/denied sibling group never cancels the others (`:1500-1576` loop semantics unchanged). |

## 5. Tests

**Unit (gateway, Testcontainers where context needed — extend `RedisContainerTest`):**
- `EntityBindingSetTest` (new): 2 latest-turn EXPLICIT resolved mentions → 2 bindings;
  ANAPHORA excluded; same-id dedupe → 1; DENIED and UNAVAILABLE form bindings; NOT_FOUND /
  AMBIGUOUS do not; ordering = mention order.
- `RoutePreparerTest` (existing suite): masked text for two-entity compare unchanged by this
  feature (hash equality before/after) — masking is consumed, not modified.
- `ChatServiceRequestedPlanTest`: `expandPerEntity` no-op at 1 binding (plan deep-equal);
  1 facet × 2 bindings → 2 `entity-facet` groups; 2 × 2 → 4; cap at `max-total-groups` keeps
  mention order, emits `groups_capped`, and surfaces the capped note — never silently truncates.
- `coverageForGroup`: binding-filtered selection (group binds ONLY its own id's interpretation);
  DENIED binding → withheld; UNAVAILABLE → withheld; **memo guard** — focal memo with id X never
  PROCEEDs a group bound to id Y (the security test; write it first).
- Terminal lattice: multi-entity all-DENIED → coverage copy (not structural); mixed → proceeds;
  single-entity DENIED → byte-identical S4 behavior.
- `AnswerSynthesizerTest`: same-agent two-entity DATA headers distinct and entity-qualified;
  WITHHELD ENTITY block uses verbatim + manifest copy, never canonicalName/id; figure
  `sourceAgent` = nodeId.

**Smoke (`scripts/smoke-route.sh`, trace-truth on `/debug/route`):**
- **S10 compare-both-covered** (rm_jane): "compare the concentration of Whitman Family Office
  and Calderon Trust" → `disp=='SERVED'`, ≥2 groups with `routingEvidence=='entity-facet'`,
  primary wealth concentration agent.
- **S11 compare-one-denied, denied-first** (rm_jane): "compare the concentration of the Okafor
  account and the Whitman Family Office" → `disp=='PARTIAL'`, exactly one entity group withheld
  with coverage reason, wealth still primary. (Pins the MC-3 fix.)
- **S12 order flip** (Whitman first) → same `PARTIAL` (pins order independence; lands only
  after the extractor hardening — until then MC-2 shows the extractor drops Okafor).
- Rerun S1-S9 unchanged; rerun `eval/goal-pick` only if the extractor prompt lands with this
  (that change moves extraction, not this one).

**E2E (one Playwright/chat row):** rm_jane asks S11's query in Conduit Chat; assert the answer
contains Whitman figures, the standard coverage sentence for the Okafor reference, and **no**
numeric figure attributable to Okafor.

## 6. Implementation order (smallest safe step first)

1. **Extractor prompt hardening** (GAPS fix 1 — `entity-extractor.system.md` skeleton;
   TUNING, no gateway Java). Dependency: makes two-entity extraction order-independent (MC-2).
   Gate: goal-pick re-baseline + S1-S9.
2. **`EntityBindingSet` derivation + tests** (pure code, zero behavior change — nothing
   consumes it yet).
3. **`RequestedGroup.binding` field + `coverageForGroup` binding filter + memo guard + tests**
   (inert: no group carries a binding yet; the guard test is the security anchor).
4. **D2 terminal-lattice gate + `expandPerEntity` + merge-copy fix + config caps**, plus the
   same gate in `decideRoute`/`overallDisposition`. This is the behavior flip; land with S10/S11.
5. **D5 synthesis** (qualified nodeId, DATA headers, withheld-entity block, figure keying,
   one prompt sentence) + synthesizer tests + the E2E row.
6. **Full regression**: `scripts/world-b-check.sh` (must stay CRITICAL 0 — every new symbol here
   is a manifest key, canonical id, config value, or user verbatim), `scripts/verify.sh`,
   S1-S12.

Steps 2-3 are individually shippable no-ops; step 4 is the single reviewable behavior change.

## 7. Top risks

1. **Extractor fragility silently degrades compare to one-sided answers** (MC-2 live: the
   second name never becomes a mention, so no binding, no withhold, no error). Mitigation:
   order the extractor hardening first; S12 pins it. Residual risk accepted: an unextracted
   name behaves like today's NOT_FOUND demotion (content, not coverage) — honest but
   incomplete. This is the biggest risk because no downstream check can detect it.
2. **Memo cross-binding** — without the D3 guard, the focal memo binds the wrong client's id
   into a sibling group (data integrity + entitlement bypass in one). Mitigated by a one-line
   guard and a dedicated test written before the expansion lands.
3. **Fan-out × latency**: entity groups run serially in `disposeGroups`' loop; F·E groups
   multiply wall-clock. Caps bound it (6 groups max); parallelizing the group loop over the
   existing VT executor is a follow-up seam, not required for correctness.
4. **Same-agent result collision in synthesis/trace** if step 5 slips behind step 4: two
   identical `agentId` DATA blocks would invite cross-attribution by the LLM. Do not enable
   expansion (step 4) without the qualified headers (step 5) in the same release; the
   figure-validator fallback catches numeric misattribution but not prose.
5. **Disposition drift with the smoke/eval harness**: `overallDisposition` and the
   `GroupDisposition` projection must gain the entity dimension in the same commit as step 4,
   or S10/S11 assert against a blind projection.
