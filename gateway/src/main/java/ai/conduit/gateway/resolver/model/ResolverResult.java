package ai.conduit.gateway.resolver.model;

import ai.conduit.gateway.registry.model.RoutingCandidate;

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
        String prompt,
        double margin,
        boolean rerankFired,
        List<String> rerankSelectedIds,
        PrimaryCandidate primaryCandidate
) {
    /**
     * The post-rerank routing leader, captured pre-authorization-filter as DIAGNOSTIC metadata (routing
     * spec V2 Piece 4). It is NOT the fulfilment contract — the {@link RequestedPlan} group is — it only
     * names the leading agent (with its sub-domain + domain, resolved from the manifest) for the trace /
     * glass-box. {@code null} when nothing was selected. World-B: opaque ids only.
     */
    public record PrimaryCandidate(String agentId, String subDomainId, String domain) {}

    /**
     * Null-guard the reranker's explicit shortlist selection.
     *
     * <p>{@code rerankSelectedIds} is populated only when the re-ranker returned a valid multi-facet
     * {@code multiple([ids])} (Piece 5): each id is a distinct requested facet, validated in-shortlist
     * and unique. Empty on every other path (single pick, abstain, no rerank). Piece 4 reads it to form
     * one requested capability group per selected id.
     */
    public ResolverResult {
        rerankSelectedIds = rerankSelectedIds == null ? List.of() : List.copyOf(rerankSelectedIds);
    }

    public ResolverResult(List<RoutingCandidate> selected,
                          List<RoutingCandidate> skipped,
                          boolean fallback,
                          double topScore,
                          String prompt,
                          double margin,
                          boolean rerankFired,
                          List<String> rerankSelectedIds) {
        this(selected, skipped, fallback, topScore, prompt, margin, rerankFired, rerankSelectedIds, null);
    }

    public ResolverResult(List<RoutingCandidate> selected,
                          List<RoutingCandidate> skipped,
                          boolean fallback,
                          double topScore,
                          String prompt,
                          double margin,
                          boolean rerankFired) {
        this(selected, skipped, fallback, topScore, prompt, margin, rerankFired, List.of(), null);
    }

    public ResolverResult(List<RoutingCandidate> selected,
                          List<RoutingCandidate> skipped,
                          boolean fallback,
                          double topScore,
                          String prompt) {
        this(selected, skipped, fallback, topScore, prompt, 0.0, false, List.of(), null);
    }

    /** Returns a copy with the diagnostic {@link PrimaryCandidate} attached (ChatService, post-resolve). */
    public ResolverResult withPrimaryCandidate(PrimaryCandidate primaryCandidate) {
        return new ResolverResult(selected, skipped, fallback, topScore, prompt, margin, rerankFired,
                rerankSelectedIds, primaryCandidate);
    }

    public boolean hasAgents() {
        return selected != null && !selected.isEmpty();
    }
}
