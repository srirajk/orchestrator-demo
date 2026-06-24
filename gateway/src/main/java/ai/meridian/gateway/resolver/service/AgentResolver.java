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

        // Stage B: apply confidence floor
        List<RoutingCandidate> selected = candidates.stream()
                .filter(c -> c.score() >= confidenceFloor)
                .toList();

        boolean fallback = selected.isEmpty();

        log.debug("Resolver: prompt='{}' → {} candidates, {} selected (floor={}), topScore={}",
                prompt, candidates.size(), selected.size(), confidenceFloor, topScore);

        meterRegistry.gauge("resolver.route.confidence", topScore);
        if (fallback) meterRegistry.counter("resolver.fallback").increment();

        return new ResolverResult(selected, fallback, topScore, prompt);
    }

    /** Convenience overload — no domain filter. */
    public ResolverResult resolve(String prompt) {
        return resolve(prompt, null);
    }
}
