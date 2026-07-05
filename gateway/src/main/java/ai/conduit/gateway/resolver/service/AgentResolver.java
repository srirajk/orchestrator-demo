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

import java.util.List;

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

    private final VectorIndex    vectorIndex;
    private final AgentRegistry  registry;
    private final MeterRegistry  meterRegistry;

    public AgentResolver(VectorIndex vectorIndex, AgentRegistry registry, MeterRegistry meterRegistry) {
        this.vectorIndex   = vectorIndex;
        this.registry      = registry;
        this.meterRegistry = meterRegistry;
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
            return new ResolverResult(List.of(), List.of(), true, 0.0, routingText);
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
            return new ResolverResult(List.of(), broad, true, leaderScore, routingText);
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
        double topScore = candidates.isEmpty() ? 0.0 :
                candidates.stream().mapToDouble(RoutingCandidate::score).max().orElse(0.0);

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

        return new ResolverResult(selected, skipped, fallback, topScore, queryText);
    }
}
