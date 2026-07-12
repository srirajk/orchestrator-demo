# Conduit — World B Architecture Lockdown

> **Status:** Locked-in design intent — recorded 2026-06-29.
> **Purpose:** The single source of truth for what "World B" is, why it's the product,
> how close we are, the exact architecture to get there, the risks and their mitigations,
> the demo that proves it, and the ordered build plan. Everything below was decided through
> a full audit of the running gateway plus a deep design review. If it is not in this doc,
> it is not part of the World B commitment.
>
> **Companion canonical docs:** [`PROJECT-OVERVIEW.md`](PROJECT-OVERVIEW.md) (what the project is +
> module map), `gateway-domain-architecture.md` (architecture), `AGENT-ONBOARDING-HANDBOOK.md`
> and [`../registry/README.md`](../registry/README.md) (the onboarding contract domain teams
> implement). This doc sits **above** those: it is the strategy and the lockdown; they are the
> mechanics.

---

## 0. The one-line strategy

> **Don't sell "generic AI." Sell a paved road with an admission gate.**
> A domain team *declares structure and labels examples* — they never write prompts or
> policy. An automated eval *certifies them in*. The glass-box *proves every step* at
> runtime. That framing is both the honest engineering truth and the thing an enterprise
> risk committee will sign off on.

The product is not "a chatbot that knows banking." The product is **a gateway that learns a
new business domain from manifests and a CRM URL, with a measurable quality gate, and zero
gateway code changes.** World B is that product. World A is a single-domain demo of it.

---

## 1. World A vs World B — the core framing

This distinction governs every decision below. We were blurring it; it is now explicit.

| | **World A** | **World B** |
|---|---|---|
| Gateway serves | One domain at a time | Wealth, PE, trade finance… **simultaneously** |
| A user query could belong to | The one configured domain | **Any** loaded domain — must be disambiguated |
| Onboarding means | Swap the config, redeploy a domain instance | Add a domain to a **live multi-tenant brain** |
| The manifest is | The configuration that domain ships | A first-class participant in routing + comprehension |

**The current system is World A with the config hardcoded into Java.** The demo we want to
give a prospective domain team *implies* World B ("here's our gateway, here's how you join
it"). **Almost every hard problem is in the A→B jump — not in templating a prompt.**

**World B is the whole game.** It is the achievement we are selling. We lock it down without
a miss.

---

## 2. Where we are — the honest assessment

There are **two layers**, and they are at very different maturity. Conflating them is how
"80% done" becomes misleading.

### Layer 1 — Execution / plumbing substrate → ~80% World-B ready

This layer is genuinely World-B-shaped already (not World A). Finishing it is "connecting
dots":

- `DomainManifestStore` already loads **two** domains and fail-fast validates the whole
  domain → sub-domain → agent tree at boot.
- Agent routing already does vector search across **all 9 agents spanning both domains** —
  a custody question ranks asset-servicing agents; a portfolio question ranks wealth agents.
  **Implicit domain routing already falls out of agent routing.**
- `CoverageClient` (DISCOVER / CHECK / RESOLVE) is domain-agnostic.
- `EffectiveManifest.merge()` exists (agent > sub-domain > domain precedence).
- Fan-out, virtual threads, circuit breaker, SSE streaming, JWT validation + JWKS rotation,
  Redis session store, revocation channel — all generic.

### Layer 2 — Intelligence / comprehension layer → ~35-40% World-B ready

This layer is still **monolithically wealth-shaped**. This is the real remaining work, and
it is **not** "connecting dots" — it is focused engineering. It is also the layer the demo's
credibility rests on.

**The exact failure if we shipped today as World B:** agent routing would correctly rank a
PE team's deal agents — but then `EntityExtractor` would try to pull a `relationship_reference`
out of *"what's the IRR on DEAL-0042"*, because the extraction schema is wealth-hardcoded.
**The routing spans domains; the comprehension does not.** That mismatch is the gap.

### The honest number

- Weighted by **lines of code**: ~80% done.
- Weighted by **what makes or breaks a prospective domain demo**: ~**60-65%** done.

The gap is **one connective seam, not a new subsystem**: make the prompt / entity / CLARIFY
layer **domain-scoped** (pick the manifest, inject its context) instead of wealth-hardcoded.

### The genuinely encouraging part

**There is zero research risk left.** Every remaining piece is a named, known pattern:

| Remaining piece | Known pattern it maps to |
|---|---|
| Domain-scoped prompts | Standard assistant-builder template injection |
| CLARIFY reliability | Deterministic `extracted ∩ required = ∅` check |
| Domain routing pre-step | Reuse the HNSW infra we already run for agents |
| Admission gate | The DeepEval release-gate discipline we already chose |
| Session migration on deploy | Temporal-style workflow-version pinning |
| Cerbos per-domain policy | Golden-path policy template + extension points |

Nothing on that list is "will this work?" Everything is "wire it the way it's already been
done." That is why the gut-check says *connecting dots* — and for the hard-engineering-risk
question, that gut is right.

### The one caveat carried into any sale, unflagged at our peril

**World B is architecturally there but empirically unproven until a structurally different
second domain runs green** — see §5 Risk 4. Until that moment we are selling conviction, not
proof. The moment a second, genuinely different domain passes the admission eval live on
screen, World B stops being a claim and becomes a demo.

---

## 3. Current-state audit — what is hardcoded, concretely

A full audit found **41+ domain-coupling violations across 12 Java files** plus one contract
violation in a Python service. This is the precise inventory the refactor must clear.

| Layer | File | Domain logic embedded |
|---|---|---|
| Entity extraction | `EntityExtractor.java` | Hardcoded LLM schema fields: `relationship_reference`, `fund_reference`, `ticker_references`, `period`. System prompt says "banking". Hardcoded regex `REL-\d+`, `FND-\w+`. Default period `"QTD"`. |
| Entity bag | `EntityBag.java` | Fixed Java record with wealth fields. A new entity type (e.g. `deal_id`) requires a code change. |
| Entity resolution | `EntityResolver.java` | `@Value("${conduit.crm.wealth.url}")` — one CRM URL baked in. Resolves only `"relationship"` and `"fund"`. |
| Input synthesis | `InputSynthesizerImpl.java` | `switch(fieldName){ case "relationship_id"…; case "fund_id"… }`. Clarification copy hardcodes "client relationship". |
| Session | `ConversationSession.java` | Named fields: `relationshipId`, `fundId`, `clientName`, `timePeriod` — all wealth-specific. |
| Chat pipeline | `ChatService.java` | Hardcoded entity type `"relationship"` passed to coverage resolve. Hardcoded user-facing copy: "client relationship", "client relationships in your coverage", clarification examples ("holdings, performance, settlements"). Denial-reason map hardcodes "client". |
| Intent classification | `IntentClassifier.java` | System prompt hardcodes "banking AI assistant", "client", "holdings", "settlements", `REL-XXXXX` format rules, FETCH/CLARIFY rules in prose. |
| Answer synthesis | `AnswerSynthesizer.java` | System prompt hardcodes "banking AI assistant for Meridian Bank"; fallback hardcodes "client name". |
| Entitlement | `CerbosEntitlementAdapter.java` | `RESOURCE_REL = "relationship"` constant; only one resource type supported. |
| Entitlement | `EntitlementService.java` | Hardcoded `"relationship"` literal in `emitAuthzDecision` calls. |
| Manifest merge | `EffectiveManifest.java` | Checks `requiredContext.contains("relationship_id")` by literal name; looks up clarification schema by `"relationship_id"` key. |
| App config | `application.yml` | `coverage.wealth-management.url` — domain name baked into the config tree. |

**Plus — RESOLVE contract violation** (`mock-agents/wealth-coverage/data.py`): `resolve()`
filters candidates by `principal_id`'s book *before* returning them. The contract
(`AGENT-ONBOARDING-HANDBOOK.md`) is explicit: **RESOLVE is principal-agnostic; CHECK is the
gate.** The correct `mock-agents/crm/data.py` already does this right (takes no principal for
the ID lookup) and is the reference implementation.

---

## 4. The architecture for World B

### 4.1 The four jobs hiding inside "intent classification"

"Intent classification" is actually three-to-four jobs with very different genericity. The
single biggest clarity win is naming them:

| Job | Genericity | Owner |
|---|---|---|
| **Domain routing** ("is this a PE or wealth question?") | Generic *mechanism*, domain-specific *data* | Platform (reuse vector infra) |
| **Intent classification** (FETCH_DATA / FOLLOW_UP / CLARIFY / CHITCHAT) | Fully generic taxonomy | Platform |
| **Entity extraction** (deal_id, REL-, period) | Fully domain-specific | Domain manifest |
| **Completeness judgment** ("enough to fetch, or must I CLARIFY?") | Generic *logic*, domain-specific *rules* | **Both — the hard one** |

The four intents are **domain-invariant**: FETCH_DATA = "get me live data", FOLLOW_UP =
"continue the prior thread", CLARIFY = "which X?", CHITCHAT = "conversational". These apply
equally to wealth, PE, insurance, trade finance. **The taxonomy is never declared by a domain
team and never changes.** What is domain-specific is *what triggers* each intent and *what
entities* to extract.

### 4.2 Deterministic CLARIFY — the highest-leverage architectural fix

> **CLARIFY is not a peer of FETCH_DATA. It is a function of entity extraction plus a
> required-entity policy.**

"Banking-related but no client mentioned → CLARIFY" is really:
`extracted_entities ∩ required_entities = ∅`. That is **not** an LLM judgment we want to
relearn per domain via prose. It is a **deterministic check** over the extraction output
against a manifest-declared required-entity set.

**The refactored flow (keep ONE LLM call for latency — we already merged intent+entity):**

```
LLM call (domain-scoped):
  → "user wants live data, mentioned these entity references"
  → output: intent_signal + extracted_entity_references   (extraction only)

Deterministic post-processing (generic gateway logic):
  → resolve extracted references against the effective manifest's entity_types
  → check: are required_context entities satisfied?
  → if NOT satisfied → effective_intent = CLARIFY   (regardless of what the LLM said)
  → if satisfied     → effective_intent = FETCH_DATA
```

This makes CLARIFY **reliable-by-construction** instead of reliable-by-prompt-engineering —
which is exactly the part that would *not* transfer across domains if left as prose. It also
means **the domain team never has to explain CLARIFY to the LLM**; they just declare which
entities are required (`required_context` in the sub-domain manifest), and the completeness
check is generic gateway code.

Do **not** re-split the LLM call for latency. Split *conceptually*: the call does
domain-scoped intent + entity extraction; the FETCH-vs-CLARIFY refinement happens
deterministically afterward.

### 4.3 Two-stage domain routing — the World B infrastructure

Do **not** build a single mega-prompt holding every domain's context. It demos beautifully at
one domain and rots at five (vocabulary collision: wealth "fund" vs PE "fund"; attention
dilution grows with total example count).

Instead, **domain routing is a first-class pre-step that reuses the HNSW infra already in
place** — it is the Stage-A resolver pattern, one level up:

```
Step 0 (NEW):  vector search over DOMAIN-level example prompts
               → top-k domains above a confidence floor
               → selects which manifest(s) to inject

Step 1 (existing, now DOMAIN-SCOPED):  one LLM call with ONLY the selected
               domain's 12-15 examples + that domain's entity_types
```

Benefits: every per-domain prompt stays at the size where few-shot is most reliable;
attention dilution is eliminated; two domains coexist without vocabulary bleed. Latency cost:
one cheap vector lookup we already pay for agents (sub-millisecond).

**Cross-domain queries are a first-class case, not an edge case.** A UHNW client whose wealth
account holds a PE fund position legitimately spans two domains. Policy: **top-k domains above
a confidence floor** (reuse the M4 confidence-floor pattern), fan their context blocks,
fan-out to agents in both, synthesize one answer. Decide top-1 vs top-k now — we chose
**top-k above threshold**.

### 4.4 The domain-context-injection pattern and its four failure modes

The pattern (generic base prompt + domain context block injected from the manifest at request
time) is the industry-standard "custom assistant" mechanic. It is correct. But it has four
real failure modes the demo-friendly version hides:

1. **Negative transfer / example contamination.** Bad few-shot examples don't fail loudly —
   they silently shift the model's decision boundary. A borderline prompt mislabeled
   CHITCHAT-instead-of-CLARIFY teaches the model to generalize that error. **Invisible
   without an eval gate (§4.5).** This is the dominant risk.
2. **Attention dilution at scale (the World B failure).** Injecting all domains into one
   call degrades with total example count and worsens as domains overlap. **Fixed by the
   two-stage routing in §4.3 — never one mega-prompt.**
3. **The completeness rule doesn't live in the few-shot well.** "CLARIFY when a required
   entity is missing" depends on which entities are required, which depends on which agents
   could serve the query — downstream knowledge examples can't teach. **Fixed by the
   deterministic check in §4.2.**
4. **Domain vocabulary collision.** "Position", "book", "fund", "exposure", "coverage" mean
   different things across banking domains. **Mitigated by domain-scoping (§4.3) plus an
   optional vocabulary/synonyms declaration in the manifest.**

### 4.5 Few-shot + the admission gate — the product's reliability story

Reliability in LLM classification comes from **few-shot examples in the domain's real
language**, not from the length of a universal rule set. 12-15 well-chosen labeled examples
beat any amount of generic prose. The elegant unification:

> **The domain team's labeled example set is simultaneously (a) the few-shot block injected
> at runtime AND (b) the eval set the gateway runs at onboarding to admit or reject the
> domain.** (Aligns exactly with the agreed DeepEval-as-release-gate strategy.)

**Onboarding becomes a measurable gate:**

```
1. Domain team submits manifest + ~30 labeled prompts.
   → ~15 used as the runtime few-shot block.
   → ~15 HELD OUT (never injected) as the scoring set.
2. Gateway compiles the domain prompt, runs the held-out set through the REAL classifier.
3. Produces routing-accuracy + clarification-precision numbers.
4. Below threshold → onboarding FAILS with a diagnostic
   ("your CLARIFY examples conflict with your FETCH examples on prompts X, Y").
   The domain team iterates on EXAMPLES, not prompts.
```

This converts the calibration burden from open-ended prompt-engineering (which a domain
banker cannot do) into a **labeling task** (which they can) plus an automated grader that
tells them when they're done. That is the platform-engineering move: replace expertise with a
paved road and a gate.

**Quota coverage is mandatory.** Left alone, domain teams submit 30 *easy* FETCH prompts; the
eval passes and production fails on exactly the CLARIFY/CHITCHAT boundary they didn't test.
Mandate a quota: **N per intent, M deliberately-ambiguous, K follow-ups-in-context.** The
quota is part of the onboarding kit.

**The example set is a living asset, not a one-time form.** The held-out eval catches
*inconsistency* (contradictory examples) but not *unrepresentativeness* (all-easy examples).
That residual gap is closed only by **production monitoring (Langfuse, per the agreed
continuous-eval choice) feeding misroutes back as new labeled examples** → review queue →
re-admit. Design this loop now.

### 4.6 The declare-vs-derive boundary

The domain team **never writes an LLM prompt or a raw Cerbos policy.** They fill a structured
manifest; the gateway *compiles* the prompts and policy from it. The discipline is being
honest about the boundary:

**Must be DECLARED (only the domain knows):**
- Entity types + extraction patterns (e.g. `deal_id: DEAL-\d+`). **The regex is a hint, not
  the extractor** — the LLM extracts the human *reference* ("the Acme deal"); deterministic
  lookup resolves it to an ID. The pattern only helps the LLM recognize a literal ID. This
  preserves the **zero-fabricated-ID** rule, which generalizes cleanly.
- **Required vs optional entities per capability** — the load-bearing declaration for CLARIFY
  (§4.2). "To fetch a cap table you need a resolved deal."
- The ~30 labeled, quota-covered example prompts.
- Clarification question templates per ambiguity type ("missing entity" vs "ambiguous-between-N").
- Domain vocabulary / synonyms (optional, high-value for homonym disambiguation).
- The resolution endpoint (CRM / coverage service) + its contract.

**Can be DERIVED / is PLATFORM-FIXED:**
- The intent taxonomy (fixed, never declared).
- Prompt scaffolding, few-shot formatting, the FETCH→CLARIFY refinement logic.
- Entity-resolution flow, authz prune-before-fan-out, synthesis, grounding check.
- Domain-routing embeddings (computed from the example set at registration).

**Don't oversell "zero tuning."** There is an irreducible loop: submit examples → eval → fix
examples → re-eval. We are not eliminating tuning; we are **relocating** it from "edit a
prompt (needs prompt-engineering + platform access)" to "fix labels (needs domain knowledge
only) against an automated grader (needs nothing)." Sell *that* — a genuine, defensible win —
not magic. Enterprises have been burned by "generic AI platform" claims; "paved road +
admission gate" is language platform-engineering buyers already trust.

### 4.7 Minimum viable manifest for reliable classification

If a domain cannot produce these three, it is not ready to onboard — and that gate is a
*feature*:

1. **Entity types with required/optional flags** (drives extraction + the CLARIFY check).
2. **The ~30 quota-covered labeled example prompts** (few-shot + admission eval).
3. **The resolution endpoint** (CRM / coverage service URL + contract).

Everything else (vocabulary, per-ambiguity clarification copy, sensitivity tiers) is
enhancement.

---

## 5. Risk register + mitigations

### Risk 1 — LLM prompt reliability in a generic world
**Mitigations (all above):** deterministic CLARIFY (§4.2) removes the least-transferable
judgment from the prompt; two-stage domain-scoped routing (§4.3) keeps each prompt small and
collision-free; few-shot + admission gate (§4.5) makes reliability *measured at onboarding*,
not hoped-for; quota coverage + production feedback loop close the representativeness gap.
**Status:** the highest-design-budget item; no research risk, but needs a regression suite
across both domains before shipping.

### Risk 2 — Session migration on deploy
A live multi-turn conversation must not change how it interprets turn 4 vs turn 1 because we
deployed a new manifest mid-stream. **Mitigation — pin each session to the manifest version
it was born under:**
- Write `manifestVersion` into session state at conversation start (key is already
  `conversationId`).
- Manifests are **immutable, content-addressed, versioned artifacts** — treat them like
  container images, not mutable config.
- On deploy: new conversations get the new version; in-flight conversations keep interpreting
  under their pinned version until they drain or expire; readers tolerate N-1.
- This is exactly Temporal's "workflow versioning" / schema-registry discipline. Immutability
  + version-pin + drain solves it.

### Risk 3 — Cerbos policy authoring
Domain teams cannot author ABAC policy from scratch. **Mitigation — golden-path template +
extension points:**
- Platform ships a **base policy** with the universal predicates: principal authenticated,
  principal is a member of the domain, resource owned-by / covered-by the principal (the
  generic coverage relation — the same shape as today's `rm_jane`-has-a-book check,
  abstracted to "principal has a coverage set; resource must be in it").
- Domain team declares only: their **resource kinds** (deal, fund, policy), the **coverage
  attribute** (deal-team membership vs RM book vs underwriter assignment), and any
  **sensitivity tiers**.
- The gateway **generates** the Cerbos policy from those declarations and **validates it at
  onboarding against seed test principals** ("as test-user-A the in-coverage resource is
  allowed and the out-of-coverage one is denied" must pass before go-live — generalizing the
  Whitman-allowed / Okafor-denied test).
- **Minimum a domain must write: zero raw Cerbos.** They declare resource kinds + coverage
  predicate + test principals.

### Risk 4 — The second domain is the real validation
World B is unproven until a **structurally different** second domain runs green. A
PE-with-a-deal-team that is a reskin of wealth-with-an-RM-book proves only that we can rename
strings. **Choose the second domain to maximize assumption violations vs wealth — it must
break at least three:**
- **Multi-entity binding:** a query needing *two* entity types resolved *together* before
  fetch (e.g. `deal_id` + `fund_id`). Wealth mostly resolves one relationship — this stresses
  the binding stage and the completeness logic.
- **A different authorization topology:** not "person owns a book of clients" but e.g.
  "deal-team membership with need-to-know walls / information barriers." A fundamentally
  different coverage shape validates the authz seam.
- **A different clarification topology:** wealth's main ambiguity is missing/ambiguous client;
  pick a domain whose main ambiguity is *which of two equally-valid entities*, or *which
  vintage/period*, so CLARIFY exercises a different branch.
- **Different grounding/units:** IRR / MOIC / multiples vs allocation percentages — stresses
  the numeric-grounding check on different number shapes.

**Selection rule:** if you cannot name the three assumptions it breaks, you've picked a bad
validation domain. The point of domain two is the assumptions it violates, not that it works.

---

## 6. The enterprise demo — the proof

The aha moment is **live onboarding with no code and no redeploy, ending on a prompt the
audience invents.** The five beats:

1. **Start from absence.** The gateway answers wealth questions. The prospective (e.g. PE)
   team asks a PE question — and it correctly **declines / does not route** (no PE domain
   registered). Establishes it isn't faking.
2. **Onboard live.** Drop in `pe-manifest.json` + their example set; hit register. **Run the
   admission eval on screen** — it scores their examples and prints a routing-accuracy number.
   The credibility beat: not "trust us," a *measured gate they watched pass*. (Also sells the
   eval discipline.)
3. **The killer beat — generalization, not lookup.** Ask the PE team to **invent a prompt on
   the spot**, in their own words, *not* in their example set. The glass-box lights up: domain
   routed → entities extracted → coverage authz decision → fan-out across their agents →
   grounded answer. Visceral: *"I never wrote a prompt, never touched your code, you ran a
   test in front of me, and it understood a sentence I made up thirty seconds ago."*
4. **Show a denial.** Ask about a deal outside the principal's deal-team. The glass-box shows
   it pruned. Proves the generic authz path generalized — the security people in the room need
   this beat specifically.
5. **Show graceful failure.** Kill one of their agents; the answer still returns and **states
   what's missing.** Enterprises don't buy happy-path demos; they buy "what happens when it
   breaks."

**The glass-box is what makes this real instead of theoretical** — every claim ("it routed",
"it checked entitlements", "it grounded the number") is *shown*, not asserted. Without it this
is another chatbot demo; with it, it is an auditable orchestration platform. The demo's whole
job is to convert "generic AI gateway" (a phrase buyers distrust) into "I watched a
non-engineer onboard a domain in five minutes and watched the gate enforce quality" (a thing
they can take to their risk committee).

---

## 7. Build plan — ordered and sized

Each step keeps the existing **88/88 E2E green**. Ordered by dependency and by value/risk.

**Pre-work — no Java, do first:**

| # | Step | Files | Risk |
|---|---|---|---|
| 0a | **Decide World A vs World B explicitly** — it's World B; this doc is that decision | — | none |
| 0b | **Add `entity_types` + `required_context` to domain/sub-domain manifests** — the load-bearing declarations for deterministic CLARIFY and manifest-driven extraction | `registry/domains/**/*.json` | low |

**Core refactor — the genericity seam:**

| # | Step | Touches | Risk |
|---|---|---|---|
| 1 | **Fix the RESOLVE contract** — `resolve()` searches all entities, returns all candidates; CHECK is the gate. Then tighten the Okafor E2E from 14-phrase fallback to a specific denial phrase | `mock-agents/wealth-coverage/data.py`, `tests/tests/e2e/tests/10-coverage-flow.spec.ts` | low |
| 2 | **Deterministic CLARIFY** — move FETCH-vs-CLARIFY out of the LLM into a generic check over (extracted entities × manifest `required_context`) | `ChatService.java`, `EffectiveManifest.java` | **medium — highest leverage** |
| 3 | **Generic entity context** — replace `EntityBag` (fixed record) with `EntityContext(Map<String,String> references, Map<String,String> resolved)`; replace `ConversationSession` wealth fields with `Map<String,String> resolvedEntities` + `primaryEntityType` | `EntityBag.java`, `ConversationSession.java`, `ConversationSessionStore.java`, all readers | medium |
| 4 | **Manifest-driven entity extraction** — `EntityExtractor` builds its LLM function schema dynamically from the effective manifest's `entity_types`; no hardcoded field names, patterns, or defaults; configurable `domain_context` opener | `EntityExtractor.java` | **medium — needs regression suite** |
| 5 | **Manifest-driven clarification messages** — every user-facing string in the coverage/clarification path comes from `clarification_schema`; no hardcoded copy in Java | `ChatService.java`, `InputSynthesizerImpl.java` | low |
| 6 | **Manifest-driven LLM prompts** — `IntentClassifier` generates entity rules from `entity_types`; `AnswerSynthesizer` takes `domain_display_name`; no domain strings in Java | `IntentClassifier.java`, `AnswerSynthesizer.java` | medium |
| 7 | **Two-stage domain routing** — HNSW over domain-level example prompts as a pre-step; top-k above confidence floor selects which manifest context to inject | `VectorIndex.java`, `AgentResolver.java`, new domain-router | **medium — new World B infra** |
| 8 | **Parameterize Cerbos resource type** — drop `RESOURCE_REL` constant; resource type comes from the manifest (`cerbos_resource_type` or `entity_types[0].resolve_type`) | `CerbosEntitlementAdapter.java`, `EntitlementService.java` | low |
| 9 | **Coverage URL from manifest** — remove `coverage.wealth-management.url` from `application.yml`; read coverage URLs from the loaded `DomainManifest` at request time | `application.yml`, `ChatService.java` | low |

**Productization — the reliability story and the demo:**

| # | Step | Touches | Risk |
|---|---|---|---|
| 10 | **Admission gate + eval runner** — onboarding compiles the domain prompt, runs the held-out labeled set through the real classifier, prints routing-accuracy + clarification-precision, fails below threshold with a diagnostic | new onboarding/eval module, DeepEval | medium — **the differentiator** |
| 11 | **Session manifest-version pinning** — write `manifestVersion` at conversation start; immutable manifests; in-flight conversations drain on the pinned version | `ConversationSession.java`, `DomainManifestStore.java` | low |
| 12 | **Cerbos golden-path template** — base policy + domain declares resource kinds + coverage predicate + test principals; gateway generates + validates against seed principals at onboarding | `infra/cerbos/`, onboarding module | medium |
| 13 | **Second (structurally different) domain** — pick one that breaks ≥3 wealth assumptions (§5 Risk 4); run it green end-to-end including the admission eval | new `registry/domains/*`, new agents/coverage | **the validation gate** |

**Sequencing guidance:**
- Steps **1, 5, 9** are low-risk structural corrections that make even the *current* single
  domain cleaner — safe to do first.
- Steps **2, 3, 4, 6, 7** are the genericity core — do them with a regression suite running
  across both domains on every change.
- Steps **10, 12, 13** are the product/proof layer — they turn "it's generic" into "watch it
  certified live."

---

## 8. Definition of done for World B

World B is locked-in and demonstrable when:

- [ ] No domain names, entity IDs, entity-type names, or domain copy exist in
      `gateway/src/main/java/` (CI grep rule green — generalize the existing H-invariant lint).
- [ ] `EntityExtractor`, `IntentClassifier`, `AnswerSynthesizer` build their prompts from the
      effective manifest — zero hardcoded domain strings.
- [ ] CLARIFY is decided by the deterministic `extracted ∩ required = ∅` check, not by the LLM.
- [ ] Session and entity context are map-based; adding an entity type is a manifest edit.
- [ ] Domain routing is a first-class pre-step; a query is scoped to top-k domains before the
      LLM call; cross-domain queries fan correctly.
- [ ] RESOLVE is principal-agnostic; CHECK is the only gate; the Okafor test asserts a
      specific denial phrase.
- [ ] Onboarding runs an **admission eval** that prints a routing-accuracy number and fails
      below threshold with a per-example diagnostic.
- [ ] Cerbos policy for a new domain is **generated** from declarations + validated against
      seed principals; the domain team writes zero raw Cerbos.
- [ ] Sessions pin their `manifestVersion`; a manifest deploy never reinterprets an in-flight
      conversation.
- [ ] A **second, structurally different** domain (breaking ≥3 wealth assumptions) passes the
      admission eval and runs green end-to-end — including a denial beat and an agent-kill beat.
- [ ] The five-beat demo (§6) runs live: absence → live onboard + on-screen eval → invented
      prompt → denial → graceful failure, all visible in the glass-box.

When the second domain passes the gate live on screen, World B is no longer a claim. That is
the achievement we are locking down.

---

## 9. The enforcement loop — how agent teams write code under this spec

The spec is worthless if it lives only in one person's memory and a human has to be the
guardrail every session. World B is enforced by a **three-layer loop** so that any agent — a
subagent, a future session, a human teammate — is checked against it automatically, without
anyone re-explaining it.

### Layer 1 — Deterministic gate (does not depend on remembering)

**`scripts/world-b-check.sh`** greps `gateway/src/main/java` for domain coupling and prints
every violation as a file:line worklist, with a CRITICAL count.

- **Status — ACHIEVED 2026-06-29: CRITICAL 0, REVIEW 0 ("gateway carries no domain
  knowledge").** Countdown: 68 → 67 (Wave 1) → 43 (entity pipeline) → 9 (prompts/CLARIFY/copy)
  → **0** (storage cleanup). Each pass validated live: routing held **95.0% F1** throughout,
  multi-turn carry-forward + telemetry trace tree intact. The check is now a hard gate in
  `scripts/verify.sh`, so domain knowledge cannot silently re-enter the gateway.
- The violation list *was* the World B worklist; the CRITICAL count *was* the progress metric.
- **Every build-plan step (§7) must drive the count down.** A step that claims to remove a
  violation class (e.g. step 4 = entity-extraction field names) must take that class to 0.
- **No change may increase the count.** That is the one hard rule even while the total is
  non-zero.
- **When CRITICAL reaches 0**, wire it into `scripts/verify.sh` and a pre-commit hook as a
  hard gate. From then on, domain knowledge cannot re-enter the gateway silently — the build
  breaks.

The check is intentionally split into CRITICAL (must be zero) and REVIEW (entity-type literals
/ domain-specific env names that need human judgment because they *might* legitimately read a
manifest). REVIEW flags are resolved or justified before the hard gate is turned on.

### Layer 2 — Always-in-context rule (every session starts knowing)

**`.claude/rules/world-b.md`** is loaded into context every session (same mechanism as the
OpenWolf rule). It states the six invariants, points here, and mandates the pre-flight read
and the post-flight check. This is what stops drift at the *start* of work, before a line is
written — no human reminder needed.

### Layer 3 — The agent harness (the contract for writing code)

Any agent writing gateway code — including subagents this project spawns — operates under this
contract. When spawning a coding subagent, inject this harness into its prompt verbatim:

```
WORLD B CONTRACT — you are editing a manifest-interpreter gateway.

PRE-FLIGHT (before writing code):
  1. Read docs/WORLD-B-LOCKDOWN.md §4 (target architecture) and §8 (definition of done).
  2. Run scripts/world-b-check.sh and record the CRITICAL count as your BEFORE baseline.

INVARIANTS (never bend — see §9 / .claude/rules/world-b.md):
  - No domain knowledge in gateway/src/main/java: no domain or client names, no REL-/FND-
    patterns, no entity-type literals, no user-facing domain copy. All come from the manifest.
  - CLARIFY is deterministic (extracted ∩ required = ∅), not LLM-judged.
  - Entity context is map-based; adding an entity type is a manifest edit, not a Java field.
  - LLM prompts are compiled from the manifest, not hardcoded.
  - RESOLVE is principal-agnostic; CHECK is the only gate. Zero fabricated IDs.

POST-FLIGHT (before reporting done):
  1. Re-run scripts/world-b-check.sh. Report BEFORE → AFTER CRITICAL count.
  2. AFTER must be <= BEFORE. If your step targets a violation class, that class must be 0.
  3. Confirm the existing E2E suite is still green (88/88).
  4. Self-report against the §8 checklist items your change was responsible for.

Return: the before/after count, which §8 items you closed, and any REVIEW flags you touched.
```

### The loop, in one line

> **Rule in context (Layer 2) → harness on every coding agent (Layer 3) → deterministic gate
> on every change (Layer 1) → count to zero → hard CI gate.** No step depends on a human
> remembering World B. The script is the memory.

### What this does NOT catch (and how it's covered)

The grep gate catches *textual* domain coupling. Three invariants are structural and not
grep-able — they are covered by required tests + the §8 checklist, not by the script:

- **Deterministic CLARIFY** (§4.2) → a unit test: missing required entity ⇒ CLARIFY without an
  LLM call.
- **Domain-scoped routing** (§4.3) → an integration test: a PE-shaped prompt selects the PE
  manifest context, not wealth's.
- **Admission gate** (§4.5) → the onboarding eval itself is the test: a bad example set fails
  registration.

These three are the items a reviewer (human or an adversarial subagent) verifies by reading,
because no regex can. Everything else, the script owns.
```
