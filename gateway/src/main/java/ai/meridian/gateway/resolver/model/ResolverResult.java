package ai.meridian.gateway.resolver.model;

import ai.meridian.gateway.registry.model.RoutingCandidate;

import java.util.List;

/**
 * Output of the resolver — the full routing decision for one user prompt.
 *
 * selected:    agents that passed the confidence floor and domain filter
 * fallback:    true when no agent cleared the threshold (triggers clarification path)
 * topScore:    highest similarity score seen (for diagnostics)
 */
public record ResolverResult(
        List<RoutingCandidate> selected,
        boolean fallback,
        double topScore,
        String prompt
) {
    public boolean hasAgents() {
        return selected != null && !selected.isEmpty();
    }
}
