package ai.conduit.gateway.resolver.service;

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
import java.util.List;
import java.util.Optional;

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

    private final VectorIndex    vectorIndex;
    private final AgentRegistry  registry;
    private final MeterRegistry  meterRegistry;
    private final RoutingRerankerClient rerankerClient;

    public AgentResolver(VectorIndex vectorIndex, AgentRegistry registry, MeterRegistry meterRegistry,
                         RoutingRerankerClient rerankerClient) {
        this.vectorIndex     = vectorIndex;
        this.registry        = registry;
        this.meterRegistry   = meterRegistry;
        this.rerankerClient  = rerankerClient;
    }

    /**
     * Resolve a user prompt to the set of agents that should be invoked.
     *
     * @param prompt  the user's natural-language query
     * @param domain  optional domain filter (null = search both domains)
     */
    @Observed(name = "resolver.route")
    public ResolverResult resolve(String prompt, String domain) {
        List<RoutingCandidate> candidates = vectorIndex.search(
                prompt, domain, topK, id -> registry.find(id).orElse(null));
        return select(candidates, prompt);
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
        List<RoutingCandidate> broad = vectorIndex.search(
                routingText, null, topK, id -> registry.find(id).orElse(null));

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

        ResolverResult result = select(broad, routingText);
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
    private ResolverResult select(List<RoutingCandidate> candidates, String queryText) {
        if (candidates.isEmpty()) {
            return new ResolverResult(List.of(), List.of(), true, 0.0, queryText, 0.0, false);
        }

        // candidates is sorted descending by score (VectorIndex.search() contract) — get(0) is
        // the leader.
        double topScore = candidates.get(0).score();
        double runnerUpScore = candidates.size() > 1 ? candidates.get(1).score() : 0.0;
        double topMargin = candidates.size() > 1 ? topScore - runnerUpScore : Double.MAX_VALUE;
        RerankApplication rerank = maybeRerank(candidates, queryText, topScore, topMargin);
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
        boolean routingAbstain = topScore < routingMinScore
                || (!rerank.suppressMarginAbstain() && topMargin < routingMinMargin);
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
                topMargin, rerank.rerankFired());
    }

    private RerankApplication maybeRerank(List<RoutingCandidate> candidates, String queryText,
                                          double topScore, double topMargin) {
        if (!shouldRerank(candidates, topScore, topMargin)) {
            meterRegistry.counter("resolver.rerank.skipped").increment();
            return RerankApplication.unchanged(candidates);
        }

        List<RoutingCandidate> shortlist = candidates.stream()
                .limit(Math.max(1, rerankMaxCandidates))
                .toList();
        try {
            RoutingRerankerClient.Decision decision = rerankerClient.rerank(queryText, shortlist);
            if (decision.needsMultiple()) {
                // The query is CLEAR but broader than any one capability. The re-ranker only exists to
                // break a near-tie by picking a single winner; that it cannot is not a reason to throw
                // away the embedding search's candidates. Keep them and let the confidence floor and
                // margin gates below decide — which is exactly what happens when the re-ranker errors.
                log.debug("Resolver rerank: multi-capability request, keeping candidates: {}", decision.reason());
                meterRegistry.counter("resolver.rerank.needs_multiple").increment();
                return RerankApplication.noOpinion(candidates);
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
                log.warn("Resolver rerank returned non-candidate id '{}'; using embedding leader",
                        decision.candidateId());
                meterRegistry.counter("resolver.rerank.invalid").increment();
                return RerankApplication.embeddingFallback(candidates);
            }
            meterRegistry.counter("resolver.rerank.pick").increment();
            log.debug("Resolver rerank picked {}: {}", picked.get().manifest().agentId(), decision.reason());
            return RerankApplication.reordered(reorder(candidates, picked.get()));
        } catch (Exception e) {
            log.warn("Resolver rerank failed ({}); using embedding leader: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            meterRegistry.counter("resolver.rerank.error").increment();
            return RerankApplication.embeddingFallback(candidates);
        }
    }

    private boolean shouldRerank(List<RoutingCandidate> candidates, double topScore, double topMargin) {
        if (!rerankEnabled || candidates.size() < 2) {
            return false;
        }
        boolean nearTie = topMargin <= rerankMarginThreshold;
        boolean nearAbstain = topScore >= routingMinScore
                && topScore <= routingMinScore + rerankAbstainAdjacentBand;
        return nearTie || nearAbstain;
    }

    private static List<RoutingCandidate> reorder(List<RoutingCandidate> candidates, RoutingCandidate picked) {
        List<RoutingCandidate> reordered = new ArrayList<>(candidates.size());
        reordered.add(picked);
        candidates.stream()
                .filter(c -> !c.manifest().agentId().equals(picked.manifest().agentId()))
                .forEach(reordered::add);
        return List.copyOf(reordered);
    }

    private record RerankApplication(
            List<RoutingCandidate> candidates,
            boolean abstain,
            boolean suppressMarginAbstain,
            boolean rerankFired) {

        static RerankApplication unchanged(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, false, false, false);
        }

        static RerankApplication reordered(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, false, true, true);
        }

        static RerankApplication embeddingFallback(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, false, true, true);
        }

        /**
         * The re-ranker ran and declined to pick a single winner because the request needs several
         * capabilities. Keep the embedding candidates and let the ordinary confidence gates decide —
         * the re-ranker simply has no opinion to add. Unlike {@link #embeddingFallback}, the margin
         * abstain gate is NOT suppressed: a genuinely weak or too-close leader must still abstain.
         */
        static RerankApplication noOpinion(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, false, false, true);
        }

        static RerankApplication abstain(List<RoutingCandidate> candidates) {
            return new RerankApplication(candidates, true, false, true);
        }
    }
}
