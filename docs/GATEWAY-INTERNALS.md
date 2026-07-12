# Conduit Gateway — Internals (the engine, end to end)

> The technical bible for how the Conduit gateway works. This document is **self-contained**: every
> mechanism is spelled out here and grounded in the source (cited as `path:line`). It reflects the
> current state of `gateway/src/main/java` on the `conduit-platform` branch. Where the code and an
> older assumption disagree, the code wins and the discrepancy is called out.

---

## 0. What it is, in one paragraph

Conduit is an enterprise AI orchestration gateway for a bank. One plain-English question fans out
across specialist agents (HTTP + MCP) over multiple business domains, enforces entitlements at three
independent gates, and streams back one grounded answer — with the whole decision visible live in a
glass-box trace. The defining property is **World B**: *the gateway carries zero embedded domain
knowledge.* A new business domain is onboarded by adding manifest JSON + a coverage-service URL —
never by changing gateway Java. Everything below is machinery that operates over manifests; nowhere
in the request path does a domain name, client name, ID pattern, entity-type literal, or user-facing
domain string appear as a Java constant. The gateway is a single Java 25 / Spring Boot 3.5.16 service
(virtual threads on); the specialist agents are external systems, and a separate `registry` profile
of the same image ingests manifests and builds the routing index that the gateway only *reads*.

---

## 1. The request lifecycle

Two endpoints exercise the engine:

- **`POST /v1/chat/completions`** — the OpenAI-compatible chat surface (SSE or a single JSON object).
  Handler: `ChatCompletionsController` (`gateway/src/main/java/ai/conduit/gateway/api/v1/chat/ChatCompletionsController.java:138`).
- **`POST /debug/route`** — a read-only diagnostic that runs the *real* pre-routing pipeline and
  returns the decision **without invoking any agent**. Handler: `RouteDecisionController`
  (`.../api/v1/admin/RouteDecisionController.java:48`); engine: `ChatService.decideRoute`
  (`.../domain/chat/ChatService.java:1232`). It exists only when
  `conduit.debug.route-decision.enabled=true` and requires an authenticated caller.

### 1.1 Ingress, identity, and the async boundary

The controller reads identity **only** from the verified RS256 JWT (`IdentityExtractor.extractUserId`
reads `SecurityContextHolder`; a legacy `X-User-Id` trusted-hop was removed —
`.../infrastructure/identity/IdentityExtractor.java:10`). It then captures three thread-local values
*on the servlet thread* before offloading, because none of them survive the hop onto a virtual thread:

- the verified `Principal` (`RequestContext.getPrincipal()`),
- the caller's raw bearer token (`extractBearerToken()`, `ChatCompletionsController.java:130`) — this
  is the **F-IDENTITY** seam: the token is threaded explicitly through every downstream hop so each
  agent call carries the caller's own identity,
- the MDC map (requestId/conversationId/userId) and the OTel `Context`.

The pipeline is then submitted to an application-scoped `Executors.newVirtualThreadPerTaskExecutor()`
(`ChatCompletionsController.java:59`). It is `submit()`-ed (not `runAsync`-ed) precisely so the
returned `Future` can be **cancelled with interruption** when the client disconnects or the deadline
fires — `bindLifecycle` wires `emitter.onTimeout`/`onError`/`onCompletion` to `pipeline.cancel(true)`
(`ChatCompletionsController.java:100`). The SSE emitter's timeout is the end-to-end request deadline,
`conduit.request.deadline-ms` (default **90000** ms, `ChatCompletionsController.java:71`).

`stream:false` runs the identical pipeline into a `BufferingSseEmitter` and re-assembles the SSE
chunks into a single `chat.completion` JSON object (`ChatCompletionsController.java:208`).

A LibreChat auto-title request (a prompt containing "generate a concise", "name this conversation",
…) is short-circuited to a canned title stream so it never routes to agents (`ChatService.isTitleRequest`
`.../domain/chat/ChatService.java:223`, `streamTitle` `:231`).

Inside `ChatService.handleChat` (`ChatService.java:254`) a root OTel span `chat.handle` is started;
its trace-id becomes the single correlation id across logs, glass-box, and traces. The span is
stamped with `session.id`/`conversation.id`/`langfuse.session.id` (conversation = Langfuse session,
turn = trace), `user.id`, and segment tags — every value comes from the verified principal, never a
hardcoded literal. W3C baggage (`session.id`, `user.id`, `conduit.request.id`) is attached so it
propagates outward on every downstream HTTP call.

### 1.2 Intent classification — compiled from the manifest

`IntentClassifier.classify` (`.../domain/intent/IntentClassifier.java:236`) makes one LLM call that
returns structured JSON classifying the turn into one of four intents (`Intent` enum:
`FETCH_DATA, FOLLOW_UP, CLARIFY, CHITCHAT`). The prompt is **compiled per request from the manifest**:
`buildSystemPrompt(entityTypes, domainContext)` (`IntentClassifier.java:62`) generates the per-entity
JSON envelope fields, extraction rules, and the mentions field list from the effective manifest's
`entity_types` + `domain_context`; the 4-intent taxonomy and the instruction-hierarchy guardrail are
the only fixed, domain-invariant parts. A CLARIFY fragment is included only when some entity type is
both `required` and resolvable (`:88`). Decode temperature is pinned to `0.0` for determinism.

**Never a canned fallback on LLM failure.** On any error `classify` throws
`IntentClassificationException` (`IntentClassifier.java:247`) — it does not substitute a fabricated
intent. `IntentResult` documents the reason: a papered-over FETCH_DATA with an empty bag would then
trip the deterministic CLARIFY and present a provider outage to the user as "which client did you
mean?". Intent classification gets its own bounded retry budget (`max-retries` 2, `request-timeout-seconds`
10) so a slow classifier cannot burn the shared LLM budget.

`ChatService.handleChat` dispatches on the intent (`ChatService.java:343`): FETCH_DATA and CLARIFY
both go through `handleFetchData` (CLARIFY with `carryContext=false` so a bare under-specified ask
cannot inherit a confident domain from history), FOLLOW_UP through `handleFollowUp`, CHITCHAT through
`handleChitchat`.

### 1.3 Entity extraction → the mention/provenance model

The same intent call also extracts entity references for FETCH_DATA/FOLLOW_UP (one round-trip, no
separate extractor call on the live path). The rules are strict:

- **The LLM extracts human references, never identifiers.** The extraction rule instructs "Copy
  verbatim — do NOT normalize, expand, or look up the name … do NOT output an identifier code you
  recall from an earlier answer" (`IntentClassifier.java:134`). A deterministic resolver maps names to
  IDs later (§1.6, §1.8).
- **Character spans are gateway-derived, never LLM-emitted.** `MentionAligner.align`
  (`.../synthesis/input/MentionAligner.java:41`) aligns each verbatim string against the original
  message using a normalization-to-original index map: it builds a lowercase, diacritic-folded
  (`NFD` + strip combining marks), whitespace-collapsed string with parallel `origStart[]`/`origEnd[]`
  arrays, so the returned `MentionSpan` always indexes the **original** characters. It is tolerant of
  case, punctuation/whitespace runs, possessives, and Unicode, anchored on token boundaries, and
  supports N-th occurrence for repeated references.
- **Deterministic focal derivation.** `deriveFocalReference` (`IntentClassifier.java:581`) picks the
  turn's focal entity from conversation structure, not the model's whole-history pick: (1) an id the
  user literally typed in the latest message (matched against the manifest `id_pattern`); (2) a name
  the user *names* in this turn (shares a distinctive ≥4-char word with the latest message); (3) a
  recency carry when the latest message only back-references ("their goals?") — bind to the
  most-recently-named entity via `focalIdByNameMatch` then `lastFocalSingleId`; (4) else the model's
  value only if it appears verbatim in a user message (dropping any recalled id); (5) else null →
  downstream clarification.

The result is an `EntityBag` (`.../synthesis/input/EntityBag.java:23`) — a **map-based** carrier with
no entity-type-specific fields (`references` = `extract_as`→raw text; `lists`; `resolved` =
`key`→resolved id; `mentions` = the span-aware `MentionSet`). Adding an entity type is a manifest
edit, not a new Java field. `Mention` carries `{entityKey, extractAs, verbatimText, messageIndex,
span, MentionSource}` where `MentionSource ∈ {EXPLICIT, ANAPHORA}` (`.../synthesis/input/MentionSource.java:15`).

### 1.4 Route preparation — the shared pre-routing pipeline

Every route-consuming path (FETCH_DATA, the FOLLOW_UP probe, `/debug/route`) runs **one** component,
`RoutePreparer.prepare` (`.../domain/chat/RoutePreparer.java:98`), which returns a `PreparedRoute`
(masked routing text + the full grounded set + relaxation flag + diagnostics). Its ordered stages:

1. **Ground mentions** — `ReferenceGroundingService.groundMentions` (§1.6) produces the full grounded
   set including a focal verdict.
2. **Choose bounded turns** (`RoutePreparer.java:136`). The routing window is chosen by message
   indices so masking stays message-local:
   - `carryContext=false` (CLARIFY) → **window 1** (route on the bare message);
   - entity known and the latest message has no id → **window 1** (this turn supplies the facet);
   - entity known and the latest message *does* carry an id → **window 2** (fresh id + one prior
     turn's facet);
   - keyword-less → **`conduit.chat.routing-context-turns`** turns (default **4**) — carry the topic.
3. **Mask resolved spans, then join** (`maskedJoin`, `RoutePreparer.java:199`). Spans index the
   original message, so each selected message is masked in its own coordinate space and only then
   trimmed and joined. Both `RESOLVED_ALLOWED` and `DENIED` mentions are masked (both have a canonical
   resolution). See §2 for the masking mechanics (the star of the engine).
4. **Residual / widen** (`RoutePreparer.java:154`). If a context-carrying turn's masked residual is
   "near-empty" (fewer than `min-residual-content-tokens` content tokens that are neither a config
   stopword nor the mask deictic — `isNearEmpty`, `:351`), widen to the full masked window. A CLARIFY
   turn never widens.
5. **Relaxation signal** (`RoutePreparer.java:174`): `relaxationAllowed = (focalAllowed &&
   maskingComplete) || nonCoverageScopedId`. Bias-to-fetch relaxation is earned only when the focal
   reference RESOLVED_ALLOWED and its span was actually masked (no alignment miss, no leak), or a
   deterministic non-coverage-scoped id_pattern matched. An unmasked/leaked resolved mention forfeits
   relaxation — the ordinary abstain gates then apply.

The policy inputs are config, not Java word lists: `RoutePreparationPolicy`
(`.../domain/chat/RoutePreparationPolicy.java`) carries `maskToken`
(`conduit.routing.entity-mask-token`, default `"the subject"`), `residualStopwords`
(`conduit.routing.residual-stopwords`, default empty), and `minContentTokens`
(`conduit.routing.min-residual-content-tokens`, default 1); wired in `RoutePreparationConfig`.

### 1.5 Grounding runs before routing — the gate

A security-relevant disposition (a coverage DENY) and its inputs — the extracted reference, the
manifest required entity, the coverage service — do **not** depend on routing. So `handleFetchData`
drives the terminal grounding lattice *before* the router runs (`ChatService.java:411`): a grounded
out-of-book reference denies at *any* embedding score. The focal verdict comes from the memoized
`PreparedRoute.groundedSet().focalVerdict()`; only a scalar-only extraction that carries no mention
model falls back to the single-reference `ReferenceGroundingService.ground` path (`ChatService.java:429`).
The lattice (`ChatService.java:433`):

- **DENIED** → stream the manifest-declared denial copy, emit `check_denied`, return.
- **UNAVAILABLE** → **fail closed** ("I am unable to verify your coverage right now …"), return.
- **RESOLVED_ALLOWED** → memoize the resolved id (`groundedMemo`) so the coverage pipeline consumes it
  instead of re-resolving, and route with `entityKnown=true`.
- **AMBIGUOUS / NOT_FOUND / NONE** → the normal pipeline runs unchanged.

### 1.6 Semantic routing

`ChatService` calls `AgentResolver.resolveContextual(maskedRoutingText, relaxationAllowed[, groundedDomainIds])`
(`ChatService.java:484`). This is HNSW/cosine retrieval over the de-entitied example corpus, an
abstain/margin gate, a bounded LLM re-ranker for the hard band, and a confidence-floor prune — all
detailed in §2. Its output is a `ResolverResult` (selected candidates, below-floor `skipped`, a
`fallback` flag, `topScore`, `margin`, `rerankFired`, and — on a valid multi-facet rerank —
`rerankSelectedIds`).

If the resolver abstains (`fallback` or empty selection), `handleFetchData` runs **abstain triage**
(`ChatService.java:505`) in order: (1) if the turn carries prior assistant data, answer from history;
(2) a deterministic required-entity CLARIFY over the broad candidate list (`attemptRequiredEntityClarify`,
`:2135`) so a vague-but-in-domain ask ("show me holdings") clarifies with the caller's in-book options
rather than "no service"; (3) only then the clean no-service message. All three are World-B clean and
the CLARIFY decision is `required ∩ extracted = ∅` decided in Java (`requiredIntersectionEmpty`, `:2191`).

### 1.7 Requested-capability groups

The routed capabilities are partitioned into **requested groups** (`buildRequestedPlan`,
`ChatService.java:1377`). When the re-ranker named an explicit multi-facet shortlist
(`rerankSelectedIds` with ≥2 back-able ids) there is one group per facet; otherwise there is exactly
**one** group of every selected candidate — the common single-capability request is byte-identical to
the pre-group flat path. The multi-group path (`disposeGroups`, `ChatService.java:1486`) runs
structural authz + coverage + binding + execution + withhold **per group**, then merges allowed
groups: all denied → a structural denial; some denied → serve the allowed and honestly label the
fully-withheld domains; a DAG group whose producer is authz-pruned → deny that group (never a silent
flat fallback); a group with no requested facet is never formed, so a denial only ever surfaces for a
capability the user actually requested.

### 1.8 Structural authorization, coverage, entitlement

For the single-group path (`ChatService.java:560` onward):

- **Structural gate** — `EntitlementService.filterAgents` (Cerbos) prunes agents the principal may not
  invoke, *before* input synthesis. `explainStructuralGates` emits one ordered `gate` frame per
  gate/agent (audience → segment → classification) for the glass box (§3).
- **Coverage gate** — for the first resource-scoped agent, run DISCOVER / RESOLVE / CHECK against the
  domain's coverage service, re-scoped to the routed sub-domain's `required_context`/`entity_types`
  (`ChatService.java:644`). RESOLVE is principal-agnostic; CHECK is the only gate; UNAVAILABLE fails
  closed; ambiguity clarifies; an empty book denies. A `groundedMemo` from §1.5 short-circuits the
  re-resolve. A deterministic named-reference backstop (`resolveNamedReferenceBackstop`, `:2240`)
  recovers a proper-noun the extractor dropped, resolving each capitalized phrase principal-agnostically
  and running CHECK only on a *unique* resolution.
- **Entity resolution + entitlement** — `InputSynthesizer.synthesize` binds agent inputs from the bag;
  a coverage-verified canonical id is injected so `EntityResolver` short-circuits via `id_pattern`
  (no network call). If a resolved id is present, `EntitlementService.checkRelationship` is the final
  per-entity check (`ChatService.java:892`).

### 1.9 Fan-out

`ChatService` first attempts a feature-flagged DAG (`tryDag`, `ChatService.java:1060`; `dag-enabled`
default **false**, so a miss/flag-off is a byte-for-byte no-op). Otherwise it builds a flat `Plan` of
one `PlanNode` per bound agent and calls `FlatPlanExecutor.execute` (§4). Fan-out is virtual-thread
parallel, joined to a deadline, and **partial-result tolerant**: a failed agent never cancels its
siblings.

### 1.10 Grounded synthesis and the SSE stream

`AnswerSynthesizer.synthesize` (`.../synthesis/answer/AnswerSynthesizer.java:199`) receives the agent
outputs as delimited **DATA** blocks, renders grounded figures, streams the answer as byte-correct
OpenAI SSE, and returns the text (mirrored onto the root span as `langfuse.trace.output`). §5 details
the grounded-figure protocol and the byte contract.

### 1.11 Telemetry and audit — never on the request path

Every stage publishes structured `TraceEvent`s to `TraceEventPublisher`, which buffers per request and
hands the buffer to async writers at `request_complete`. Nothing touches Redis or the audit store
synchronously (§7).

### 1.12 The `/debug/route` projection

`decideRoute` (`ChatService.java:1232`) runs the exact production sequence
(`IntentClassifier.classify → RoutePreparer.prepare → the same resolveContextual overload →
withPrimaryCandidate → buildRequestedPlan → per-group filterAgents`) using the **same beans**, then
projects the outcome into a `RouteDecision` and stops before input synthesis. The response is stamped
`RouteDecision.PRODUCTION_PATH = "production"` as a witness that the release-gate harness scored the
real routing, not a forked one.

---

## 2. Routing intelligence (the star)

The precision core is the separation of **what** is asked (the capability) from **who/what** it is
asked about (the entity). The state-of-the-art claim is that routing keys on the action, not the name.

### 2.1 Capability-first routing (the bug-261 fix)

The corpus is **de-entitied**: manifest example prompts are capability templates ("pending settlements
for this relationship"), never instance names. A client name in an example would teach the retriever
that *a customer name is a capability feature* — the root cause of "settlements for Continental
Freight" routing to insurance because "Continental Freight" is an insurance policy. Routing therefore
selects agents by cosine similarity of the (masked) query to each agent's de-entitied example corpus.

### 2.2 Query-side entity masking

`RoutePreparer` masks the grounded entity spans before embedding so retrieval keys on the action. The
mechanics (`RoutePreparer.java`):

- **Mask only the entity, keep the capability word.** *Needle tightening* (`RoutePreparer.java:106`):
  when the LLM extracted a greedy verbatim ("Calderon Trust holdings") and the resolver's canonical
  name ("Calderon Trust") is a *provable proper sub-phrase* of it (aligns via `MentionAligner` and is
  not the whole verbatim), only the canonical name is masked, so the action word ("holdings") survives
  into the routing text. On any miss (no single canonical name, no sub-phrase alignment, or equality)
  this is byte-identical to masking the full verbatim — the safety property that leaves single-turn
  masking untouched. The canonical name comes from `soleResolvedCanonicalName` (`:382`): exactly one
  distinct resolved name, else no tightening.
- **Mask the name *and* the canonical id**, at every in-window occurrence (name + id, "Name (ID)"),
  merging overlapping/nested/duplicate spans before a single reverse-offset application (`collectMaskSpans`
  `:228`, `mergeSpans` `:259`, `applyMask` `:286`).
- **Anti-injection.** A user-typed occurrence of the mask deictic that lies *outside* a mask span is
  neutralized to equal-length whitespace (`sanitizeSpans`, `:305`) so a user cannot inject the token.
- **The mask token can never be a domain word.** `effectiveMaskToken` (`:444`) bans the configured
  token from equalling any manifest `EntityType.display/resolveType/key`, falling back to the pure
  deictic `"that"` on collision — a World-B guard.

Diagnostics (`PreparedRoute.MaskDiagnostics`) record the mask mode
(`masked-base`/`masked-widened`/`unmasked-base`), mention/span counts, alignment misses, residual
class, and non-reversible hashes of the raw and masked text — enough to audit masking without carrying
a raw entity value.

### 2.3 The retrieval + abstain gates

`AgentResolver.resolveContextual` (`.../resolver/service/AgentResolver.java:218`) queries the HNSW
index (`VectorIndex.search`, no domain filter, `topK` = `conduit.resolver.top-k` default **10**),
converts cosine *distance* to *similarity* (`score = 1 - distance`, `.../registry/index/VectorIndex.java:103`),
dedupes by agent keeping max similarity, and applies a layered gate. The knobs (defaults; the running
values come from `application.yml`, which overrides the Java `@Value` fallbacks):

| Property | Meaning | Default |
|---|---|---|
| `conduit.resolver.confidence-floor` | absolute cosine floor | **0.30** (yml; Java fallback 0.35) |
| `conduit.resolver.relative-floor-factor` | dynamic floor = `max(absolute, topScore*factor)` when `topScore>0.55` | **0.65** |
| `conduit.resolver.domain-margin` | leader vs best other-domain candidate | **0.05** |
| `conduit.resolver.decisive-score` | a leader this strong is trusted regardless of margin | **0.55** |
| `conduit.routing.min-score` | raw top-1 abstain floor (whole query) | **0.40** |
| `conduit.routing.min-margin` | raw top-1-vs-top-2 too-close-to-call gate | **0.005** |
| `conduit.routing.rerank.margin-threshold` | near-tie trigger | **0.13** |
| `conduit.routing.rerank.abstain-adjacent-band` | near-abstain band above `min-score` | **0.08** |
| `conduit.routing.rerank.max-candidates` | re-ranker shortlist cap | **5** |

The contextual confidence gate (`AgentResolver.java:248`) requires
`leaderScore ≥ confidenceFloor && (entityKnown || leaderScore ≥ decisiveScore || margin ≥ domainMargin)`;
`select()` (`:274`) then applies the general routing abstain gate
(`!entityKnown && (topScore < routingMinScore || (!suppressMarginAbstain && topMargin < routingMinMargin))`)
and the dynamic confidence-floor prune. **Relaxation** (`entityKnown`, set from
`PreparedRoute.relaxationAllowed`) *bypasses* the contextual margin/decisive requirement and the routing
abstain gate — but never lowers the confidence floor, so long-tail noise is still pruned. Every abstain
path returns an empty selection with `fallback=true` and increments `resolver.fallback`; the chat path
then runs abstain triage (§1.6).

### 2.4 The re-ranker triggers (NEAR_TIE / NEAR_ABSTAIN / CONFLICT)

The re-ranker is invoked only for the hard band, chosen by `rerankTrigger` (`AgentResolver.java:432`)
returning `RerankTrigger ∈ {NONE, NEAR_TIE, NEAR_ABSTAIN, CONFLICT}`:

- **NEAR_TIE** — `topMargin ≤ rerankMarginThreshold` (0.13).
- **NEAR_ABSTAIN** — `topScore` in `[routingMinScore, routingMinScore + abstainAdjacentBand]`
  (0.40–0.48): a weak-but-eligible leader gets the model a chance to strengthen or abstain.
- **CONFLICT** — (config-gated, default off) the routed leader's domain is not among the grounded
  references' domains; CONFLICT dominates the others.

Error handling is stricter on the CONFLICT path: a re-ranker error, a non-shortlist id, or an unusable
`multiple` **abstains** (never trusts the embedding leader); on NEAR_TIE/NEAR_ABSTAIN a broken re-ranker
falls back to the embedding order. `LlmRoutingRerankerClient` (`.../resolver/service/LlmRoutingRerankerClient.java`)
sends the query + a bounded candidate set (id/name/description/score/skills), and the prompt
(`prompts/routing-reranker.system.md`) is explicit: "you select from a bounded candidate set and do
nothing else … Match the action the user asks for, not the names they mention," returning exactly one
of a single id, `"multiple"` (with an explicit `candidate_ids` facet subset), or `"abstain"`. It is a
**selector within a manifest-derived shortlist, never an authorization oracle**: the resolver validates
every returned id against the shortlist and it performs no entitlement decision. Reasoning models
(`gpt-5*`, `o1/o3/o4`) get temperature omitted (`supportsCustomTemperature`, `:174`).

### 2.5 Multi-turn behaviour (carry vs switch)

Multi-turn behaviour is decided deterministically from conversation structure, driven by `carryContext`
and the focal derivation:

- **FOLLOW_UP that carries no new entity** inherits the conversation's topic through the widened window
  and the recency carry (`deriveFocalReference` step 3); a bare anaphor ("its settlements") binds to the
  most-recently-named single-entity id.
- **A turn that names a new entity** switches focus: the explicit name wins the focus for this and the
  next anaphor (`deriveFocalReference` step 2).
- **A keyword-less follow-up whose data is not already in context** falls through to the fetch pipeline:
  `handleFollowUp` (`ChatService.java:1812`) re-uses the *same* masked text + relaxation as the fetch
  path (no separate unmasked probe) and, on a confident route, invokes `handleFetchData` with the
  prepared memo so grounding/masking runs exactly once.
- **The near-empty→widen policy** (§1.4 stage 4) recovers recall when masking blanks most of a
  single-turn window.

### 2.6 CLARIFY is deterministic, not LLM-judged

Two clarify directions, both decided in Java:

- **Missing required entity** — `required ∩ extracted = ∅` over the sub-domain's manifest-declared
  required keys (`requiredIntersectionEmpty`, `ChatService.java:2191`; also the abstain-triage clarify
  `:2135`). The *wording* is manifest-declared (template list of in-book options by name+id, or an
  opt-in LLM-composed question validated to introduce no foreign id — `buildClarificationQuestion`
  `:1924`); the *decision* is never in a prompt.
- **Named-but-unresolved / ambiguous** — a coverage RESOLVE that is ambiguous intersects the candidates
  with the caller's discovered book and asks; a not-found reference asks for a specific identifier.
  A named reference that resolves uniquely is *fully specified* and runs CHECK (honest deny if out of
  book) rather than clarifying with the caller's own clients.

### 2.7 The embedding sidecar (model-stamped index)

Embeddings come from a Python `sentence-transformers` sidecar (**all-MiniLM-L6-v2, 384-dim**) over an
OpenAI-compatible `/v1/embeddings` endpoint, behind the `TextEmbedder` port
(`.../registry/embedding/TextEmbedder.java`). `RemoteEmbedder`
(`.../registry/embedding/RemoteEmbedder.java`) is the request-path implementation
(`conduit.embedding.remote.url` default `http://localhost:8083/v1/embeddings`; in compose,
`http://embeddings:8083/...`); it pins HTTP/1.1 with explicit connect/read timeouts (2000/5000 ms) and
`@PostConstruct` probes the endpoint, failing boot on a wrong-dimension response. `QueryEmbedder`
(request path, **uncached** — user queries do not repeat) and `ManifestEmbedder` (ingestion, batched +
content-addressed cache) sit on top. The embedder's identity `"remote:all-MiniLM-L6-v2:384"` is
**stamped into the index** and verified at startup: `RegistryReadinessVerifier` refuses to start the
gateway unless the index exists, is non-empty, and was built by the same model it queries with
(§8, §7.4) — "searching one vector space with another's vectors yields confident nonsense".

---

## 3. Entitlement — three independent gates

Identity and authorization are deterministic and pre-LLM. `EntitlementService`
(`.../domain/auth/EntitlementService.java`) is the enforcement facade; the Cerbos PDP verdict is
authoritative.

### 3.1 Per-hop JWT verification

Every request is verified against Axiom (the IAM service) as an RS256 JWS. `SecurityConfig.jwtDecoder`
(`.../config/SecurityConfig.java:163`) enforces, in order: **algorithm RS256** (JWE/non-JWS tokens are
rejected at the bearer resolver, `:104`), a `kid` resolvable via `JwksClient`, signature, `exp`, `nbf`,
`iss ∈ conduit.auth.required-issuers`, and `aud` contains `conduit.auth.required-audience` (default
`conduit-gateway`). `JwksClient` (`.../domain/auth/JwksClient.java`) caches JWKS for 5 minutes, warms up
at startup, and coalesces concurrent refreshes under a lock (deliberately blocking rather than
returning a spurious null during key rotation) — the IAM service persists its signing key so the `kid`
is stable across restarts. The `Principal` (`.../domain/auth/Principal.java`) is an ABAC subject:
`{id, tenantId, roles, adminDomains, segments: Map<segment,tier>, domains}` — a per-segment
classification map, no `book` claim and no numeric clearance in the token (book is a runtime coverage
concern). A legacy JSON-array `segments` claim degrades each entry to `"internal"` (least privilege).
The caller's raw token is threaded to every downstream hop (§1.1) and re-verified at each agent.

### 3.2 Structural gate — Cerbos PDP (segment → domain, classification)

`CerbosEntitlementAdapter` (`.../domain/auth/CerbosEntitlementAdapter.java`) makes one **batched**
`CheckResources` POST to Cerbos (`conduit.cerbos.host:http-port`, default `localhost:3592`) — never N
round-trips. The principal node carries `roles` and an `attr` with `segments` (the per-segment
classification map), `domains`, and `admin_domains`; deliberately **no `book`** (that is the coverage
service's job). Each agent resource carries `domain`, `audience` (default `segment`), `access_mode`
(default `read`), and `data_classification` (default `confidential`). The domain→segment mapping and
the classification ladder live in the **Cerbos policy** (`infra/cerbos/policies/`), not gateway code.

`explainStructuralGates` (`EntitlementService.java:166`) issues two batched calls (`invoke_membership`
and `invoke`) and infers the deciding gate for the glass box: **audience** (an `enterprise` agent
passes for everyone), **segment** (a membership deny means the agent's domain maps to no segment the
principal holds), or **classification** (a member whose tier is below the agent's required
`data_classification`). `filterAgents` (`:109`) keeps only allowed agents; `checkRelationship` (`:44`)
gates a resolved entity id (a revocation overlay is checked first — see below); `requiresCoverage`
(`:225`) returns true for every non-`enterprise` agent (enterprise/knowledge agents skip the coverage
pipeline). Cerbos is **fail-closed** by default (`conduit.cerbos.fail-mode=closed` → deny all on any
PDP exception, logged as a security event, not graceful degradation; a `local` mode is available for
demo resilience).

The admin plane adds `AgentAuthorization` (`.../domain/auth/AgentAuthorization.java`): `platform_admin`
manages any domain, `domain_admin` only domains in its `admin_domains` claim.

### 3.3 Data-classification gate

This is the classification arm of the Cerbos verdict above: the principal's per-segment tier is
compared against the agent's `data_classification`. A servicing analyst with `servicing=confidential`
is pruned from a `confidential-pii` agent — deterministically, from manifest + principal data. Because
this is per-agent, a domain can be partly served: `settlement_status` (confidential) survives while
`settlement_risk` (confidential-pii) is pruned, and the domain is *not* labelled withheld
(`computeWithheldDomains`, `ChatService.java:2383`, the bug-260 scoping).

### 3.4 Coverage gate — the book-of-business

`CoverageClient` (`.../domain/coverage/CoverageClient.java`) is three fail-closed operations against the
domain's coverage service over a reactive `WebClient` with a 5-second timeout (no Resilience4j here —
resilience is a Reactor timeout + `.onStatus(5xx)`):

- **DISCOVER** `GET discoverUrl` — the principal's book of resources.
- **CHECK** `GET checkUrl` — is a resolved id in the principal's book.
- **RESOLVE** `POST resolveUrl` with `{reference, type}` — **principal-agnostic**: it deliberately sends
  no `principal_id`, scoped only by `X-Tenant-Id`. The book gate is the subsequent CHECK plus the
  candidates∩discover intersection, never the resolution itself (World-B invariant 5).

Any 5xx or exception throws `CoverageUnavailableException` (metered `conduit.coverage.unavailable`); a
null/blank bearer token also throws ("refusing coverage call"). Callers **deny, never grant**, on this
exception.

`ReferenceGroundingService` (`.../domain/coverage/ReferenceGroundingService.java`) runs the Stage-1
RESOLVE→CHECK before routing. Its verdict lattice: `NONE, RESOLVED_ALLOWED, DENIED, AMBIGUOUS,
NOT_FOUND, UNAVAILABLE`. Precise semantics:

- **RESOLVE is principal-agnostic; CHECK is the only gate, and only ever receives a RESOLVED id**
  (`:122`) — the raw reference never reaches CHECK.
- **A reference that does not RESOLVE (NOT_FOUND) is demoted to content** — it never fills a coverage
  slot and can never become a spurious access denial (closing the "wrong-type entity → false outside
  your access" class).
- **UNAVAILABLE fails closed** — coverage unreachable → deny.
- `groundMentions` (`:164`) grounds every mention across all manifest interpretations under a **bounded
  budget**: `GroundingBudget` (`conduit.grounding.max-mentions` 8, `max-interpretations-per-mention` 4,
  `concurrency` 8, `stage-deadline-ms` 8000). Grounding runs on a per-request virtual-thread executor
  with a `Semaphore` concurrency cap, per-interpretation deadline, and dedup by
  `(resolveUrl, resolveType, refId)`; any timeout/expiry → UNAVAILABLE, fail-closed. Only the focal
  mention's leading interpretation feeds the terminal lattice (`focalVerdict`); the full set feeds
  masking/relaxation.
- **Withhold before binding, no cross-coverage leak**: a `canonicalName` is carried only for
  ALLOWED/DENIED, null on every non-resolving status; a group whose required coverage entity is
  unresolved is CLARIFIED or WITHHELD, never bound (`coverageForGroup`, `ChatService.java:1678`), so no
  unchecked id ever reaches an agent.

A `RevocationChecker` (`.../domain/auth/RevocationChecker.java`) is wired as the first check in
`checkRelationship`, but is **honestly documented as currently inert**: nothing writes the revocation
key, and the gateway and IAM sit on different logical Redis DBs (a bounded-context boundary), so it
always returns false. The correct fix is a short JWT TTL or a gateway-owned revocation store fed by an
IAM event — not tuning the fail-open branch of a no-op.

---

## 4. Fan-out — three shapes

### 4.1 Flat (independent capabilities, parallel) — the default

`FlatPlanExecutor.execute` (`.../orchestration/executor/FlatPlanExecutor.java`) fans out one virtual
thread per node (`Executors.newVirtualThreadPerTaskExecutor()`), each via
`CompletableFuture.supplyAsync`, and joins with
`allOf(...).orTimeout(conduit.orchestration.fan-out-deadline-ms).exceptionally(t→null).join()` — the
`exceptionally` swallows the overall-deadline timeout so **survivors are still harvested** (a failed or
slow agent never cancels its siblings; a straggler becomes a synthetic `TIMEOUT` result). The default
deadline is **60000 ms** (the class Javadoc still says "30-second" — stale comment, bug-270). The
executor captures the OTel `Context` and MDC on the calling thread and re-applies them per node (a
per-node `agent.invoke` span, `openinference.span.kind=AGENT`), and threads `callerToken` straight to
the harness.

`NodeResult` status is `OK, FAILED, TIMEOUT, BREAKER_OPEN, SKIPPED_CONDITION_FALSE, CONDITION_ERROR,
AUTH_EXPIRED`; `isOk`/`isCleanSkip`/`isFailure` distinguish success, a condition-false clean skip, and a
real failure — a distinction the synthesizer and the metrics honour (a request where every agent
failed is counted `no_data`/`FAILED`, never `ANSWERED`, `ChatService.java:976`).

### 4.2 Entity (compare — per-entity groups)

The compare arc is the requested-group model applied to a same-capability, multi-entity request: the
re-ranker's `multiple` names a facet per entity, `disposeGroups` disposes each per-entity group
independently, and the merged answer reports each entity's own figures (never a computed roll-up). This
is designed and built **on the `feat/multi-entity-compare` branch, not on main** — its current blocker
is documented in §10 (bug-281): a same-capability compare near-tie-abstains before the fan-out.

### 4.3 The DAG resolver (producer → consumer) — config-gated off

When a capability's manifest declares `io` contracts (typed inputs/outputs), `tryDag`
(`ChatService.java:1060`) can execute it as a producer→consumer DAG instead of a flat fan-out. It is
gated by `conduit.orchestration.dag-enabled` (default **false**); any miss falls through to the flat
path, so the flag off is a strict no-op. The pieces:

- **`DagResolver`** (`.../orchestration/planner/DagResolver.java`) — deterministic and stateless.
  Starting from a goal capability whose `io.consumes` has a `from` (produced-ref) edge, it matches each
  `from` to some capability's `io.produces[].type`. No producer → `MISSING_PRODUCER`; more than one →
  `AMBIGUOUS_PRODUCER` (it refuses to guess — "intentionally not an LLM decision"); a Kahn's-algorithm
  topological sort orders the plan, and a leftover set is a `CYCLE`. All failures are returned
  classified, never thrown.
- **AUTHZ re-gate** — the resolver pulls in producers that were not in the originally gated set, so
  `tryDag` re-runs `filterAgents` over *every* manifest the plan touches; a single prune refuses the
  DAG and falls back to flat (never invoking a resolver-pulled producer the principal is not entitled
  to). In the group path this is a *group denial*, not a silent flat fallback (`dagProducerPruned`,
  `:1789`).
- **`DagPlanExecutor`** (`.../orchestration/executor/DagPlanExecutor.java`) — executes by topological
  layers, classifying nodes `READY/WAITING/BLOCKED/CLEAN_SKIPPED` each pass. `Blackboard` holds
  produced outputs keyed by type; `checkComposable` gates a node before dispatch (a required upstream
  output that is `_complete:false`/`truncated:true`, or a bound input that fails
  `InputContractValidator.missingFields` against the introspected schema, yields a synthetic FAILED so
  a malformed request never reaches the agent). It supports `io.condition` (a JMESPath boolean → clean
  skip `SKIPPED_CONDITION_FALSE` or `CONDITION_ERROR`) and `io.map` bounded iteration (JMESPath over an
  input array, capped by `map.max-items` 100 and `map.max-concurrency` 8, truncation flagged and
  metered). It re-gates produced-entity arrays by coverage-allowed ids intra-DAG. Partial failure is
  tolerated the same way as flat: a failed dependency marks transitive dependents unmet, independent
  branches keep running.

### 4.4 The single-agent harness and protocol adapters

Every agent call goes through `AgentHarness.execute` (`.../orchestration/harness/AgentHarness.java`),
whose pipeline is **bulkhead → circuit breaker → adapter.invoke → SLA timeout**, and which **never
throws** (every outcome is a `NodeResult`). It owns its own virtual-thread executor. Resilience4j knobs
(`conduit.orchestration.harness.*`): per-agent bulkhead (`max-concurrent` 5, `queue-capacity` 20, an
immediate `BREAKER`/`QUEUE_FULL` reject when the queue is full), a per-agent circuit breaker
(`failure-rate-threshold` 50, `slow-call-rate-threshold` 80, `slow-call-duration-seconds` 10,
`open-wait-seconds` 30, `sliding-window-size` 10, `min-calls` 3, `half-open-permitted-calls` 2), and an
SLA timeout (`default-sla-ms` 5000, or the manifest's `slaTimeoutMs`) enforced via `future.get(...,
TimeUnit.MILLISECONDS)` with cancel-on-timeout. An expired JWT is caught pre-dispatch → `AUTH_EXPIRED`.
There is **no retry loop** in the harness (resilience is bulkhead + breaker + timeout). Per-agent
gauges: `conduit.circuit.breaker.state` (0/1/2), `conduit.bulkhead.executing`, `conduit.bulkhead.queued`.

The adapters implement `ProtocolAdapter` (`.../adapter/ProtocolAdapter.java`):
`JsonNode invoke(manifest, input, bearerToken)`. The contract requires the bearer token to be sent as
outbound `Authorization` and to **refuse (throw) rather than silently omit** when null/blank; adapters
never read `SecurityContextHolder` (it does not survive the VT hop).

- **`HttpAdapter`** (`.../adapter/http/HttpAdapter.java`, `protocol="http"`) — OpenAPI-driven: it
  fetches+caches the spec (unauthenticated introspection), finds the operation by `operationId`, and
  issues a GET (query params) or POST (JSON body) to the resolved base URL. The *data* call requires
  identity — it refuses without a bearer token. It injects W3C trace context (`traceparent`/`tracestate`)
  and wraps the response with the `X-Conduit-Verified-Sub` header.
- **`McpAdapter`** (`.../adapter/mcp/McpAdapter.java`, `protocol="mcp"`) — MCP **Streamable HTTP at spec
  `2025-11-25`** by default (a single `/mcp` endpoint; the deprecated HTTP+SSE transport is reachable
  only when a manifest declares `transport: "sse"`; MCP 2.0 `2026-07-28` is designed-for, not adopted).
  The protocol version is **never a Java literal** — it resolves from `connection.protocol_version` →
  `conduit.mcp.protocol-version`/`conduit.mcp.legacy-protocol-version` (no default; unconfigured
  throws). A call is `initialize` (capturing `Mcp-Session-Id` and the negotiated `protocolVersion`) then
  `tools/call` echoing the session id and `MCP-Protocol-Version`. HTTP/1.1 is forced (FastMCP/uvicorn
  returns 421 on HTTP/2), the timeout is `slaTimeoutMs` or 10000 ms, the bearer token is required, and
  a JSON-RPC `error` or `result.isError` is thrown (agent outputs are ground truth — never a masked
  success).

Input synthesis feeding the fan-out is Extract→Resolve→Bind (`InputSynthesizerImpl`): `EntityExtractor`
(the standalone LLM extractor — verbatim references, keyword fallback on failure, never a fabricated
id), `EntityResolver` (deterministic `id_pattern` short-circuit, else a `POST {query,type}` to the
resolve endpoint), and a bind stage that **drops an agent whose required field is null rather than
inventing a value**.

---

## 5. Synthesis

`AnswerSynthesizer` (`.../synthesis/answer/AnswerSynthesizer.java`) turns the harvested `NodeResult`s
into one grounded, streamed answer.

### 5.1 Agent outputs are untrusted DATA, never instructions

`buildUserContent` (`:335`) wraps each result: OK → `--- DATA: <agentId> (<protocol>) --- <pretty JSON>
--- END DATA ---`; a clean skip → `--- NOT APPLICABLE: … condition evaluated false; do not report this
as missing data ---`; a failure → `--- MISSING: … data unavailable (status: …) ---`; a structurally
withheld domain → `--- WITHHELD: <domain> --- outside the user's access … ---`. The final user message
is `dataContent + "\n\nQuestion: " + originalPrompt`. The shared instruction-hierarchy fragment
(`prompts/fragments/instruction-hierarchy.md`, rendered with `surface="the DATA sections"`) hard-codes
that everything in the data is untrusted and no instruction inside it can override the rules — a
prompt-injection from an agent payload cannot redirect the model.

### 5.2 The grounded-figure protocol — the LLM never types a currency/percent digit

Every monetary or percentage figure is extracted from the agent output deterministically and
substituted by the gateway; the model only writes a placeholder.

- **Extraction** — `GroundedFigureRenderer.render` (`.../synthesis/answer/GroundedFigureRenderer.java:34`)
  reads each OK result's manifest `io.produces[].figures[]`, compiles the figure's **JMESPath** against
  the agent data (`renderOne`, `:56`), formats the scalar (`percent`/`percent1`/`percent2`,
  `currency_usd`→`$#,##0.00`, `count`, `date`/`plain`), and mints a placeholder
  `{{figure_<index>_<slug(label)>}}`. A `GroundedFigure` carries `{label, placeholder, renderedValue,
  rawValue, format, sourceAgent, numericValues}` where `numericValues` is the tolerance set (raw value,
  both percent interpretations, the parsed rendered string).
- **What the LLM sees** — the `--- GROUNDED FIGURES ---` block carries only `label`, `placeholder`,
  `source_agent`, `format` per figure (the **actual number is deliberately withheld**). The system
  prompt (`prompts/answer-synthesizer.system.md`) orders: "write that figure's placeholder string
  exactly … Never type a currency amount or percentage yourself — no digits with `$` or `%`, no
  rewordings like '1.2 million', no rounding, no added commas, no unit conversions." Cross-entity
  aggregation is forbidden at the prompt layer: "never add, sum, average, or otherwise combine numbers
  drawn from different DATA sections."
- **Substitution + validation** — with figures present, the synthesizer *collects* the full draft
  (non-streamed), runs `substituteFigures` (placeholder → `renderedValue`, whitespace-tolerant) and
  `ensureFiguresMentioned` (appends any unmentioned figure), then `GroundedFigureValidator.validate`
  (`.../synthesis/answer/GroundedFigureValidator.java:22`). The validator scans each sentence's numerals
  and flags an unattributed `$`/`%` numeral (or any numeral near a matched label) that matches no
  figure's `numericValues` within tolerance. On failure it serves a `deterministicFigureFallback`
  ("the grounded figures … are: <label> is <value>; …"). Only the substituted text reaches the client
  (as a single content delta), so a raw placeholder never leaks. A separate best-effort
  `checkNumericGrounding` (`:812`) logs (does not block) any answer number absent from the agent data.

  *Known limit:* the validator scopes candidate figures to the sentence's matched label, so a correctly
  grounded numeral sharing a sentence with a different figure's label can be wrongly rejected (bug-273,
  §10).

### 5.3 The SSE byte-contract (OpenAI shape)

Chunks are `object:"chat.completion.chunk"`, `model:"conduit-assistant"`, `choices[0].index=0`. The
sequence is a **role delta** (`delta.role="assistant"`, sent immediately so the stream appears alive),
then **content deltas** (`delta.content`), then a **stop delta** (`finish_reason:"stop"`), then the
literal `" [DONE]"`, then `emitter.complete()` (`AnswerSynthesizer.java:279`; the short-response path in
`ChatService.streamTextAndComplete` `:2287` is identical). Each chunk is emitted with a **leading
space** so the wire form is OpenAI-exact `data: {json}` (Spring omits the space otherwise). The role
delta is emitted exactly once per response (a duplicate role delta previously left LibreChat's send
button disabled). An idle watchdog (a virtual thread) closes a stalled LLM stream after
`stream-idle-timeout-seconds` (30) so a mid-generation stall cannot pin the emitter to the 90s deadline;
the HTTP client is pinned to HTTP/1.1 to avoid the `h2c` upgrade 404 against cleartext providers. TTFT
is recorded on the first content token (`conduit.ttft`, `conduit.time.to.first.token`).

### 5.4 Partial / withheld / not-applicable / history

A partial fan-out (some agents failed, ≥1 succeeded) still synthesizes from survivors and states the
gap (`emitRequestPartial`); a total failure renders honest "data unavailable" prose but is recorded
`FAILED`/`no_data`. `synthesizeFromHistory` (`:437`) answers FOLLOW_UP-from-context and CHITCHAT with no
agent call, using a per-request history prompt (`prompts/answer-synthesizer-history.system.md`) that
also forbids computing a number not stated verbatim.

---

## 6. Prompts and model tiers

### 6.1 Externalized, fail-fast, compiled from the manifest

All prompts live in `gateway/src/main/resources/prompts/*.md` and load through `PromptLoader`
(`.../config/PromptLoader.java`), which reads every `prompts/**/*.md` **once at bean construction** and
**fails fast**: a blank resource, an empty corpus, an unknown name, or any leftover `{{token}}` after
rendering throws (a resource typo or a missing Java-side variable fails startup, not a request). The
files:

- `intent-classifier.system.md` (+ `intent-classifier.clarify-rule.md`) — the intent/entity prompt,
  compiled per request from `entity_types` + `domain_context`.
- `answer-synthesizer.system.md`, `answer-synthesizer.figures-block.md`,
  `answer-synthesizer-history.system.md` — the synthesis prompts.
- `routing-reranker.system.md` — the bounded-selector re-ranker prompt.
- `entity-extractor.system.md` — the standalone extractor prompt.
- `clarification-composer.system.md` (+ `.default-question.md`) — the opt-in composed-clarify prompt.
- `fragments/instruction-hierarchy.md` — the **shared instruction-hierarchy fragment**, rendered with a
  per-call-site `{{surface}}` ("the DATA sections", "the conversation", "the query and candidate
  descriptions"). This is the single anti-prompt-injection guardrail reused across every call site.

The domain-bearing tokens (display name, domain context, entity rules) arrive only as placeholders from
manifest/config — the resource skeletons carry no domain copy (World-B; `scripts/world-b-check.sh`
scans the directory).

### 6.2 Per-call-site model tiers (env-driven, provider-swappable)

Each call site is provider-swappable per call via `CONDUIT_LLM_*_BASE_URL` / `_MODEL` / `_API_KEY`. There
are **two layers** to read carefully, because they differ:

- **Compose / `application.yml` defaults are Z.AI GLM** — `glm-4.5-flash` for intent/extract/re-rank/clarify,
  `glm-4.6` for the synthesizer. This is deliberate: the committed default is the cheap provider so load/perf
  tests can never accidentally bill OpenAI.
- **The real deployment overrides these via `.env` (gitignored) to the locked OpenAI tiers** — this is what
  actually runs (verified live: the synthesizer logs `calling https://api.openai.com/v1 model=gpt-5-mini`).

| Call site | `.env` deployed model | Compose default |
|---|---|---|
| Intent classify (+ entity extract, same call) | `gpt-4.1-nano` | `glm-4.5-flash` |
| Standalone entity extractor | `gpt-4.1-mini` | `glm-4.5-flash` |
| Routing re-ranker | `gpt-5-mini` | inherits intent → `glm-4.5-flash` |
| Clarification composer | `gpt-4.1-nano` | inherits intent → `glm-4.5-flash` |
| Answer synthesizer | `gpt-5-mini` | `glm-4.6` |

To know which model is *actually running*, render `docker compose config` (it substitutes `.env`) or read
`.env` — not the yaml defaults. There is **no LLM-judge call site in the gateway** — the only "judge" is an
external Langfuse continuous eval (deployed on `o4-mini` via `JUDGE_MODEL`) that scores the emitted trace
off-path. The re-ranker (and any reasoning-model call site) omits `temperature` for `gpt-5*`/`o1`/`o3`/`o4`,
which reject a non-default value (`supportsCustomTemperature`).

---

## 7. Observability, audit, and insights

### 7.1 The rule: never on the request path

On the request thread, telemetry and audit incur only in-memory buffer appends and non-blocking `offer`s
into bounded queues. `TraceEventPublisher` (`.../infrastructure/telemetry/TraceEventPublisher.java`)
buffers `TraceEvent`s per request (`ConcurrentHashMap<requestId, List<TraceEvent>>`, bounded by
`buffer.max-events-per-request` 512 and `buffer.max-open-requests` 10000, both metered on overflow), and
at the terminal `request_complete` event copies the buffer and hands it to `AsyncTraceWriter.submit`
(and, when audit is enabled, `AsyncAuditWriter.submit`). The live glass-box SSE panel reads the
in-memory fan-out, never Redis; persistence never happens on the publish path. `TraceStreamController`
(`.../api/v1/trace/TraceStreamController.java`) serves `GET /trace/stream` (SSE, filterable by
`conversationId`), `GET /trace/{requestId}` (replay), and `GET /trace/history`.

`AsyncTraceWriter` (`.../infrastructure/telemetry/AsyncTraceWriter.java`) is a **bounded**
`ArrayBlockingQueue` (`async.queue-capacity` 2048) drained by an **isolated** virtual-thread pool
(`async.workers` 2); `submit` never blocks or throws (overflow drops the oldest batch, metered
`conduit.trace.dropped`). `RedisTraceStorageAdapter` writes each batch in **one pipelined round-trip**
(`trace:{requestId}` list + `conv_traces:{conversationId}` sorted set, 24 h TTL) on a dedicated
telemetry Jedis pool — replacing ~92 round-trips/request. A lost trace is acceptable; a lost audit
record is not (see §7.2).

`GatewaySloMetrics` records RED + saturation (`conduit.gateway.inflight.requests`,
`conduit.gateway.requests`, `conduit.gateway.request.duration` with p50/p95/p99 tagged
path/domain/outcome/status_class, `conduit.gateway.stage.duration` per stage).

### 7.2 OTel → Tempo / Langfuse / Prometheus

Spans are emitted through Micrometer-tracing/OTel and exported to Tempo; the root span carries
`langfuse.session.id`/`trace.name`/`trace.input`/`trace.output` and `domain:`/`agent:`/`segment:` tags so
Langfuse groups conversation→traces→observations and slices by principal/segment/domain; Prometheus
scrapes the Micrometer registry. Logs carry `traceId`/`spanId` + MDC keys for Tempo↔Loki pivots.

### 7.3 Immutable WORM audit — off the request path

When `conduit.audit.enabled=true`, `AsyncAuditWriter` drains the same per-request buffer on its own
bounded queue + isolated VT pool. `AuditRecordAssembler` builds one immutable `AuditRecord`
(`{schemaVersion, transactionId, conversationId, occurredAt, Principal(userId,tenantId), outcome, Counts,
gatewayVersion, events (the full ordered decision trace), contentSha256}`) — a SHA-256 over
canonical-ordered JSON for tamper-evidence — and `ObjectStoreAuditSink` writes it to S3-compatible object
storage with **Object-Lock (WORM)**: `objectLockMode=COMPLIANCE`, `retention-days` 2555 (~7 years, SEC
17a-4(f)), key `{prefix}/dt=YYYY-MM-DD/tenant={t}/{transactionId}.json`. Under COMPLIANCE the object
cannot be deleted or overwritten before retention expires, not even by account root. A dropped audit
record is a monitored, loudly-logged incident (unlike a trace).

### 7.4 Insights — native admin-gated analytics

`GET /v1/insights/*` (`.../api/v1/insights/InsightsController.java`) serves seven boards from
`BoardCatalog` (`.../domain/insights/BoardCatalog.java`):

1. **Executive Overview** — requests/24h, answered-rate, agent-calls, P95 latency, volume, outcome mix.
2. **Traffic & Intent** — questions/24h, TTFT p50/p95/p99, intent mix, adoption-by-role.
3. **Governance / Authorization** — allow-rate, decisions, coverage-gaps, decision mix, denials-by-agent,
   an authz ledger table.
4. **Agent Performance** — latency/selection by agent, calls-by-protocol, fan-out trend.
5. **Reliability / Resilience** — breakers-open, error-rate, JVM threads, bulkhead executing/queued.
6. **Live Trace / latency waterfall** — DAG share/node-count/fallbacks, a Tempo-backed per-agent
   waterfall, fan-out-now.
7. **Cost & Quality** — cost/tokens/traces (Langfuse), eval scores, grounding-score distribution,
   grounding-by-model, compaction.

Data comes through the `MetricsSource` seam (`PrometheusMetricsSource` for ops, `LangfuseMetricsSource`
for cost/eval — cost is computed in-gateway by multiplying Langfuse token counts by
`registry/model-prices.json` rates, flagged `estimated` when a model is unpriced). Every source is
**non-throwing at the query surface**; `InsightsExecutor` fans panels out on a dedicated VT pool with a
per-query TimeLimiter (`per-query-timeout-ms` 3000), a board deadline (`board-deadline-ms` 4000), and a
concurrency semaphore (`max-concurrent-queries` 8) — a slow or down source degrades that panel to an
**honest `unavailable`**, never a fabricated value. Access is admin-gated through the **same Cerbos PDP**
as chat: `InsightsAuthorizer.canRead` (resource `insights`, action `read`) is fail-closed; `chat_user`
gets 403.

---

## 8. The hard guarantees, and why this is SOTA

### 8.1 The guarantees (each enforced in code, not by convention)

1. **SSE byte-correctness** — role delta, content deltas, stop, `[DONE]`, OpenAI chunk shape, leading
   space, single role delta (§5.3).
2. **Zero fabricated identifiers** — the LLM extracts human references; a deterministic resolver
   (`id_pattern` short-circuit or coverage RESOLVE) maps them to ids; an unresolved reference clarifies,
   never guesses (§1.3, §1.8, §3.4). Spans are gateway-derived, not LLM-emitted.
3. **Agent outputs are untrusted DATA** — delimited, never instructions; the instruction-hierarchy
   fragment neutralizes injection at every call site (§5.1).
4. **Partial-result tolerance** — a failed agent never cancels siblings; the join harvests survivors to
   a deadline and the answer states what is missing (§4.1, §5.4).
5. **Deterministic CLARIFY** — `extracted ∩ required = ∅` decided in Java over manifest data, plus the
   named-but-unresolved direction (§2.6). No user wording can move it.
6. **Principal-agnostic RESOLVE; CHECK is the only gate** — resolution is never filtered by the caller's
   book; authorization is a separate explicit decision; UNAVAILABLE fails closed (§3.4).
7. **World-B: zero domain knowledge in gateway source** — no domain/client names, ID patterns,
   entity-type literals, or user-facing domain copy in `gateway/src/main/java`; onboarding a domain is
   manifest JSON + a coverage URL + a Cerbos segment mapping + re-ingest. `EffectiveManifest`
   (`.../domain/manifest/EffectiveManifest.java`) is the merged domain+sub-domain view every stage reads;
   `scripts/world-b-check.sh` is the deterministic gate.

### 8.2 Why it beats the rejected alternatives

- **vs. a planner / LangGraph / LangChain hardcoded flows** — those encode orchestration in code per use
  case. Conduit encodes it in manifests; onboarding is config, and the request path is one auditable JVM
  service with in-process `ProtocolAdapter`s — no external agent-gateway hop, no framework lock-in. The
  simple path is built (flat plans, flat semantic routing, HTTP+MCP); the scale seams (DAG, A2A) are
  defined behind interfaces, not built.
- **vs. "let the LLM decide access"** — treated as unsafe by construction. Entitlement is deterministic
  and pre-LLM (three gates, all before the model); the LLM is confined to extraction, ranking-within-a-
  shortlist, and faithful synthesis — never authorization. A prompt injection cannot move a gate because
  the gate never reads the model's opinion.
- **vs. bolting entitlement into the synthesis prompt** — a prompt-level guard is prompt-injectable. Ours
  is structural (Cerbos) + data-aware (coverage), both deterministic, both before the model.
- **vs. a pure-LLM skill-taxonomy classifier for routing** — that puts an unbounded LLM decision on 100%
  of routing calls (latency, nondeterminism, unauditable) and cannot hold every skill as agents multiply.
  Deterministic HNSW retrieval over a de-entitied corpus + a bounded re-ranker for only the hard band
  scales (HNSW) and stays auditable, and is tuned by manifests + an eval gate, not by editing Java.
- **why capability-first beats entity-routing** — a client name in an example teaches the retriever that
  a customer name is a capability feature (the "Continental Freight → insurance" failure). De-entiting
  the corpus + masking the query removes that; masking recovers the recall de-entiting costs; relaxation
  is earned only on a masked RESOLVED_ALLOWED focal, never on the mere syntactic presence of a name.

### 8.3 Honest current limits

- **Domain correctness is not the gateway's.** If an agent computes a metric wrong, the answer is wrong;
  the gateway guarantees faithful transport + entitlement, not domain math (a World-B boundary — "fixing"
  it would require embedding domain knowledge).
- **The synth figure validator is label-scoped** (bug-273): a correctly-grounded numeral sharing a
  sentence with a different figure's label can be wrongly rejected.
- **The same-capability compare is parked** (bug-281, on a branch): a compare of the same capability
  across two entities near-tie-abstains before the fan-out.
- **The DAG resolver is gated off** on live traffic (`dag-enabled=false`); its flat-fallback path is
  unexercised in production.
- **Multi-turn routing has residual, fail-safe imperfections** (occasional over-abstain / over-fetch of a
  sibling capability) — bounded, never a leak.
- **Single-tenant today.** A per-deployment-per-org tenant model is designed, not built.

---

## 9. The tech stack and the request-path / control-plane split

**Runtime.** Java 25 (bytecode target 21 — `gateway/pom.xml`), Spring Boot **3.5.16**, **virtual threads
on**. Web is Spring MVC + `SseEmitter`; every offloaded pipeline runs on a virtual-thread executor and
every outbound read is bounded (JEP-491 removes the synchronized carrier-pinning that made VTs unsafe for
blocking I/O).

**Routing + state.** Redis Stack 7.4 (RediSearch **HNSW** — `TYPE=FLOAT32, DIM=384, DISTANCE_METRIC=COSINE,
M=16, EF_CONSTRUCTION=200` — + RedisJSON), accessed via Jedis. The gateway and IAM use **separate Redis
instances/namespaces** (bounded contexts — no cross-namespace reads); a dedicated telemetry Jedis pool is
separate from the routing pool.

**Embeddings.** A Python `sentence-transformers` sidecar (`all-MiniLM-L6-v2`, 384-dim) over HTTP behind
`RemoteEmbedder`/`TextEmbedder` — the one deliberate cross-process seam on the request path, intended to
move in-JVM. The index is stamped with the model id; a mismatch refuses startup.

**LLM.** OpenAI-compatible, provider-swappable per call site via `CONDUIT_LLM_*` env (default Z.AI GLM,
§6.2), behind an HTTP client per call site.

**Authorization.** Cerbos PDP (structural, gateway-side) + coverage services (data-aware book). IAM
authorizes its own API with `@PreAuthorize` and has no Cerbos dependency.

**Identity.** IAM service (Axiom) — OIDC, RS256/JWKS, stable `kid`, verified at every hop.

**Resilience.** Resilience4j (bulkhead + circuit breaker in the harness).

**Telemetry / audit.** Micrometer + OTel → Tempo/Langfuse/Prometheus/Loki; WORM audit to S3 Object-Lock.
Both are strictly off the request path (buffer + async flush).

**Orchestration.** docker-compose. The everyday demo is the **no-profile core set**
(`docker compose -p orchestrator-demo up -d`): `redis-stack`, `gateway`, `registry-service`, `embeddings`,
`cerbos`, the mock agents (`wealth-http`, `servicing-mcp`, `insurance-http`, the knowledge agents), the
coverage services (`wealth-coverage`, `insurance-coverage`), `iam-service` + `postgres`, `admin-ui`, and
the observability collectors. `observability` (grafana), `eval`, and `scale` (k6) are opt-in profiles.

### 9.1 Request path vs control plane

The **registry ingests; the gateway only reads.** Manifest ingestion runs as the `registry` profile of
the *same image* in its own container (`SPRING_PROFILES_ACTIVE=registry`), on startup, and reconciles the
manifest folder as the source of truth:

- `RegistryIngestor` (`.../registry/ingest/RegistryIngestor.java`, `@Profile("registry")`) waits for the
  embedding service, ensures the index, then for every manifest: validate → introspect the live agent
  (`AgentIntrospector` — OpenAPI for HTTP, `tools/list` for MCP) → embed the example corpus
  (`ManifestEmbedder`, batched + content-addressed cache keyed by `model + sha256(text)`) → write the
  HNSW index (`VectorIndexWriter`, which stamps the model id). It is **fail-closed on invalid**
  (`fail-on-invalid=true`: any rejected manifest fails ingestion; zero loaded refuses to run) and
  **prunes orphans** (an agent the folder no longer describes is deregistered). Its health goes green only
  after ingestion succeeds, and the gateway's `depends_on` blocks on it.
- The gateway holds **no** `ManifestEmbedder`, `VectorIndexWriter`, or `AgentRegistrar` — those beans are
  `@Profile("registry")`, so the gateway *cannot* embed or mutate routing data. `RegistryReadinessVerifier`
  (`.../registry/readiness/RegistryReadinessVerifier.java`, `@Profile("!registry")`) refuses startup
  unless the index exists, is non-empty, and was stamped by the same model the gateway queries with.
- The write control plane (`POST/PUT/DELETE /admin/agents`) lives on the registry-service; the gateway
  keeps only `GET /admin/agents`.

---

## 10. Known bugs & open work

This is a demo/reference build with a small, clear punch-list. **Nothing open below is a live security or
correctness hole on `main`** — the data-no-leak, deterministic-entitlement, and no-fabrication guarantees
hold. The single biggest item is landing the multi-entity compare arc.

### Open bugs (on `main`)

- **bug-273 — synth figure validator label-scoping.** `GroundedFigureValidator` scopes candidate figures
  to a sentence's matched label, so a correctly-grounded numeral that shares a sentence with a *different*
  figure's label is rejected as unattributed. Model-agnostic; surfaces on concentration-style answers
  with `gpt-5-mini`. *Fix direction:* per-numeral nearest-label attribution (correctness-sensitive, not a
  blunt loosening of the guard).
- **bug-272 — synthesizer prompt contradiction (mostly resolved).** Moving the synthesizer off
  `gpt-4o-mini` exposed a contradiction between the inline figure rules; the externalized-prompt rewrite
  (a single placeholder protocol) resolved most of it. The residual is tied to bug-273.
- **bug-266 — coverage first-match nondeterminism.** When more than one candidate resolves a reference,
  the pipeline binds the first non-deterministically. *Fix direction:* a deterministic tie-break (or a
  forced clarify) on a multi-candidate resolve.
- **bug-267 — DAG flat-fallback path inert.** With `dag-enabled=false` the DAG resolver and its
  flat-fallback path are unexercised on live traffic; correctness is covered only by tests.
- **bug-268 — disconnect cancellation is write-triggered, not immediate.** Client-disconnect cancellation
  fires on the next emitter write, so an in-flight fan-out runs briefly after the client leaves (spends a
  little agent/LLM work for an answer no one will see).
- **bug-269 — `/debug/route` intent parity.** The decision endpoint mirrors FETCH_DATA/CLARIFY faithfully
  but not fully FOLLOW_UP/CHITCHAT, so those intents are scored approximately by the eval harness.
- **bug-270 — nits.** A `MentionAligner` repeated-token edge case; a demo-name in a Javadoc; a stale
  `application.yml` comment; the `FlatPlanExecutor` "30-second" Javadoc vs the 60s default; 32-bit
  `stableHash` headroom.

### Parked — the multi-entity compare arc (branch `feat/multi-entity-compare`, not on `main`)

- **bug-277 — compare silently drops a valid client.** When extraction misses one of two named,
  *covered* clients, the compare answers for the other and drops the missed one. Phrasing-dependent (an
  extraction-recall gap), not coverage-dependent.
- **bug-278 — the fix: clarify-on-incomplete-resolution.** Ask when the turn named ≥2 entities but
  resolved fewer — deterministic, World-B clean. Designed and built on the branch.
- **bug-279 — compare loses the explicit "withheld client" line.** The compare answer falls to the
  deterministic figure fallback, so the sentence naming the withheld client is dropped (the data-no-leak
  property still holds structurally — no withheld data is emitted).
- **bug-281 — THE MERGE BLOCKER.** A same-capability compare ("compare the concentration of A and B")
  near-tie-abstains ~75% of the time: the resolver reads the two near-identical entity-facet scores as
  ambiguous and abstains *before* the fan-out. *Fix direction:* a compare with ≥2 bindings must not be
  killed by the pre-expansion near-tie abstain.

### Roadmap

1. **Land compare** — fix bug-281, then merge the branch (bug-278 makes it honest; bug-279 restores the
   withhold-UX line).
2. **Orchestration reporting** — the Insights boards report ops + the *old* routing but are blind to the
   new intelligence. Instrument "why it clarified" (ambiguous vs incomplete-resolution vs missing-context),
   the rerank-trigger distribution, the capability-first/mask rate, and multi-turn carry-vs-switch; add an
   Orchestration board.
3. **bug-273 validator per-numeral attribution** — unblocks `gpt-5-mini` synthesis narratives.
4. **The review should-fixes** — bug-266..270.
5. **Deferred seams** — enable the DAG resolver on live traffic; a stub-LLM + stub-agent mode for load
   testing (a real OpenAI quota was burned); the alias-tail trailing-token trim; widened-window
   extractor-recall leak visibility in the trace; the VT carrier-pinning / perf hardening.
6. **Platform** — the Spring Boot 4 migration (the 3.5 line is OSS-EOL; a Jackson 3 migration validated
   against live SSE is the prerequisite); a one-deployment-per-org tenant model; a standalone
   security-hardening pass.
