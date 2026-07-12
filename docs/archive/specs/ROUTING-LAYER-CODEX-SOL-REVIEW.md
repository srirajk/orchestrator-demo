# Routing-layer implementation review

**Model: SOL**  
**Review type:** adversarial code-level design review; no application implementation performed  
**Reviewed:** `docs/specs/ROUTING-LAYER-IMPL-SPEC.md` against branch `conduit-platform`

## Verdict

**Do not implement the spec as written.** The capability-first direction is correct, and the measured
de-entiting/masking result is strong evidence for the class of fix. The implementation contract is not
yet coherent enough to build safely, however. There are four release-blocking gaps:

1. The proposed grounding list cannot be produced from the current extraction model. `EntityBag` stores
   one string per `extract_as`, with no mention list, message provenance, or offsets
   (`gateway/src/main/java/ai/conduit/gateway/synthesis/input/EntityBag.java:18-33,47-50`). A list-shaped
   `GroundingResult` does not create the missing source mentions.
2. “Mask every user turn in the routing window” is impossible from `ground(preExtracted,
   latestPrompt, ...)`: the grounder sees only the latest string and one conversation-level focal bag
   (`gateway/src/main/java/ai/conduit/gateway/domain/coverage/ReferenceGroundingService.java:73-79`),
   while the routing query is assembled later from independently trimmed messages
   (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1374-1386`).
3. The v1 domain circuit-breaker contradicts partial fulfillment. It would deny a legitimate mixed ask
   whenever its primary domain is fully pruned, although the existing path deliberately serves surviving
   domains and reports fully pruned domains as withheld
   (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:533-556`) and the spec requires
   that behavior (`docs/specs/ROUTING-LAYER-IMPL-SPEC.md:92`).
4. The current reranker cannot supply `{primary, requestedGroups, dagGoal}`. Its output is exactly one id,
   `multiple`, or `abstain` (`gateway/src/main/java/ai/conduit/gateway/resolver/service/RoutingRerankerClient.java:33-46`).
   On `multiple`, the resolver simply keeps every embedding candidate
   (`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:323-332`), including noise.
   There is no requested-group boundary to preserve through authorization.

The narrow bug-261 acceptance test can pass while these gaps remain. That would be a false green: the
system could still substitute a same-domain sibling, regress terse follow-ups, leak an unaligned name into
the embedding, or mishandle a real mixed-domain request.

## Stage 0 — de-entity the corpus

### Right

- Removing client names and typed identifiers from capability examples attacks the demonstrated source
  of corpus contamination. The local manifest changes do remove the named examples implicated in the
  reported route.
- This belongs in registry/manifests, not gateway Java, and is World-B compliant.

### Will break

- Landing corpus changes “with the rest as one unit” makes the 96.3% comparison uninterpretable. Stage 0
  changes the indexed distribution before Stage 2 changes the query distribution. If both land under one
  result, a regression cannot be assigned to corpus normalization, masking, threshold changes, or the
  reranker.
- Several local manifest diffs contain broad JSON reformatting in addition to example changes. That
  expands review surface and makes the claimed “17 examples” harder to audit. The implementation commit
  should isolate semantic example edits.

### Under-specified + required fix

- Produce three frozen measurements against the same model and labeled rows: **A** current corpus/raw
  query, **B** de-entitied corpus/raw query, **C** de-entitied corpus/production-masked query. Record the
  index content hash and embedding model id for each. Do not tune thresholds until A/B/C raw score data is
  saved.
- Add a registry admission check that rejects likely entity-specific examples using manifest-declared
  patterns plus the labeled name-invariance set. Do not put names, prefixes, or type words in Java.

## Stage 1 — grounding foundation

### Right

- `RESOLVED_ALLOWED` is the only defensible signal for relaxing routing abstention. Today syntactic
  presence sets `entityKnown` at
  `gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:446-447`, and the helper returns
  true for a non-null extracted value or an `id_pattern` match without resolution or CHECK
  (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1429-1444`). The spec correctly
  removes that trust escalation.
- Returning all eligible interpretations is necessary. The current store returns the first match from
  `HashMap`-backed subdomains
  (`gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifestStore.java:30-34,365-384`).
- Typed-id match offsets and conservative name alignment are the correct starting point. A span must
  identify user-authored bytes, not merely a resolved entity value.

### Will break

#### P0 — the proposed result shape drops required current states and data

The spec's status set omits `UNAVAILABLE`, but the current lattice fails closed on that state
(`gateway/src/main/java/ai/conduit/gateway/domain/coverage/ReferenceGroundingService.java:101-109`) and
`ChatService` has a distinct terminal path for it
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:415-425`). The proposed item also
omits `denialReason`, which the terminal denial renders at
`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:404-410`. Implementing the literal
shape in the spec either loses fail-closed behavior or forces hidden side channels back into the caller.

Required item fields are at least: reference text, canonical id, manifest entity key/type, subdomain,
coverage contract/interpretation id, status including `UNAVAILABLE`, denial reason, source kind, message
identity, local `[start,end)`, and whether the mention was explicit or carried by anaphora. “No reference”
can be an empty list; unavailability cannot.

#### P0 — multi-name extraction does not exist

The classifier returns one scalar per resolvable `extract_as`
(`gateway/src/main/java/ai/conduit/gateway/domain/intent/IntentClassifier.java:370-410`), and `EntityBag`
copies that into a `Map<String,String>`. “Compare Whitman and Calderon” or two policies of the same entity
type cannot become two grounded name references. Typed regexes can enumerate ids, but names cannot.

Fix the extraction contract first: add a generic list of mention records keyed by manifest entity key,
each with verbatim user text and message identity. Keep the focal scalar as a derived compatibility view
if needed. This remains map/list based and World-B compliant. Do not add one Java field per entity type.

#### P0 — terminal deny on *any* interpretation conflicts with mixed partial fulfillment

With all cross-domain interpretations, one surface name may resolve in an allowed coverage service and
also resolve-but-deny in another. “Terminal DENY on any” makes interpretation breadth an availability
denial. It also terminates a genuine mixed request instead of serving the entitled group, contradicting
the definition of done. A denied reference in a negated or incidental clause (“not policy X; show
settlements for Y”) would also kill the request before capability intent is known.

The aggregation policy must be capability-group aware. Resolve mentions before routing if masking needs
identity, but attach CHECK outcomes to candidate capability groups and apply terminal denial only when the
denied resource belongs to the requested group being disposed. A mixed request needs per-group
ALLOW/DENY/CLARIFY, not one request-global verdict. Until requested groups exist, “deny on any” is safe
against disclosure but functionally wrong.

#### P0 — removing syntactic `entityKnown` regresses the FOLLOW_UP fetch probe

The main fetch path grounds before resolving, but `handleFollowUp` does not. It uses
`hasGroundedResolvableReference` as the admission condition, calls `resolveContextual(..., true)`, and
only then enters `handleFetchData`
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1152-1167`). Deleting the syntactic
helper without redesigning this path strands the documented anaphoric fresh-data case and the terse id
case in `eval/multiturn-routing.json:61-68,81-87`.

Do not preserve the unsafe helper. Introduce one shared pre-routing preparation result used by both
FETCH_DATA and FOLLOW_UP. It must carry grounding outcomes, selected message window, masked text, and the
allowed-relaxation signal. The follow-up path must not resolve/check once for its probe and then again in
`handleFetchData`; pass the preparation/memo through.

#### P1 — “declaration order” is not currently a defined global order

Entity types within one JSON array have order, but subdomain resources are loaded from a classpath glob
and inserted into a `HashMap`
(`gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifestStore.java:110-124`). There is no
cross-file declaration order. Returning all interpretations should make semantic behavior order
independent; a stable sort is only for reproducibility. If precedence is actually needed, it must be a
generic manifest declaration, never a Java domain table.

#### P1 — the latency multiplier is unbounded

The current grounder performs RESOLVE then CHECK serially
(`gateway/src/main/java/ai/conduit/gateway/domain/coverage/ReferenceGroundingService.java:84-95`). Each
coverage call can block for five seconds
(`gateway/src/main/java/ai/conduit/gateway/domain/coverage/CoverageClient.java:85-100,125-147`). With `R`
mentions and `I` interpretations, a naive implementation adds up to `2 * R * I` network calls and can
consume roughly `10 * R * I` seconds sequentially. The existing memo repays only the one routed
subdomain's downstream call; it does not make multi-interpretation grounding free
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:621-633`).

### Under-specified + required fix

- Define maximum mention count, maximum interpretations per mention, deduplication key, bounded
  concurrency, stage deadline, cancellation, and deterministic aggregation when one coverage service is
  unavailable. Deduplicate identical `(coverage endpoint, resolve_type, reference)` resolves and batch
  CHECK if the coverage contract supports it.
- Cache compiled, schema-validated regexes at manifest load. The spec says offsets are “already compiled,”
  but both current paths call `Pattern.compile` on request
  (`gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifestStore.java:340-344` and
  `gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1403-1407`).
- Specify overlap and duplicate interpretation semantics. The output must be deterministic independent of
  map/resource iteration and network completion order.
- Add tests for two names of one type, two ids, one surface name valid in two domains, allow+deny across
  interpretations, denial in a negated clause, one unavailable interpretation, and FOLLOW_UP fetch
  fallthrough. Current grounding tests exercise only one mocked reference at a time
  (`gateway/src/test/java/ai/conduit/gateway/domain/coverage/ReferenceGroundingServiceTest.java:69-79,225-241`).

## Stage 2 — query masking

### Right

- Mask only a reference that deterministically resolved; never mask based on NER syntax or a string that
  merely resembles a short name.
- Use a config-driven, domain-neutral replacement and feed the same prepared text to vector search and
  reranking. The current reranker receives the resolver's query text directly
  (`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:252-258,323-324`), so one
  shared value can enforce this.
- Reverse-offset replacement is necessary within one immutable string.
- Entity-only input should not fall back to raw entity text. It contains no capability evidence.

### Will break

#### P0 — spans are in the wrong coordinate space

The grounder receives `latestPrompt`; `buildRoutingQuery` later selects multiple message objects, trims each
one, reverses them, and inserts newlines
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1374-1386`). A latest-message offset
cannot safely index that flattened text, and there are no offsets for older turns. Applying offsets after
flattening can mask the wrong characters.

Keep spans local to a stable message identity and original content. Select the routing window first, mask
each selected user message independently, then join. Never translate offsets through `trim()` and newline
concatenation.

#### P0 — the current focal reference is often intentionally absent from the latest turn

The classifier is instructed to carry a prior user reference for anaphora or an entity-less fresh-data
follow-up (`gateway/src/main/java/ai/conduit/gateway/domain/intent/IntentClassifier.java:84-99`). The
deterministic focal derivation can return a prior id/name
(`gateway/src/main/java/ai/conduit/gateway/domain/intent/IntentClassifier.java:484-508`). Literal alignment
against only the latest prompt must fail in exactly the multi-turn cases masking is meant to support.

Grounding needs mention provenance, not just focal-entity provenance. A carried reference may authorize
the routing relaxation, but it has no latest-turn span; mask its actual prior mention if that turn is in
the chosen window.

#### P0 — fail-open-on-alignment-miss is not safe for routing correctness

It is memory-safe and avoids masking an arbitrary word, but it reintroduces the class defect: the raw
entity remains in the embedding and reranker input. The post-prune guard may catch some substitutions, but
same-domain substitution and pre-prune shortlist exclusion remain possible.

On miss, leave the text unchanged **and mark masking incomplete**. Incomplete masking must retain normal
score/margin abstention (no `entityKnown` relaxation based on that mention) and must be a required eval
slice. For a known bug-class conflict, prefer capability clarification/abstention over trusting the raw
entity-dominated route. “Fail open” must not mean “behave exactly like a fully masked, trusted route.”

#### P1 — reverse order does not solve overlaps or duplicates

A typed id can sit inside a larger extracted mention; the same name can appear twice; normalized
case/punctuation matching can map several candidates; spans from different interpretations can be
identical. Reverse replacement alone can double-mask or corrupt text. Normalize intervals first: validate
bounds against the original message, merge exact duplicates, reject conflicting overlaps, define whether
all repeated mentions of the same resolved id are masked, then replace descending by start.

#### P1 — “case/punct/possessive tolerant” is not an exact-span algorithm

Unicode apostrophes, hyphens, combining characters, whitespace runs, and case folding can change normalized
length. The implementation needs a normalization-to-original index map. The current focal validation is
much weaker than literal equality: sharing one distinctive word is sufficient
(`gateway/src/main/java/ai/conduit/gateway/domain/intent/IntentClassifier.java:476-483,521-535`). Such an LLM
value may not be a contiguous source span at all.

### Under-specified + required fix

- Replace `routingTextForFetch`'s boolean interface with a preparation object. Today context selection
  changes materially by `entityKnown`: latest only for a name, two turns for a typed id, full configured
  window otherwise (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1389-1398`).
  Specify the order as: derive focal mention and trusted grounding -> choose bounded turns -> ground/mask
  mentions in those turns -> determine residual/action policy -> embed/rerank.
- Define “near-empty” without a domain word list. Character count is not adequate; neither is a Java
  precedence/type table. Use a generic tokenizer/configured stopword policy plus tests, or represent the
  requested capability groups before masking. “Only reuse prior action text” is currently not derivable:
  messages carry raw strings, not action spans.
- Neutrality must be measured, not asserted. For each candidate replacement (including deletion), run
  paired variants over every capability: at least 10 unrelated names/ids per query, possessive and
  punctuation forms, repeated names, and multi-turn variants. Report top-1 capability, top-k recall,
  score/margin deltas, name-invariance, wrong-domain substitutions, and empty-residual behavior. Select on
  calibration data and freeze the token per embedding-model/index version. A token is “acceptable,” not
  mathematically neutral.
- Add trace fields for mask mode, number of grounded mentions, masked spans, alignment misses, residual
  class, and a hash/length of prepared text. Do not log new raw entity values merely to debug masking.

## Stage 3 — post-prune capability continuity

### Right

- The bug is real. Once at least one agent survives `filterAgents`, the current code proceeds with the
  survivors (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:521-542`) without
  proving that the requested capability survived.
- A stable pre-authz intent artifact is the right abstraction. Authorization must prune fulfillment, not
  retroactively redefine what the user asked.
- Coverage denial copy and structural/capability denial copy are different contracts. Current
  `denial_messages` maps coverage reason codes
  (`gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifestStore.java:276-308`), while the
  all-pruned structural response is hardcoded
  (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:522-530`). A new key in the existing
  generic `messages` map is the correct World-B location; that map already accepts arbitrary string keys
  (`registry/sub-domain-manifest.schema.json:56-59`).

### Will break

#### P0 — do not ship the v1 domain circuit-breaker

For a genuine two-domain request, the reranked leader is necessarily in only one domain. If that domain is
fully pruned and the other requested domain survives, v1 denies everything. That is the exact opposite of
the current partial-withholding contract and the spec's mixed-request definition of done. Existing tests
explicitly require only the fully pruned domain to be withheld
(`gateway/src/test/java/ai/conduit/gateway/domain/chat/ChatServiceWithheldScopingTest.java:42-63`).

The domain check also false-passes when an unrelated same-domain sibling survives. Therefore v1 is neither
safe nor a useful independently shippable stage. Build capability groups first.

#### P0 — `primaryCandidate` alone is insufficient and sometimes undefined

After rerank pick, `candidates.get(0)` is indeed the final leader
(`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:340-351,258-260`). Populate a
leader there if it is useful for diagnostics. But:

- `multiple` returns no ids and preserves the noisy candidate set
  (`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:323-332`).
- `abstain` has no primary.
- The dynamic floor can select multiple capabilities from one or many domains
  (`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:289-300`).
- `ResolverResult` currently records only selected/skipped and scalar routing diagnostics
  (`gateway/src/main/java/ai/conduit/gateway/resolver/model/ResolverResult.java:15-33`).

Consequently `{primary, requestedGroups, dagGoal}` is not derivable from current output. Do not infer
requested groups from every floor-passed candidate; that converts embedding noise into user intent.

Change the bounded reranker decision to return `single(candidate_id)`, `multiple([candidate_id...])`, or
`abstain`, validate every id against the shortlist, and require each returned id to correspond to a
distinct requested facet. Preserve embedding scores separately. The LLM is a selector within a bounded
manifest-derived set, not an authorization oracle.

#### P0 — the current multi-domain coverage path invalidates the durable design

After structural pruning, `ChatService` finds only the **first** resource-scoped survivor, derives one
effective manifest and one coverage entity, and performs one coverage pipeline
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:584-614`). It then injects one
canonical id into one shared bag before synthesizing all final manifests
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:789-814`). A real request containing
different resource types/domains cannot be correctly checked and bound by this structure.

The continuity artifact must be per requested group and must carry its required entity/grounding/check
state. Process structural auth, coverage, binding, execution, and withheld reporting per group, then merge
the allowed groups. Without that refactor, the mixed insurance+servicing DoD is not a valid test of the
proposed guard.

#### P0 — the DAG path can silently fall back after authorization removes a producer

The current DAG goal is chosen only after structural filtering and input synthesis
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1009-1020`). Producers are introduced
later by `DagResolver`. If producer re-gating prunes anything, `tryDag` returns empty and the caller falls
back to the flat path
(`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1043-1053`). That loses the fact that
the requested DAG capability cannot be fulfilled and can execute adjacent flat survivors instead.

For a requested DAG group, derive the goal and producer closure before final disposition. If a required
producer is structurally or coverage denied, mark that group withheld/unfulfillable; do not silently flat
fallback. Technical resolution misses may still use an explicitly defined fallback, but authorization
prune is not a technical miss.

### Under-specified + required fix

- Define a routing intent artifact such as `RequestedPlan(groups[])`, where each group has bounded
  requested capability ids, flat/DAG kind, optional goal id, producer closure, required entity keys, and
  routing evidence. “Primary” is diagnostic metadata on a group, not the fulfillment contract.
- Define partial semantics: all groups denied -> structural denial; some groups denied -> execute allowed
  groups and report denied group copy; required producer denied -> deny that DAG group; optional/noise
  candidate denied -> no user-visible denial.
- Define copy lookup when the primary is pruned. The proposed `primaryCandidate(agent_id + domain)` does
  not itself carry subdomain even though copy selection is subdomain-scoped. Derive the effective manifest
  from the retained pre-authz candidate and use a required generic message key; do not fall back by
  `HashMap` iteration (`gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifestStore.java:250-273`).
- Test: same-domain primary pruned/sibling survives; mixed requested groups with primary denied; noisy
  secondary domain pruned; reranker `multiple`; primary below dynamic floor; DAG goal denied; producer
  denied; and producer unavailable. Assert invoked agent ids and absence of facts from withheld groups, not
  just response text.

## Stage 4 — eval as the release gate

### Right

- The release gate must measure the production-prepared query. The current harness calls raw
  `/debug/resolve` (`eval/goal-pick/measure_goal_pick.py:94-102`), and the controller calls `resolver.resolve`
  directly (`gateway/src/main/java/ai/conduit/gateway/api/v1/admin/DebugResolverController.java:32-52`). It
  exercises neither context selection, grounding, masking, contextual confidence, structural pruning, nor
  coverage.
- Per-row personas are required. The dataset already has a persona on every row, but the harness mints one
  admin token and reuses it (`eval/goal-pick/measure_goal_pick.py:82-91,303-325`).
- Exact capability accuracy, name invariance, wrong-domain substitution zero, and out-of-scope abstention
  are the right headline gates. Domain accuracy alone is too lenient for this defect.

### Will break

#### P0 — an optional `/debug/resolve` masking flag creates a test-only fork

A flag bolted onto the current prompt-only GET cannot reproduce production. Grounding needs an authenticated
principal, tenant, caller token, entity extraction, and message window; masking needs message-local spans;
post-prune disposition needs authorization. Pre-masking in Python is worse: it tests a second masking
implementation and cannot validate alignment failures.

Extract one shared, side-effect-free pre-routing preparation component used by chat and a test-profile
decision endpoint. The endpoint should accept the same message DTO and authenticated principal, stop
before agent invocation, and return preparation mode/version, grounded statuses, masked routing text or
safe diagnostics, resolver decision, requested groups, and post-auth disposition. The harness must assert
`path == production` and the expected masking version. Keep raw resolver measurement as a separate
diagnostic, never as the release score.

#### P0 — the current gate does not gate the advertised metric

The harness computes overall exact-agent accuracy at
`eval/goal-pick/measure_goal_pick.py:224-240`, but pass/fail checks only domain accuracy, abstain rate, and
canonical poaching (`eval/goal-pick/measure_goal_pick.py:275-299`). It can print exact accuracy below 96.3%
and still exit zero. `cross_agent_confuser` is excluded from `clear_categories`
(`eval/goal-pick/measure_goal_pick.py:208-217`). Both must change before calling this a gate.

### Under-specified + required fix

Use this concrete re-baseline procedure:

1. Freeze model id, registry snapshot/index hash, reranker model/config, and dataset revision.
2. Group-split query families into calibration and held-out sets so name variants and paraphrases of one
   seed cannot land on both sides. Stratify by capability, persona, single/multi-group, flat/DAG,
   out-of-scope, and alignment outcome.
3. Save A/B/C score vectors from Stage 0. Add at least `capability_entity_conflict`, `name_invariance`,
   multi-reference, same-name-cross-domain, alignment-miss, entity-only, and multi-turn/facet-carry rows.
4. Grid-search the four thresholds **jointly** on calibration data. They interact: `domain-margin` gates
   before `select`, `min-score/min-margin` gate inside `select`, and `confidence-floor` controls selected
   fan-out (`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:212-230,261-300`).
   Include rerank trigger thresholds in the recorded configuration even if not tuned.
5. Apply hard constraints first: wrong-domain substitution = 0, out-of-scope abstain = 100%, no denied
   group invocation, and minimum per-capability recall. Among feasible settings maximize held-out exact
   capability/group accuracy and choose a stable plateau, not the single best point.
6. Freeze thresholds, run held-out once for the release decision, and report counts plus confidence
   intervals. “>=96.3%” must name numerator/denominator; a percentage without sample size is not a robust
   non-regression claim.
7. Run production-path and raw-path suites separately. Production is the hard gate; raw results diagnose
   whether masking is hiding a degrading base retriever. Also gate shortlist/top-k recall because the
   reranker cannot recover an absent capability.

Token acquisition must cache per persona, not mint per row. A row's expected outcome must include whether
that persona is structurally entitled and covered, so routing correctness is not conflated with expected
denial.

## Stage 5 — reranker and World-B audit

### Right

- The masked query and de-entitied examples must be the only semantic text the reranker sees. Today the
  reranker includes up to two examples per skill
  (`gateway/src/main/java/ai/conduit/gateway/resolver/service/LlmRoutingRerankerClient.java:72-95`), so Stage
  0 affects both embedding and LLM selection.
- Preserving margin abstention on reranker failure is correct. Current `embeddingFallback` suppresses it
  (`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:352-356,393-395`).
- Measuring top-k recall is essential.

### Will break

- “Leader domain != grounded reference domain” is not evidence that the leader is wrong. Entity ownership
  and requested capability domain are deliberately different in bug-261 and in legitimate cross-domain
  requests. With multiple eligible grounding interpretations, “the reference's domain” is a set, not one
  value. Use this only as a conservative rerank/abstention trigger and latency signal, never as a denial or
  correctness rule.
- A conflict-triggered reranker cannot fix shortlist exclusion. If the right capability is outside
  `rerankMaxCandidates`, it never reaches the LLM
  (`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:320-324`). Define a minimum
  top-k recall gate before enabling the trigger.
- The current reranker `multiple` result contains no selected ids, so it cannot produce durable groups.
  Stage 3 and Stage 5 must share one expanded bounded output contract; implementing them independently
  would create two incompatible notions of “requested capability.”

### Under-specified + required fix

- Add an explicit fallback mode to `RerankApplication`: ordinary error may follow the existing policy if
  separately justified, but conflict-path error/invalid id must keep margin abstention. Do not encode this
  as another ambiguous boolean.
- Validate `multiple([ids])`: ids must be in the shortlist, unique, nonempty, and capped. If invalid or the
  model errors, abstain on the conflict path. Preserve the reason only for diagnostics; never infer policy
  from it.
- Measure reranker determinism/retry behavior and added p95/p99 latency. The LLM request timeout is eight
  seconds (`gateway/src/main/java/ai/conduit/gateway/resolver/service/LlmRoutingRerankerClient.java:30-46,112-122`).

### World-B findings

- The proposed Java mechanics can remain World-B clean if they traffic only in manifest keys, agent ids,
  domain/subdomain ids, generic status enums, offsets, and score arithmetic. A config-driven mask token is
  acceptable; an entity-type replacement word is not.
- Do **not** implement a Java precedence table for ambiguous reference interpretations. “Declaration
  order” must not become a disguised domain/type ordering. Return all interpretations and make aggregation
  order independent.
- Do **not** add a Java enum of domain names, entity types, id prefixes, or per-domain structural-denial
  keys. Put user copy in the existing subdomain `messages` contract and require the generic key in manifest
  validation.
- The spec should explicitly ban using `EntityType.display`, `resolveType`, or `key` as the mask token.
  Those are manifest-derived but still inject domain vocabulary into the semantic query.
- The existing source already contains generic fallbacks and some domain-shaped defaults; this review does
  not authorize expanding them. In particular, new failure copy should not follow the hardcoded all-pruned
  pattern at `gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:528`.

## Missing failure modes the spec must add

1. **Multi-reference same type:** two names/ids for one `extract_as`; comparison and “X vs Y.”
2. **One mention, multiple interpretations:** allow+deny, allow+not-found, unavailable+allow, and duplicate
   canonical ids across coverage services.
3. **Negation/incidental entity:** a denied entity named only in an excluded clause must not terminate an
   unrelated requested group.
4. **Repeated/overlapping spans:** duplicate name, name plus id, substring names, Unicode punctuation,
   normalization length changes, and span after message trimming.
5. **Anaphora outside the bounded routing window:** a carried focal id may be groundable but have no
   maskable mention in the selected turns. Specify clarify/abstain behavior.
6. **Alignment-miss adversarial names:** miss must not receive routing relaxation or silently pass the same
   path as fully masked text.
7. **Entity-only and entity-plus-stopwords:** deterministic residual classification, including non-English
   or punctuation-only turns if supported.
8. **Capability absent from top-k:** post-prune continuity cannot protect a capability the retriever never
   surfaced.
9. **Same-domain substitution:** primary denied, sibling survives; the domain circuit-breaker misses it.
10. **Mixed requested vs noisy domains:** deny reporting must be based on requested groups, not every
    selected embedding candidate.
11. **DAG producer denial/unavailability:** never flatten silently after an authorization prune.
12. **Multiple resource-scoped groups:** distinct entity types and coverage services in one request; no
    reuse of the first group's id or coverage memo.
13. **Coverage latency/deadline exhaustion:** bounded fan-out, cancellation, partial interpretation
    failures, and no sequential `R * I * 10s` tail.
14. **Stale index/model/token calibration:** mask-token acceptance and thresholds must be tied to the
    embedding model and index content hash.
15. **Debug/prod drift:** the eval endpoint must prove which shared preparation version ran.

## Prioritized spec changes required before implementation

### P0 — block implementation until resolved

1. Replace the scalar extraction/grounding contract with generic mention records carrying message-local
   provenance and offsets; retain all current verdict data including `UNAVAILABLE` and denial reason.
2. Define a single shared pre-routing preparation pipeline used by FETCH_DATA, FOLLOW_UP, and the eval
   decision endpoint. It owns window selection, grounding, masking, residual disposition, and the routing
   relaxation signal.
3. Remove the v1 domain circuit-breaker from the plan. Define requested capability groups first and expand
   the bounded reranker output to return explicit ids for `multiple`.
4. Reconcile request-global terminal deny with mixed partial fulfillment. Authorization and coverage
   outcomes must attach to requested groups; “deny on any interpretation” cannot coexist with the stated
   DoD.
5. Refactor the design, not necessarily yet the code, around per-group resource coverage. The existing
   first-resource-scoped-agent pipeline cannot satisfy the mixed-domain criterion.
6. Make DAG goal/producer continuity part of the requested group before final authorization. An authz
   producer prune must never become flat fallback.
7. Replace the debug flag/pre-mask options with a shared production decision path and change the harness
   exit criteria to gate exact capability/group accuracy and confusers.

### P1 — specify before coding the relevant stage

8. Define span normalization, duplicate/overlap resolution, alignment-miss semantics, and message-local
   coordinate handling.
9. Define grounding budgets: max mentions/interpretations, deduplication, bounded concurrency, deadline,
   unavailable aggregation, and metrics.
10. Define and execute the A/B/C threshold calibration protocol with grouped held-out data and frozen
    model/index/config identifiers.
11. Define the neutral-token experiment and acceptance criteria; do not bless a token by intuition.
12. Add the generic structural/capability-unavailable message key to every applicable subdomain manifest
    and validation, with no Java domain copy.

### P2 — required for the green commit

13. Add unit/integration/eval cases for every missing failure mode above, including the existing
    `multiturn-routing.json` conversation as a production-path regression.
14. Gate top-k recall, p95/p99 preparation latency, wrong-domain substitution zero, denied-group
    non-invocation, out-of-scope abstention, and exact capability/group accuracy.
15. Run the full gateway suite and `scripts/world-b-check.sh`; report the actual before/after CRITICAL
    counts. The checker being “stale” is not a reason to waive manual World-B review.

## Bottom line / disagreement to carry into reconciliation

The spec treats grounding, masking, and post-prune continuity as mostly local extensions to existing
records. The code says otherwise. Multi-reference masking requires a mention/provenance model; correct
mixed-domain denial requires a requested-group model and per-group coverage; correct DAG continuity
requires preserving the goal and producer closure before authorization. Those are control-flow changes,
not fields added to `GroundingResult` and `ResolverResult`.

I agree with **de-entitied corpus + resolved-span masking + capability continuity + production-path eval**.
I disagree with shipping the v1 domain breaker, with request-global “deny on any” in a partial-fulfillment
system, with fail-open alignment receiving trusted routing relaxation, and with claiming requested groups
are derivable from the current reranker. Reconcile those four points before implementation starts.
