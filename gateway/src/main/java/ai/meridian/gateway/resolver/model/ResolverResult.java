package ai.meridian.gateway.resolver.model;

import ai.meridian.gateway.registry.model.RoutingCandidate;

import java.util.List;

/**
 * Output of the resolver — the full routing decision for one user prompt.
 *
 * selected:    agents that passed the confidence floor and domain filter
 * skipped:     candidates seen but below the floor (shown struck-out in the glass-box)
 * fallback:    true when no agent cleared the threshold (triggers clarification path)
 * topScore:    highest similarity score seen (for diagnostics)
 */
public record ResolverResult(
        List<RoutingCandidate> selected,
        List<RoutingCandidate> skipped,
        boolean fallback,
        double topScore,
        String prompt
) {
    public boolean hasAgents() {
        return selected != null && !selected.isEmpty();
    }
}
