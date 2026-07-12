# Routing Precision Independent Verification

**Model:** SOL (Codex, GPT-5)  
**Verdict:** **Needs changes.** The design identifies the correct failure class and has the right broad layers, but P0 is too coarse to deserve the name “capability honesty,” P2 relies on an unreliable single “entity domain,” and the current eval gate is materially less protective than the brief claims.

## Executive conclusion

Bug-261 is real and structurally reproducible from the checked-in code. A grounded entity can relax both routing abstain gates; semantic routing can select both the requested asset-servicing capabilities and entity-anchored insurance capabilities; structural authorization then removes asset servicing; and the pipeline deliberately synthesizes from whatever other domain survived. The existing `WITHHELD` instruction makes the answer disclose the missing domain, but it also explicitly tells the model to “fulfill the part you can,” even when that “part” is only a routing false positive (`ChatService.java:521-556`; `AnswerSynthesizer.java:133-139`). That is response-honesty failure, not a data leak.

The proposed layering is directionally right:

1. Add a deterministic post-authorization intent-preservation guard immediately.
2. Remove instance names and IDs from the routing corpus and rebuild the index/thresholds.
3. Route on entity-masked action text using structured grounding evidence.
4. Use the reranker only as a precision aid, never as the safety boundary.
5. Replace the routing-only aggregate eval gate with adversarial invariance, conflict, and persona-scoped end-to-end gates.

However, I would not ship P0 exactly as written. “At least one survivor from the leader's domain” proves only domain continuity, not capability continuity. The deterministic invariant should be expressed against an explicit pre-authorization routing target (flat primary capability or DAG goal), with domain continuity retained as an initial containment heuristic while that richer decision artifact is added.

## 1. Verification of the six code facts

### Fact 1 — PARTLY RIGHT

**Confirmed:** the registry creates an HNSW cosine index and stores one vector document per manifest example. `VectorIndexWriter.index()` obtains `manifest.allExamples()`, embeds the list, and writes keys `vec:{agent_id}:{i}` one by one (`gateway/src/main/java/ai/conduit/gateway/registry/index/VectorIndexWriter.java:96-126`). `AgentManifest.allExamples()` is the unmodified concatenation of every skill's `examples` strings (`gateway/src/main/java/ai/conduit/gateway/registry/model/AgentManifest.java:221-226`). The index is explicitly HNSW/COSINE (`VectorIndexWriter.java:175-201`). Query search embeds the supplied text, performs KNN, converts cosine distance to similarity, and keeps the maximum example score per agent (`gateway/src/main/java/ai/conduit/gateway/registry/index/VectorIndex.java:77-116`).

**Confirmed:** routing has several score gates, not just one. The effective configuration is `confidence-floor: 0.30`, `domain-margin: 0.05`, `routing.min-score: 0.40`, and `routing.min-margin: 0.005` (`gateway/src/main/resources/application.yml:127-150`). The Java field default for confidence floor is 0.35, but the checked-in YAML overrides it to 0.30 (`AgentResolver.java:33-38`). The general minimum score is 0.40 (`AgentResolver.java:63-80`).

**Confirmed:** an LLM reranker fires for near ties or leaders adjacent to the abstain floor (`AgentResolver.java:313-367`). It receives the query plus manifest name, description, score, skills, and up to two raw examples per skill (`LlmRoutingRerankerClient.java:49-110`).

**Correction:** “RAW query” is accurate for `/debug/resolve`, which passes the request prompt directly to `resolver.resolve()` (`gateway/src/main/java/ai/conduit/gateway/api/v1/admin/DebugResolverController.java:32-53`; `AgentResolver.java:121-130`). It is not consistently accurate for chat. Chat builds `routingText` from the current turn, the last two user turns, or a configured recent-user-turn window depending on context and entity state (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:1362-1398`) and passes that to `resolveContextual()` (`ChatService.java:446-450`). The contamination problem still applies because the resulting text contains the unmasked named entity.

### Fact 2 — RIGHT

“Continental Freight” occurs in three insurance example corpora:

- policy details (`registry/manifests/insurance/meridian.insurance.policy_details.json:35-40`),
- claim status (`registry/manifests/insurance/meridian.insurance.claim_status.json:35-40`), and
- renewal risk (`registry/manifests/insurance/meridian.insurance.renewal_risk.json:36-44`).

Settlement status uses “Whitman,” not Continental Freight (`registry/manifests/asset-servicing/meridian.servicing.settlement_status.json:35-44`), while settlement risk has no instance name (`registry/manifests/asset-servicing/meridian.servicing.settlement_risk.json:36-42`). Because agent scoring is the maximum similarity over its individual example vectors (`VectorIndex.java:99-114`), a single contaminated example can dominate an agent's score. This makes per-example contamination more dangerous than it would be under mean pooling.

One nuance: the corpus is broadly instance-contaminated, not simply “each entity anchors exactly one domain.” “Whitman” appears in both wealth and asset-servicing examples. Continental Freight is the especially clean one-domain anchor responsible for this bug.

### Fact 3 — RIGHT, WITH A MORE SERIOUS NUANCE

Reference grounding is pre-routing. It chooses a typed-ID match first, otherwise an LLM-extracted reference, then performs RESOLVE and CHECK (`gateway/src/main/java/ai/conduit/gateway/domain/coverage/ReferenceGroundingService.java:61-105`). Chat executes it before routing and memoizes an allowed result (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:385-432`).

An allowed grounding makes `entityKnown=true` (`ChatService.java:442-450`). In `resolveContextual`, `entityKnown` bypasses the domain-margin/decisive-score branch while retaining only the lower per-candidate confidence floor (`gateway/src/main/java/ai/conduit/gateway/resolver/service/AgentResolver.java:196-230`). It also bypasses the 0.40 absolute minimum and 0.005 top-two margin abstain gate (`AgentResolver.java:261-287`). Therefore the claim is correct: naming a groundable entity makes routing more credulous.

The nuance is worse than the brief states. `entityKnown` is also true on a context-carrying turn when `hasGroundedResolvableReference()` merely finds an extracted reference or matching ID pattern (`ChatService.java:446-447`, `1429-1444`). That predicate does not prove that the reference was uniquely resolved and CHECK-allowed. Thus an AMBIGUOUS or NOT_FOUND Stage-1 outcome can still receive the same routing relaxation through the second branch. Replace the boolean with structured grounding status; do not infer “known” from mere syntactic presence.

### Fact 4 — RIGHT

Chat converts the selected routing candidates to manifests, emits structural gate traces, and calls `entitlementService.filterAgents()` (`gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java:505-521`). It terminates only when **all** manifests are pruned (`ChatService.java:522-531`). If any candidate survives, it continues with those survivors (`ChatService.java:533-542`), computes fully missing domains as `selected domains - served domains` (`ChatService.java:544-556`, `1757-1763`), binds inputs, and invokes the surviving agents (`ChatService.java:789-872`). There is no check that the surviving candidates still implement the capability the user asked for.

The answer layer makes the behavior explicit: when a domain is withheld, it must state the omission but also “fulfill the part you can” (`gateway/src/main/java/ai/conduit/gateway/synthesis/answer/AnswerSynthesizer.java:133-139`, `332-341`). That is appropriate for a real multi-domain request and unsafe for a single-capability request whose lower-ranked survivors were false positives.

There is a related DAG version. A selected and allowed fan-in goal can pull producers from the registry, re-gate them, and fall back to the flat path if any dependency is pruned (`ChatService.java:1009-1053`). A flat fallback can similarly return adjacent raw capabilities even though the intended analytical goal could not be executed. The intent-preservation rule must cover both the first structural prune and DAG re-gate fallback.

### Fact 5 — RIGHT

`identifyByReference()` scans `subDomains.values()` and returns immediately on the first required, resolvable entity whose `extract_as` key is present (`gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifestStore.java:365-384`). `subDomains` is a `HashMap` populated from resource discovery (`DomainManifestStore.java:30-34`, `110-135`). HashMap iteration order is not an ownership rule or stable semantic contract.

This matters now: `relationship_reference` is declared by wealth private banking, asset-servicing cash management, and asset-servicing custody operations (`registry/domains/wealth-management/private-banking.json:8-17`; `registry/domains/asset-servicing/cash-management.json:8-17`; `registry/domains/asset-servicing/custody-operations.json:8-17`). The function does not actually identify “the entity's domain”; it chooses one candidate namespace by incidental iteration order.

The failure modes are more severe than nondeterminism alone:

- a wrong namespace can return NOT_FOUND, after which the code does not try the other eligible namespaces;
- a same-name record in the wrong namespace can resolve and produce a wrong coverage denial; and
- a single returned `subDomainId` cannot model a legitimate entity shared across several domains.

P2 must not treat this first match as authoritative “grounded entity domain.” Grounding should return all eligible manifest-derived reference interpretations, resolve them deterministically, and preserve zero/one/many matches.

### Fact 6 — MOSTLY RIGHT; THE EVAL IS EVEN WEAKER THAN CLAIMED

The harness calls `/debug/resolve` (`eval/goal-pick/measure_goal_pick.py:94-103`), and that endpoint explicitly routes without invoking agents (`DebugResolverController.java:14-20`). It does not execute chat's contextual routing, Stage-1 grounding, structural entitlement prune, coverage handling, DAG fallback, or answer synthesis.

The dataset contains 73 queries: 18 canonical, 18 held-out, 18 near-miss, 11 cross-agent confusers, and 8 out-of-scope. There is no capability/entity conflict case such as “settlements for Continental Freight.” The named cases keep entity and requested capability aligned: Continental Freight is used only for insurance renewal (`eval/goal-pick/labeled_queries.json:79-84`, `351-355`); Okafor/REL-00188 is used for servicing (`labeled_queries.json:86-132`, `224-275`); and Whitman/Calderon cases request wealth capabilities (`labeled_queries.json:8-48`, `135-173`).

The inflation claim is directionally correct but overstated. Exact corpus-name reuse exists for Whitman and Continental Freight, and exact corpus-ID reuse exists for POL-77001 and CLM-5501. However, Okafor and Calderon do not occur in the manifest examples, and REL-00188 does not occur there. The set therefore includes some name generalization, but it never crosses an entity signal with a conflicting capability label. It can measure paraphrase generalization while remaining structurally blind to bug-261.

Additional eval defects:

- `persona` is metadata only. The loop always mints/uses one admin token and never selects credentials from each row's persona (`measure_goal_pick.py:303-325`). Persona-scoped authorization is not tested.
- The release gate's “clear” set excludes all 11 `cross_agent_confuser` rows (`measure_goal_pick.py:208-217`). Those rows appear in printed misses, but the pass/fail condition uses only clear-set domain accuracy, out-of-scope abstain, and canonical poaching (`measure_goal_pick.py:275-299`).
- Exact-agent accuracy is printed but is not a gate (`measure_goal_pick.py:224-240`, `287-299`). A router can pass on 90% domain accuracy while choosing the wrong capability within that domain.
- The current “confusion list” is only a list of misses, not a full confusion matrix or per-agent precision/recall (`measure_goal_pick.py:266-273`).
- Because `/debug/resolve` calls plain `resolve()`, it does not exercise the contextual domain-margin gate or either `entityKnown` bypass that is central to this bug (`DebugResolverController.java:32-53`; `AgentResolver.java:121-130`).

## 2. P0 — post-prune capability honesty

### Is it the right top priority?

**Yes as immediate deterministic containment; no as the primary routing-precision fix.** P0 is the only proposed layer that guarantees the exact observed failure cannot silently turn into another domain's answer even if embeddings and the reranker remain wrong. It belongs first in rollout order. P1 is the primary root-cause precision fix because it removes the contaminating feature from the corpus.

### Exact definition of “leader's domain fully pruned”

For the proposed coarse guard, define it from an immutable pre-authorization routing decision:

```text
effectiveLeader = the resolver's final primary candidate after any reranker decision,
                  before entitlement filtering
D0              = effectiveLeader.manifest.domain
R0              = all confidence-selected manifests whose manifest.domain == D0
A0              = R0 intersect allowedManifests, by agent_id
fullyPruned(D0) = R0 is non-empty AND A0 is empty
```

Do not recompute the leader from `allowedManifests`, do not use map iteration, and do not use the grounded entity's domain. Preserve a `primaryCandidateId`/`primaryDomain` in `ResolverResult`; relying implicitly on list position is fragile because the reranker reorders candidates (`AgentResolver.java:340-351`) while `ResolverResult` currently carries no explicit leader field (`gateway/src/main/java/ai/conduit/gateway/resolver/model/ResolverResult.java:15-23`).

This definition does **not** break the example in the brief: if the leader's domain is entitled, `A0` is non-empty, so the guard permits all entitled requested domains to continue. If a second legitimately requested domain is fully pruned, the existing withheld-domain behavior can report that partial result.

### Why this is still too coarse

Domain continuity is not capability continuity. If the user asks for an analytical capability and that agent is pruned while an unrelated lower-classification agent in the same domain survives, `A0` is non-empty and the proposed P0 still returns the wrong capability. This is plausible in the current corpus: settlement status and settlement risk share a domain but have different classifications (`registry/manifests/asset-servicing/meridian.servicing.settlement_status.json:53-57`; `registry/manifests/asset-servicing/meridian.servicing.settlement_risk.json:52-55`).

The stronger generic invariant is:

- **Flat single-capability route:** the explicit primary agent must survive. Do not substitute a lower-ranked agent merely because it shares the domain.
- **DAG route:** the intended goal must survive, and every required producer must survive the re-gate. If not, do not flat-fallback into a different answer unless the user independently requested those producer capabilities.
- **True multi-capability route:** preserve a set of requested capability groups. Fulfill surviving groups and report denied groups. Do not terminate the whole response merely because the numerically first group was denied.

The current resolver cannot represent that last distinction. The reranker returns one id, `multiple`, or `abstain`; `multiple` simply retains the original candidate set (`AgentResolver.java:323-338`). It does not identify which candidates correspond to the distinct requested clauses. Therefore a domain-of-leader guard is a defensible emergency circuit breaker, but the durable design needs a generic routing decision artifact with `primary`, `requestedGroups`, and optional `dagGoal`, all populated from manifests and query evidence without domain literals.

### Denial copy

The design says to emit “the manifest structural-denial copy,” but no such manifest contract currently exists. Sub-domain `denial_messages` are coverage reason messages (`gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifestStore.java:276-308`), while the all-pruned structural response is hardcoded generic text (`ChatService.java:522-530`). Do not misuse a coverage message such as “not in your book” for a Cerbos structural denial. Add a generic manifest message key specifically for unavailable/unauthorized requested capability, or retain a genuinely domain-neutral platform message. Selection of the copy may use the primary candidate's manifest/sub-domain; the gateway must not contain domain wording.

## 3. P1 — de-entity the corpus

### Is it the right primary fix?

**Yes, for routing precision.** The index's unit and aggregation make this the direct World-B lever: examples are manifest-owned, embedded individually, and max-pooled per agent (`VectorIndexWriter.java:96-126`; `VectorIndex.java:99-114`). Removing instance names and literal IDs stops onboarding data from teaching the router that a customer name is a capability feature.

Rewrite all business-instance names and IDs, not just Continental Freight. Preserve action-bearing language. “Pending settlements for this relationship” is acceptable, but corpus quality should include several neutral constructions so every agent is not reduced to one identical boilerplate suffix. A manifest schema/lint should reject or warn on undeclared instance literals. If automated normalization is added, its rules must be driven by manifest annotations/id patterns, not Java lists of known client names or prefixes.

Rebuild the entire vector index and re-baseline all four thresholds after the corpus change. The current YAML comments explicitly say 0.40 and 0.005 were selected from the existing labeled distribution (`gateway/src/main/resources/application.yml:141-150`); those numbers have no validity after changing the embedding corpus.

### Does eval name reuse inflate the result?

**Partly.** Whitman and Continental Freight appear in both eval and corpus; POL-77001 and CLM-5501 also appear in both. These rows can benefit from exact lexical anchoring. But Okafor, Calderon, and REL-00188 are not corpus examples, so it is not accurate to call the entire seed contaminated by exact name reuse. The more important defect is pairing: every entity-bearing query requests a capability consistent with the entity's learned/home domain. Even the novel names therefore cannot expose action-vs-entity conflict.

### Collision metric: per-example or centroid?

Use **per-example**, not centroid, as the primary lint. The production index retrieves individual examples and takes the best hit per agent; a centroid would hide the exact outlier that can poach a query. However, raw nearest-cross-agent similarity alone is insufficient. Use a leave-one-example-out retrieval/margin test that mirrors production:

1. For each example as a query, exclude that vector itself.
2. Compute the best same-agent positive score and best other-agent negative score.
3. Record `positive - negative`, top-k agent rank, and whether the negative is cross-domain or same-domain.
4. Gate on worst-case/low-quantile margin and top-1 ownership, with separately calibrated thresholds for same-domain siblings and cross-domain collisions.
5. Run name-substitution metamorphic variants over every example.

Centroids can remain a secondary fleet visualization, but should not gate ingestion. Also lint duplicate/near-duplicate generic examples and require enough distinct action language per capability; de-entitization can otherwise collapse neighboring agents together.

## 4. P2 — query-span masking and conditional trust

### Traps

**Empty residual/entity-only requests.** A single-turn query that becomes empty or punctuation-only after masking contains no capability evidence. Failing open to the original entity-bearing query recreates the defect. It should deterministically clarify what the user wants. A context-carrying follow-up may reuse prior action text only when that prior text is explicitly in the bounded routing window; this is different from blindly restoring the unmasked entity.

**Neutral token versus type word.** The design is correct that replacing a policy name with “policy” or a relationship name with “relationship” re-injects namespace/domain information. Use one configurable neutral token shared across domains, or delete the span and normalize whitespace. Test the chosen token because sentence-transformer behavior is empirical; angle-bracket pseudo-tokens are not guaranteed to be neutral. Apply compatible normalization to the corpus and query path.

**Multi-entity requests.** The grounder deliberately handles only one “strongest” reference (`ReferenceGroundingService.java:61-65`). Masking only that one leaves other names/IDs as anchors. Return a list of grounded mentions and mask all resolved spans. Preserve mention-to-canonical-ID mappings for later binding. A single `entityKnown` boolean cannot express mixed outcomes such as one resolved, one ambiguous, and one not found.

**Surface-form drift.** `GroundingResult` carries a resolved ID/type/sub-domain but no source offsets (`ReferenceGroundingService.java:117-124`). LLM-extracted text may differ in case, punctuation, possessive suffix, Unicode normalization, or extent from the source. Do not perform an unbounded regex replacement of the extracted string. Carry exact `[start,end)` spans from typed-ID matchers and, for extracted names, require an evidence span or a conservative literal alignment step. Mask all non-overlapping spans in reverse offset order and record whether alignment succeeded.

**Shared namespaces.** “Grounded entity domain” is not currently reliable because `identifyByReference()` returns the first HashMap match. More fundamentally, an entity can be a valid argument to capabilities in several domains. Compare the action route with a set of resolved namespace candidates, not one incidental domain.

**Context enrichment.** Mask every occurrence of grounded mentions in every user turn included in `routingText`, not only the latest prompt. Otherwise the previous turn can retain the same anchor (`ChatService.java:1374-1398`). Avoid masking ordinary words that happen to equal short entity names; source spans and resolution evidence are required.

### Is “disagreement re-arms gates, never overrides/clarifies” correct?

**Mostly yes, with conditions.** Entity/action disagreement is not itself an error. Bug-261 is a legitimate cross-domain action applied to a named entity. The entity domain must never force the router to the entity's home capability, and disagreement alone must not force a clarification. Route on masked action text and apply the ordinary score/margin gates.

But “let existing behavior decide” is not enough unless the disagreement path removes every relaxation:

- set trust from `RESOLVED_ALLOWED`, not mere reference presence;
- re-enable both the contextual domain-margin gate and the 0.40/0.005 general gates;
- do not let reranker error fallback suppress margin abstention. Today `embeddingFallback` sets `suppressMarginAbstain=true` (`AgentResolver.java:352-356`, `393-395`);
- apply the deterministic post-prune intent-preservation guard regardless of scores; and
- if masking leaves no action evidence, clarify instead of failing open to the contaminated string.

The masked route should be allowed to select asset servicing even though the reference first resolved in insurance. Authorization then honestly denies the requested servicing capability for `uw_sam`. That is the expected bug-261 disposition.

## 5. P3 — reranker

Feeding the masked query and adding “match the action asked, not the names mentioned” are sound generic changes. A domain-conflict trigger is also useful, provided “conflict” is computed from manifest-derived routing/grounding metadata and never from literal domain names.

Risks and missing pieces:

- The reranker payload currently includes raw manifest examples (`LlmRoutingRerankerClient.java:72-95`). Until P1 is complete, those examples reintroduce the same entity contamination inside the reranker. Normalize or omit them.
- The reranker only sees `max-candidates` (default five) (`AgentResolver.java:85-92`, `320-324`). A contaminated embedding shortlist can exclude the correct capability, and an LLM cannot recover a missing candidate. Conflict eval must measure correct-capability recall at K before judging reranker accuracy.
- `multiple` identifies no subset and falls back to all embedding candidates (`AgentResolver.java:323-332`). That is not a real multi-intent plan and remains vulnerable to false-positive fan-out.
- Reranker errors fail open to the embedding leader and suppress the top-two margin abstain (`AgentResolver.java:352-356`, `393-395`). On an entity/action conflict path, preserve abstention gates on error.
- LLM output must never decide authorization or whether it is safe to substitute another capability. P0 remains deterministic.

## 6. Eval upgrade assessment

The proposed additions are necessary but not sufficient. Make these release-blocking:

### Required datasets

- `capability_entity_conflict`: every capability crossed with an entity/name/ID associated with another domain; include both entitled and non-entitled personas.
- `name_invariance`: same action with (a) a name present in any corpus example, (b) a novel name, (c) a typed ID, (d) no entity, and (e) two entities. Compare the intended capability set, not necessarily the final request disposition when required context is absent.
- multi-domain clause tests: leader allowed/second denied; leader denied/second allowed; both allowed; both denied. Assert partial response semantics by requested group.
- same-domain capability-prune tests: analytical goal denied while a sibling leaf survives.
- entity-only and masking-alignment tests: empty residual, possessive, punctuation, case drift, repeated mention, and prior-turn mention.
- reranker outage/invalid-response tests on conflict cases.

### Required metrics and gates

- per-agent precision, recall, F1, and support;
- full agent and domain confusion matrices;
- top-k recall before reranking and final exact-capability accuracy after reranking;
- abstention precision and recall, not just out-of-scope abstain rate;
- name-invariance violation rate;
- conflict-set exact-capability accuracy and wrong-domain substitution rate (target **zero** after entitlement prune);
- explicit gates on cross-agent confusers and exact-agent accuracy. Do not let domain accuracy alone pass a wrong capability.

### Required end-to-end assertions

The proposed persona-scoped bug-261 E2E is mandatory, but assert more than “denial/clarify, not another domain's data”:

- no insurance agent invocation;
- no insurance facts in the response;
- asset-servicing structural denial is present in trace;
- response outcome is DENIED or a deterministic capability clarification, as specified;
- the same capability with an entitled servicing persona invokes settlement status;
- a genuine mixed insurance + servicing request still returns the entitled requested portion and explicitly withholds only the denied requested portion.

Do not run these through `/debug/resolve`. Use the real chat/BFF path and each row's actual persona token. Keep the routing-only harness as a fast component gate, but do not label it end-to-end routing correctness.

## 7. What I would do differently

### Recommended rollout

1. **First, add failing regression evidence.** Add the exact bug-261 persona E2E, a same-domain sibling-prune test, and two true multi-domain prune-order tests.
2. **Ship a narrow deterministic containment guard.** Initially implement the exact domain continuity predicate above, but apply it only to routes classified as single-primary. Never let lower-domain survivors replace a fully pruned primary domain.
3. **Add an explicit routing decision model.** Carry `primaryCandidate`, `requestedCapabilityGroups`, `rerankerOutcome`, and `dagGoal` across authorization. Upgrade P0 from domain continuity to target-capability continuity.
4. **De-entity every manifest corpus example and ID.** Add leave-one-out collision and metamorphic name-invariance lints. Rebuild the index and re-baseline thresholds.
5. **Fix grounding before relying on entity-domain agreement.** Return all eligible interpretations and exact source spans; remove the first-HashMap-match semantic decision.
6. **Mask all grounded spans in the full routing window.** Clarify on an empty single-turn residual; reuse only real prior action context.
7. **Update reranking.** Use masked query/corpus material, add the generic action-over-name instruction and conflict trigger, preserve abstention on reranker failure, and measure shortlist recall.
8. **Make the upgraded eval a release gate.** Gate exact capability accuracy, conflict invariance, and e2e post-prune response honesty, not only domain accuracy.

### Explicit disagreements with the Fable design

1. **DISAGREE:** P0 as “leader-domain has any survivor” is not capability honesty. It misses same-domain substitution and DAG-goal fallback.
2. **DISAGREE:** terminating whenever the leader domain is pruned is safe for a single-capability query, but it wrongly discards an independently requested, entitled second capability in a true multi-domain query. Requested capability groups are needed.
3. **DISAGREE:** there is no existing “manifest structural-denial copy” contract. Coverage denial copy must not be reused for a Cerbos structural denial.
4. **DISAGREE:** the design treats “grounded entity domain” as trustworthy. Current code derives it by first HashMap match, and shared entity namespaces make a single domain conceptually wrong.
5. **DISAGREE:** fail-open to the unmasked query on empty residual is unsafe for a single-turn entity-only ask. That must clarify; only explicit prior action context may be reused.
6. **DISAGREE:** merely re-arming existing gates on entity/action disagreement is insufficient because reranker error fallback suppresses a margin gate and P0 is still required.
7. **DISAGREE:** a collision lint based only on nearest cross-agent similarity is underspecified. It should mirror max-over-examples production retrieval and gate ownership/margin; centroid is only supplementary.
8. **DISAGREE:** the claim that eval seed inflation comes from all client names being reused is too broad. Whitman/Continental and some IDs are reused; Okafor/Calderon/REL-00188 are not. The decisive blind spot is absence of cross-domain entity/capability pairings.
9. **DISAGREE:** one bug-261 E2E is not enough. Same-domain prune, DAG re-gate, multi-domain partial response, reranker failure, and masking edge cases are required.
10. **MISSING:** the design does not address the fact that `entityKnown` can be set by syntactic presence even without a successful allowed grounding.
11. **MISSING:** the reranker receives raw contaminated examples and cannot recover a correct capability absent from its top-five shortlist.
12. **MISSING:** current confuser cases and exact-agent accuracy do not participate in the release gate, and eval personas are ignored.

## 8. World-B verification

The proposed mechanisms can remain fully World-B:

- P0 compares runtime manifest fields/agent IDs and authorization results.
- P1 changes manifest data and registry ingestion/eval logic.
- P2 uses manifest-derived entity definitions, grounding spans, and runtime domain identifiers.
- P3 uses a generic prompt instruction and manifest-derived candidates.
- Eval cases may contain domain literals because they are test data, not gateway Java.

No gateway Java should contain `insurance`, `asset-servicing`, `settlements`, `policy`, `relationship`, `Continental Freight`, `POL-`, `REL-`, or any other domain/entity/capability literal. In particular:

- do not hardcode the mask token as an entity-type word;
- do not implement a domain precedence table for grounding;
- do not special-case `uw_sam`, Continental Freight, or settlement agents in P0;
- do not encode domain-to-segment mapping in Java; Cerbos remains the source of structural authorization;
- do not select denial copy with a switch on a domain name.

Baseline execution of `bash scripts/world-b-check.sh` during this audit reported **CRITICAL: 0, REVIEW: 0**. The checker scans only gateway Java (`scripts/world-b-check.sh:25-27`) and the proposal need not change its count.

One independent warning: the checker itself has a stale hardcoded vocabulary. Its critical patterns include wealth/asset-servicing and several client/entity terms, but omit the checked-in insurance and HR domain vocabulary (`scripts/world-b-check.sh:60-75`). Therefore “CRITICAL: 0” does not prove the gateway is clean for every currently onboarded domain. The stronger World-B check should derive forbidden domain IDs, sub-domain IDs, entity keys/extract slots, ID-pattern literals, and known demo names from manifests/test data, then scan gateway source. That enhancement belongs in scripts/eval, not as domain knowledge in gateway Java.

## Final assessment

Do not reject the architecture; revise it before implementation. The strongest parts are de-entitized manifest examples, masked action routing, and a deterministic post-prune guard. The central change is to make the guard preserve an explicit requested capability/goal rather than merely a domain, and to replace the lossy `entityKnown + first entity domain` model with structured multi-reference grounding. With those changes and a real persona-scoped conflict/invariance gate, the design remains World-B and closes bug-261 as a class rather than as a demo-specific patch.
