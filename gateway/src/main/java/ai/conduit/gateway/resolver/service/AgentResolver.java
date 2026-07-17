package ai.conduit.gateway.resolver.service;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.registry.service.AgentRegistry;
import ai.conduit.gateway.resolver.model.ResolverResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolver — Stage A + Stage B from the spec.
 *
 * Stage A: embed prompt → vector search → apply confidence floor
 * Stage B: filter by domain (optional) + is_mutating==false (always)
 *
 * Returns a ResolverResult with the selected agents and a fallback flag.
 * The debug endpoint exposes this without invoking agents.
 */
@Service
public class AgentResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentResolver.class);

    @Value("${conduit.resolver.confidence-floor:0.35}")
    private double confidenceFloor;

    /** Applied when topScore > 0.55: floor = max(absolute, topScore * factor). Prunes long-tail matches. */
    @Value("${conduit.resolver.relative-floor-factor:0.65}")
    private double relativeFloorFactor;

    @Value("${conduit.resolver.top-k:10}")
    private int topK;

    /**
     * Minimum similarity gap between the leading candidate and the best candidate from any OTHER
     * domain for the query to count as having a confident domain winner. A small gap means the
     * leader is jostling with cross-domain candidates in the noise band. Margin-based by design
     * (plan §3) — this, not a raw absolute cutoff, is what rejects a lone low-confidence
     * out-of-domain hit.
     */
    @Value("${conduit.resolver.domain-margin:0.05}")
    private double domainMargin;

    /**
     * A leader at or above this similarity is decisively strong: the route is trusted on the
     * leader's strength alone, regardless of cross-domain proximity. This keeps legitimate
     * balanced cross-domain queries ("holdings AND settlements") routable — their leader is well
     * clear of the floor even when a second domain scores close behind. Mirrors the score above
     * which the dynamic relative floor engages.
     */
    @Value("${conduit.resolver.decisive-score:0.55}")
    private double decisiveScore;

    /**
     * General routing abstain gate (T1.6) — applied inside {@link #select}, so it runs for EVERY
     * resolve call (the plain debug path AND the contextual chat path). An absolute floor: below
     * this, the leader is untrustworthy on its own regardless of how it compares to anything
     * else. Distinct from {@link #confidenceFloor}, which is a per-candidate long-tail prune, not
     * a whole-query abstain signal.
     */
    @Value("${conduit.routing.min-score:0.40}")
    private double routingMinScore;

    /**
     * Minimum raw top-1-vs-top-2 score gap. Below this the leader and runner-up are too close to
     * call — the router would be guessing, not routing — so the query abstains rather than
     * serving a coin-flip pick. Domain-agnostic (unlike {@link #domainMargin}, which only compares
     * the leader against the best OTHER-domain candidate).
     */
    @Value("${conduit.routing.min-margin:0.005}")
    private double routingMinMargin;

    @Value("${conduit.routing.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${conduit.routing.rerank.margin-threshold:0.13}")
    private double rerankMarginThreshold;

    @Value("${conduit.routing.rerank.abstain-adjacent-band:0.08}")
    private double rerankAbstainAdjacentBand;

    @Value("${conduit.routing.rerank.max-candidates:5}")
    private int rerankMaxCandidates;

    /**
     * Conflict trigger (routing spec V2 Piece 5, V2.1 minor) — a rerank/abstain HINT only, never a
     * denial or correctness rule. When the routed leader's domain is not among the grounded references'
     * domains (a set the caller derives from the Piece-2 {@code GroundedReferenceSet} and passes in —
     * this resolver never reads grounding, only compares ids), the query is a candidate for a
     * conflict-path rerank whose error handling is STRICTER (a reranker error/invalid response abstains
     * rather than trusting the embedding leader, preserving margin abstention). Ships OFF: it is enabled
     * only after Piece 6's top-k recall gate passes, because a contaminated shortlist can exclude the
     * right capability and the LLM cannot recover an absent candidate. Off ⇒ the trigger never fires and
     * behaviour is byte-identical to before.
     */
    @Value("${conduit.routing.rerank-conflict-trigger.enabled:false}")
    private boolean rerankConflictTriggerEnabled;

    // The former `rerank.pick-score-tolerance` band-aid — which let a rerank-picked leader a hair
    // below the routing floor still route so a phrasing-sensitive score ("Okafor's holdings" 0.398 vs
    // "Okafor holdings" 0.418) would not flip a would-be coverage denial into "no service" — has been
    // RETIRED. That disposition is now handled deterministically BEFORE routing by
    // ReferenceGroundingService (a grounded out-of-book reference denies at any score), so the routing
    // score no longer sits between the user and a denial. The `entityKnown` signal (below) replaces the
    // tolerance for the legitimate case: a grounded reference keeps a near-floor query routable.

    private final VectorIndex    vectorIndex;
    private final AgentRegistry  registry;
    private final MeterRegistry  meterRegistry;
    private final RoutingRerankerClient rerankerClient;
    private final TenantKeyspace keyspace;

    public AgentResolver(VectorIndex vectorIndex, AgentRegistry registry, MeterRegistry meterRegistry,
                         RoutingRerankerClient rerankerClient, TenantKeyspace keyspace) {
        this.vectorIndex     = vectorIndex;
        this.registry        = registry;
        this.meterRegistry   = meterRegistry;
        this.rerankerClient  = rerankerClient;
        this.keyspace        = keyspace;
    }

    /**
     * H5 fail-closed guard for the data plane. Under multi-tenant enforcement a routing query with no
     * resolved tenant is DENIED rather than served from the shared legacy {@code intent_idx}. Delegates
     * the multi-tenant/default/off decision to {@link TenantKeyspace} so the rule lives in exactly one
     * place. A no-op with multi-tenancy OFF and for the default tenant — the demo path is untouched.
     */
    private void requireResolvedTenantForDataRoute(TenantExecutionContext tenant) {
        if (keyspace.deniesTenantlessDataRoute(tenant)) {
            meterRegistry.counter("resolver.route.tenant_denied").increment();
            throw new TenantlessRouteDeniedException();
        }
    }

    /**
     * A data-plane routing query arrived without a resolved tenant while multi-tenant enforcement is ON.
     * Serving it from the shared legacy {@code intent_idx} would leak routing metadata across tenants
     * (the H5 fail-open hole), so the route is denied rather than silently answered from the legacy index.
     */
    public static final class TenantlessRouteDeniedException extends RuntimeException {
        public TenantlessRouteDeniedException() {
            super("multi-tenant routing denied: the request carried no resolved tenant on a data route");
        }
    }

    /**
     * Resolve a user prompt to the set of agents that should be invoked.
     *
     * @param prompt  the user's natural-language query
     * @param domain  optional domain filter (null = search both domains)
     */
    @Observed(name = "resolver.route")
    public ResolverResult resolve(String prompt, String domain) {
        return resolve(prompt, domain, null);
    }

    /**
     * Resolve a user prompt to the set of agents, in the given request tenant's routing keyspace (H5).
     *
     * <p>The tenant context is threaded into {@link VectorIndex#search} so the query runs against the
     * tenant-qualified index ({@code intent_idx__{tenant}} for a real tenant, the legacy
     * {@code intent_idx} for the default tenant or with multi-tenancy off). This overload backs the
     * debug/admin inspection path, which carries no tenant context and so reads the legacy index; the
     * fail-closed guard for a tenant-less DATA route lives on the contextual (chat) path.
     */
    @Observed(name = "resolver.route")
    public ResolverResult resolve(String prompt, String domain, TenantExecutionContext tenant) {
        List<RoutingCandidate> candidates = vectorIndex.search(
                prompt, domain, topK, tenant, id -> registry.find(id).orElse(null));
        return select(candidates, prompt, false, Set.of());
    }

    /** Convenience overload — no domain filter. */
    public ResolverResult resolve(String prompt) {
        return resolve(prompt, null);
    }

    /**
     * Context-aware routing for the chat path, with a confidence/abstain gate.
     *
     * <p>The chat path historically embedded only the bare last user message, so a keyword-less
     * follow-up ("summarize for Calderon Trust (REL-00099)?") landed in the cross-domain noise band:
     * the true in-domain agents scored below the floor while a lone out-of-domain agent leaked
     * through the absolute floor, and the request was misrouted and wrongly denied. This method
     * fixes it generically, with zero domain knowledge in the gateway:
     *
     * <ol>
     *   <li><b>Conversation-enriched embedding</b> — the caller builds {@code routingText} from the
     *       recent user turns, so a keyword-less follow-up inherits the conversation's established
     *       domain vocabulary. This alone lifts the true matches back above the confidence floor and
     *       drops the spurious out-of-domain hit below it — no domain literal anywhere; the routing
     *       text is raw conversation content.</li>
     *   <li><b>Confidence/abstain gate</b> — the query is only routed if the leading candidate is
     *       trustworthy: either decisively strong on its own ({@code leader ≥ decisiveScore}) or it
     *       out-scores the best candidate from any OTHER domain by {@link #domainMargin}. A leader
     *       barely above the floor AND jostling with a different domain in the noise band is a lone
     *       low-confidence hit → abstain (empty selection + fallback) so the chat path emits the
     *       deterministic clarification instead of trusting it. Margin-based, not a raw cutoff.</li>
     *   <li><b>Standard floor</b> — a confident query is selected with the normal dynamic floor over
     *       the broad candidate set, so a legitimate cross-domain query (e.g. holdings + settlements)
     *       still fans out across domains; only sub-floor noise is pruned.</li>
     * </ol>
     *
     * <p>Entity resolution runs AFTER routing in the chat pipeline, so the routed domain cannot come
     * from the resolved entity. The conversation's established domain is instead carried by the
     * enriched routing text — cheaper than, and free of the circular coverage-service selection
     * that, a pre-routing entity resolution would require.
     *
     * @param routingText conversation-enriched query text (recent user turns), built by the caller
     */
    @Observed(name = "resolver.route.contextual")
    public ResolverResult resolveContextual(String routingText) {
        return resolveContextual(routingText, false);
    }

    /**
     * Context-aware routing with an optional <b>bias-to-fetch</b> relaxation of the abstain gate.
     *
     * <p>When {@code entityKnown} is true the current turn already carries an explicit, grounded
     * subject (an id the user typed, or a name the deterministic focal derivation resolved), so the
     * turn is NOT genuinely under-specified — only which agents/facet to consult is muddy. In that
     * case a low top-vs-noise margin must NOT abstain into a clarification (that would strand a
     * terse but fully-specified follow-up like "and for REL-00099?"); the query is routed with the
     * normal dynamic floor and the downstream coverage CHECK remains the access gate. When
     * {@code entityKnown} is false the original confidence/abstain gate applies unchanged, so bare
     * under-specified asks still clarify (bug-232 behaviour preserved).
     *
     * @param routingText conversation-enriched query text (recent user turns), built by the caller
     * @param entityKnown the turn carries an explicit grounded resolvable entity reference
     */
    @Observed(name = "resolver.route.contextual")
    public ResolverResult resolveContextual(String routingText, boolean entityKnown) {
        return resolveContextual(routingText, entityKnown, Set.of());
    }

    /**
     * Context-aware routing that additionally supplies the <b>grounded references' domain set</b> for
     * the Piece-5 conflict trigger. {@code groundedDomainIds} are the manifest domain ids of the turn's
     * grounded references (the caller derives them from the Piece-2 {@code GroundedReferenceSet}); the
     * resolver never inspects grounding, it only checks whether the routed leader's domain id is in this
     * set. Empty set ⇒ no conflict trigger (identical to the two-arg overload). The trigger is itself
     * gated OFF by {@code conduit.routing.rerank-conflict-trigger.enabled}, so today this is inert
     * plumbing for Piece 4 to wire; it never denies — it only makes the near-tie rerank's error handling
     * stricter on a genuine cross-domain conflict.
     */
    @Observed(name = "resolver.route.contextual")
    public ResolverResult resolveContextual(String routingText, boolean entityKnown,
                                             Set<String> groundedDomainIds) {
        return resolveContextual(routingText, entityKnown, groundedDomainIds, null);
    }

    /**
     * Tenant-aware contextual routing (Axiom H5). The chat request path threads the request's resolved
     * {@link TenantExecutionContext} through here so the routing query runs against that tenant's own
     * index ({@code intent_idx__{tenant}}), not the shared legacy {@code intent_idx}.
     *
     * <p><b>Fail closed.</b> When multi-tenant enforcement is ON and this data route carries no resolved
     * tenant, the query is DENIED ({@link TenantlessRouteDeniedException}) rather than silently answered
     * from the legacy shared index — that fallback would leak routing metadata across tenants (the
     * fail-open hole the audit flagged). With multi-tenancy OFF, or for the configured default tenant,
     * {@code tenant} resolves to the legacy names and behaviour is byte-identical to the single-tenant
     * demo.
     */
    @Observed(name = "resolver.route.contextual")
    public ResolverResult resolveContextual(String routingText, boolean entityKnown,
                                             TenantExecutionContext tenant) {
        return resolveContextual(routingText, entityKnown, Set.of(), tenant);
    }

    /**
     * Tenant-aware contextual routing carrying both the grounded domain set (Piece-5 conflict trigger)
     * and the request tenant (H5). See {@link #resolveContextual(String, boolean, TenantExecutionContext)}
     * for the fail-closed contract.
     */
    @Observed(name = "resolver.route.contextual")
    public ResolverResult resolveContextual(String routingText, boolean entityKnown,
                                             Set<String> groundedDomainIds,
                                             TenantExecutionContext tenant) {
        // H5 fail-closed: a data-plane routing query under multi-tenant enforcement MUST carry a resolved
        // tenant; a tenant-less one is denied here, BEFORE any index read, so it can never fall through to
        // the legacy shared intent_idx.
        requireResolvedTenantForDataRoute(tenant);

        List<RoutingCandidate> broad = vectorIndex.search(
                routingText, null, topK, tenant, id -> registry.find(id).orElse(null));

        if (broad.isEmpty()) {
            meterRegistry.counter("resolver.fallback").increment();
            return new ResolverResult(List.of(), List.of(), true, 0.0, routingText, 0.0, false);
        }

        // search() returns candidates sorted by score descending → first is the leader.
        RoutingCandidate leader = broad.get(0);
        double leaderScore = leader.score();
        String domain = leader.manifest().domain();

        // Best score among candidates NOT in the leader's domain (the cross-domain "noise" band).
        // Absent for a single-domain registry → 0, so a single-domain query stays routable.
        double bestOther = broad.stream()
                .filter(c -> domain == null ? c.manifest().domain() != null
                                            : !domain.equals(c.manifest().domain()))
                .mapToDouble(RoutingCandidate::score)
                .max().orElse(0.0);
        double margin = leaderScore - bestOther;

        meterRegistry.gauge("resolver.route.confidence", leaderScore);

        // Confident when the leader clears the floor AND is either decisively strong on its own
        // (keeps balanced cross-domain queries routable) or clearly beats every other domain.
        // Bias-to-fetch: a turn carrying an explicit grounded entity is trusted above the floor
        // regardless of margin — its subject is known; only the facet is being disambiguated.
        boolean confident = leaderScore >= confidenceFloor
                && (entityKnown || leaderScore >= decisiveScore || margin >= domainMargin);
        if (!confident) {
            log.debug("Resolver(contextual): abstain — leader={} domain={} score={} bestOther={} margin={} (need ≥{} or leader ≥{}; floor={})",
                    leader.manifest().agentId(), domain, String.format("%.3f", leaderScore),
                    String.format("%.3f", bestOther), String.format("%.3f", margin),
                    String.format("%.3f", domainMargin), String.format("%.3f", decisiveScore),
                    String.format("%.3f", confidenceFloor));
            meterRegistry.counter("resolver.fallback").increment();
            // Empty selection + fallback → the chat path emits the deterministic clarification.
            // Broad candidates are surfaced as skipped for the glass box.
            return new ResolverResult(List.of(), broad, true, leaderScore, routingText, margin, false);
        }

        ResolverResult result = select(broad, routingText, entityKnown, groundedDomainIds);
        log.debug("Resolver(contextual): leader={} domain={} margin={} → {} selected",
                leader.manifest().agentId(), domain, String.format("%.3f", margin),
                result.selected().size());
        return result;
    }

    /**
     * Apply the confidence floor to a candidate set. Dynamic floor: relative
     * ({@code topScore * relativeFloorFactor}) once the top score is decisive, else the absolute
     * {@code confidenceFloor}. Prunes long-tail matches for focused queries.
     */
    private ResolverResult select(List<RoutingCandidate> candidates, String queryText, boolean entityKnown,
                                  Set<String> groundedDomainIds) {
        if (candidates.isEmpty()) {
            return new ResolverResult(List.of(), List.of(), true, 0.0, queryText, 0.0, false);
        }

        // candidates is sorted descending by score (VectorIndex.search() contract) — get(0) is
        // the leader.
        double topScore = candidates.get(0).score();
        double runnerUpScore = candidates.size() > 1 ? candidates.get(1).score() : 0.0;
        double topMargin = candidates.size() > 1 ? topScore - runnerUpScore : Double.MAX_VALUE;
        RerankApplication rerank = maybeRerank(candidates, queryText, topScore, topMargin, groundedDomainIds);
        if (rerank.abstain()) {
            meterRegistry.counter("resolver.fallback").increment();
            return new ResolverResult(List.of(), candidates, true, topScore, queryText,
                    topMargin, rerank.rerankFired());
        }
        candidates = rerank.candidates();
        topScore = candidates.get(0).score();

        // ── General routing abstain gate (T1.6, config-driven, World-B clean) ───────────────
        // Runs BEFORE the per-candidate floor below. Two independent, domain-agnostic checks:
        // the leader is weak in absolute terms (routingMinScore), or the leader and runner-up
        // are too close to call (routingMinMargin). Either ⇒ the whole query abstains — no
        // partial selection — so the caller falls through to the deterministic clarify/decline
        // path instead of trusting a guess. Pure score arithmetic; no domain literal.
        runnerUpScore = candidates.size() > 1 ? candidates.get(1).score() : 0.0;
        topMargin = candidates.size() > 1 ? topScore - runnerUpScore : Double.MAX_VALUE;
        // A turn carrying an explicit GROUNDED reference (entityKnown) is not under-specified — its
        // subject was RESOLVED + coverage-CHECKED pre-routing; only which agents/facet to consult is
        // muddy. So it must not abstain on a phrasing-sensitive score just below the floor (the case
        // the retired pick-score-tolerance band-aid used to catch). The abstain gate is bypassed for
        // such turns; the per-candidate floor below still prunes long-tail noise. When the reference is
        // NOT grounded, the ordinary absolute-floor + margin abstain applies unchanged, so a genuinely
        // vague ask still falls through to the deterministic clarify/decline path.
        boolean routingAbstain = !entityKnown
                && (topScore < routingMinScore
                    || (!rerank.suppressMarginAbstain() && topMargin < routingMinMargin));
        if (routingAbstain) {
            log.debug("Resolver: routing abstain — leader={} topScore={} margin={} (need score>={}, margin>={})",
                    candidates.get(0).manifest().agentId(), String.format("%.3f", topScore),
                    candidates.size() > 1 ? String.format("%.3f", topMargin) : "n/a",
                    String.format("%.3f", routingMinScore), String.format("%.3f", routingMinMargin));
            meterRegistry.counter("resolver.fallback").increment();
            return new ResolverResult(List.of(), candidates, true, topScore, queryText,
                    topMargin, rerank.rerankFired());
        }

        double effectiveFloor = topScore > 0.55
                ? Math.max(confidenceFloor, topScore * relativeFloorFactor)
                : confidenceFloor;
        List<RoutingCandidate> selected = candidates.stream()
                .filter(c -> c.score() >= effectiveFloor)
                .toList();

        boolean fallback = selected.isEmpty();

        List<RoutingCandidate> skipped = candidates.stream()
                .filter(c -> c.score() < effectiveFloor)
                .toList();

        log.debug("Resolver: query='{}' → {} candidates, {} selected, {} skipped (floor={}/relative={}), topScore={}",
                queryText, candidates.size(), selected.size(), skipped.size(),
                String.format("%.3f", confidenceFloor), String.format("%.3f", effectiveFloor), topScore);

        meterRegistry.gauge("resolver.route.confidence", topScore);
        if (fallback) meterRegistry.counter("resolver.fallback").increment();

        return new ResolverResult(selected, skipped, fallback, topScore, queryText,
                topMargin, rerank.rerankFired(), rerank.selectedIds());
    }

    private RerankApplication maybeRerank(List<RoutingCandidate> candidates, String queryText,
                                          double topScore, double topMargin, Set<String> groundedDomainIds) {
        RerankTrigger trigger = rerankTrigger(candidates, topScore, topMargin, groundedDomainIds);
        if (trigger == RerankTrigger.NONE) {
            meterRegistry.counter("resolver.rerank.skipped").increment();
            return RerankApplication.unchanged(candidates);
        }

        // On the CONFLICT path (the routed leader's domain is not among the grounded refs' domains) a
        // reranker error / unusable answer must ABSTAIN, not silently trust the embedding leader — the
        // whole point of the trigger is to preserve margin abstention when the entity and the capability
        // disagree. On the ordinary near-tie / near-abstain paths the historical embedding-fallback
        // behaviour is kept (a broken reranker never fails an otherwise-routable request).
        boolean conflictPath = trigger == RerankTrigger.CONFLICT;

        List<RoutingCandidate> shortlist = candidates.stream()
                .limit(Math.max(1, rerankMaxCandidates))
                .toList();
        Set<String> shortlistIds = new HashSet<>();
        for (RoutingCandidate c : shortlist) {
            shortlistIds.add(c.manifest().agentId());
        }
        try {
            RoutingRerankerClient.Decision decision = rerankerClient.rerank(queryText, shortlist);
            if (decision.needsMultiple()) {
                // The query is CLEAR but broader than any one capability. Piece 5: a valid multi-facet
                // answer names the explicit shortlist subset (one id per requested facet) — validate it
                // (in-shortlist, unique, non-empty, capped) and carry it forward for Piece 4 to form one
                // requested group per id, keeping the embedding candidates so the request still fans out.
                List<String> selectedIds = validMultiple(decision.candidateIds(), shortlistIds);
                if (selectedIds.isEmpty()) {
                    // No usable explicit ids (empty / duplicate / unknown / over cap). On a conflict path
                    // that is an error-equivalent → abstain and preserve margin abstention. Off the
                    // conflict path, fall back to the historical multi-capability behaviour: keep the
                    // embedding candidates and let the confidence floor + margin gates decide.
                    log.debug("Resolver rerank: multi-capability answer without usable ids (conflictPath={}): {}",
                            conflictPath, decision.reason());
                    meterRegistry.counter("resolver.rerank.invalid_multiple").increment();
                    return conflictPath
                            ? RerankApplication.abstain(candidates)
                            : RerankApplication.noOpinion(candidates);
                }
                log.debug("Resolver rerank: multi-capability request, selected {}: {}",
                        selectedIds, decision.reason());
                meterRegistry.counter("resolver.rerank.needs_multiple").increment();
                return RerankApplication.multiple(candidates, selectedIds);
            }
            if (decision.abstain()) {
                // Genuinely ambiguous: indistinguishable candidates, or none fit. Clarify.
                log.debug("Resolver rerank abstained: {}", decision.reason());
                meterRegistry.counter("resolver.rerank.abstain").increment();
                return RerankApplication.abstain(candidates);
            }
            Optional<RoutingCandidate> picked = shortlist.stream()
                    .filter(c -> c.manifest().agentId().equals(decision.candidateId()))
                    .findFirst();
            if (picked.isEmpty()) {
                log.warn("Resolver rerank returned non-candidate id '{}' (conflictPath={}); {}",
                        decision.candidateId(), conflictPath,
                        conflictPath ? "abstaining" : "using embedding leader");
                meterRegistry.counter("resolver.rerank.invalid").increment();
                return conflictPath
                        ? RerankApplication.abstain(candidates)
                        : RerankApplication.embeddingFallback(candidates);
            }
            meterRegistry.counter("resolver.rerank.pick").increment();
            log.debug("Resolver rerank picked {}: {}", picked.get().manifest().agentId(), decision.reason());
            return RerankApplication.reordered(reorder(candidates, picked.get()));
        } catch (Exception e) {
            log.warn("Resolver rerank failed ({}, conflictPath={}); {}: {}",
                    e.getClass().getSimpleName(), conflictPath,
                    conflictPath ? "abstaining" : "using embedding leader", e.getMessage());
            meterRegistry.counter("resolver.rerank.error").increment();
            return conflictPath
                    ? RerankApplication.abstain(candidates)
                    : RerankApplication.embeddingFallback(candidates);
        }
    }

    /**
     * Why (and how) the reranker fires for this candidate set — a small tag, never an anonymous boolean,
     * because the three triggers demand different error handling downstream. Priority: a CONFLICT (when
     * enabled) dominates, because its stricter abstain-on-error is the whole reason it exists; otherwise
     * the historical near-tie / near-abstain triggers apply, both of which keep the embedding-fallback
     * error path.
     */
    private RerankTrigger rerankTrigger(List<RoutingCandidate> candidates, double topScore,
                                        double topMargin, Set<String> groundedDomainIds) {
        if (!rerankEnabled || candidates.size() < 2) {
            return RerankTrigger.NONE;
        }
        if (isConflict(candidates, groundedDomainIds)) {
            return RerankTrigger.CONFLICT;
        }
        if (topMargin <= rerankMarginThreshold) {
            return RerankTrigger.NEAR_TIE;
        }
        boolean nearAbstain = topScore >= routingMinScore
                && topScore <= routingMinScore + rerankAbstainAdjacentBand;
        return nearAbstain ? RerankTrigger.NEAR_ABSTAIN : RerankTrigger.NONE;
    }

    /**
     * The Piece-5 conflict signal, config-OFF by default: the routed leader's domain id is NOT among the
     * grounded references' domain ids. Pure id-set membership — no domain literal, no entity-type word;
     * the grounded domain set is supplied by the caller. A HINT to rerank with stricter error handling,
     * never a denial: bug-261 is a legitimate cross-domain case, so a conflict must never itself reject a
     * route — it only asks the reranker to decide and preserves abstention if the reranker cannot.
     */
    private boolean isConflict(List<RoutingCandidate> candidates, Set<String> groundedDomainIds) {
        if (!rerankConflictTriggerEnabled || groundedDomainIds == null || groundedDomainIds.isEmpty()) {
            return false;
        }
        String leaderDomain = candidates.get(0).manifest().domain();
        return leaderDomain == null || !groundedDomainIds.contains(leaderDomain);
    }

    /**
     * Validate a multi-facet answer's explicit shortlist subset: each id must be in the presented
     * shortlist, non-empty, and unique; the result is capped at {@code rerankMaxCandidates}. Returns the
     * validated ids in first-seen order, or an EMPTY list if any id is unknown or duplicated (so the
     * caller treats the whole answer as an invalid {@code multiple}). Order-preserving and deterministic.
     */
    private List<String> validMultiple(List<String> candidateIds, Set<String> shortlistIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        List<String> validated = new ArrayList<>(candidateIds.size());
        Set<String> seen = new HashSet<>();
        for (String id : candidateIds) {
            if (id == null || id.isBlank() || !shortlistIds.contains(id) || !seen.add(id)) {
                // unknown, empty, or duplicate id ⇒ the multi-facet answer is unusable as a whole
                return List.of();
            }
            validated.add(id);
        }
        int cap = Math.max(1, rerankMaxCandidates);
        return validated.size() > cap ? List.copyOf(validated.subList(0, cap)) : List.copyOf(validated);
    }

    private static List<RoutingCandidate> reorder(List<RoutingCandidate> candidates, RoutingCandidate picked) {
        List<RoutingCandidate> reordered = new ArrayList<>(candidates.size());
        reordered.add(picked);
        candidates.stream()
                .filter(c -> !c.manifest().agentId().equals(picked.manifest().agentId()))
                .forEach(reordered::add);
        return List.copyOf(reordered);
    }

    /**
     * How (and why) the reranker fired for this candidate set. A small tag rather than another anonymous
     * boolean, because the trigger decides error handling: only {@link #CONFLICT} abstains on a reranker
     * error / invalid response; {@link #NEAR_TIE} and {@link #NEAR_ABSTAIN} keep the embedding fallback.
     * {@link #NONE} = the reranker was not invoked.
     */
    private enum RerankTrigger { NONE, NEAR_TIE, NEAR_ABSTAIN, CONFLICT }

    private record RerankApplication(
            List<RoutingCandidate> candidates,
            boolean abstain,
            boolean suppressMarginAbstain,
            boolean rerankFired,
            List<String> selectedIds) {

        RerankApplication {
            selectedIds = selectedIds == null ? List.of() : List.copyOf(selectedIds);
        }

        static RerankApplication unchanged(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, false, false, false, List.of());
        }

        static RerankApplication reordered(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, false, true, true, List.of());
        }

        static RerankApplication embeddingFallback(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, false, true, true, List.of());
        }

        /**
         * The re-ranker ran and declined to pick a single winner because the request needs several
         * capabilities. Keep the embedding candidates and let the ordinary confidence gates decide —
         * the re-ranker simply has no opinion to add. Unlike {@link #embeddingFallback}, the margin
         * abstain gate is NOT suppressed: a genuinely weak or too-close leader must still abstain.
         */
        static RerankApplication noOpinion(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, false, false, true, List.of());
        }

        /**
         * A VALID multi-facet answer (Piece 5): the re-ranker named an explicit, validated shortlist
         * subset — one id per requested facet. Same routing shape as {@link #noOpinion} (keep the
         * embedding candidates, fan out, margin abstain NOT suppressed) but the selected ids are carried
         * forward on {@link ResolverResult#rerankSelectedIds()} so Piece 4 can form one requested group
         * per id.
         */
        static RerankApplication multiple(List<RoutingCandidate> candidates, List<String> selectedIds) {
            return new RerankApplication(candidates, false, false, true, selectedIds);
        }

        static RerankApplication abstain(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, true, false, true, List.of());
        }
    }
}
