package ai.meridian.gateway.resolver.service;

import ai.meridian.gateway.registry.index.VectorIndex;
import ai.meridian.gateway.registry.model.AgentManifest;
import ai.meridian.gateway.registry.model.RoutingCandidate;
import ai.meridian.gateway.registry.service.AgentRegistry;
import ai.meridian.gateway.resolver.model.ResolverResult;
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

    @Value("${meridian.resolver.confidence-floor:0.35}")
    private double confidenceFloor;

    /** Applied when topScore > 0.55: floor = max(absolute, topScore * factor). Prunes long-tail matches. */
    @Value("${meridian.resolver.relative-floor-factor:0.65}")
    private double relativeFloorFactor;

    @Value("${meridian.resolver.top-k:10}")
    private int topK;

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

        double topScore = candidates.isEmpty() ? 0.0 :
                candidates.stream().mapToDouble(RoutingCandidate::score).max().orElse(0.0);

        // Stage B: dynamic floor — relative prunes long-tail matches for focused queries
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

        log.debug("Resolver: prompt='{}' → {} candidates, {} selected, {} skipped (floor={:.3f}/relative={:.3f}), topScore={}",
                prompt, candidates.size(), selected.size(), skipped.size(), confidenceFloor, effectiveFloor, topScore);

        meterRegistry.gauge("resolver.route.confidence", topScore);
        if (fallback) meterRegistry.counter("resolver.fallback").increment();

        return new ResolverResult(selected, skipped, fallback, topScore, prompt);
    }

    /** Convenience overload — no domain filter. */
    public ResolverResult resolve(String prompt) {
        return resolve(prompt, null);
    }
}
