package ai.conduit.gateway.resolver.service;

import ai.conduit.gateway.registry.model.RoutingCandidate;

import java.util.List;

/**
 * Second-pass selector for ambiguous embedding results.
 */
public interface RoutingRerankerClient {

    Decision rerank(String queryText, List<RoutingCandidate> candidates) throws Exception;

    /**
     * The re-ranker's answer. Three outcomes, deliberately distinct — two of them used to be one.
     *
     * <p>The re-ranker exists to break a near-tie by picking a single winner. It can fail to do that
     * for two entirely different reasons, and they demand opposite responses:
     * <ul>
     *   <li>{@link #abstain} — "these candidates are too close, or none genuinely match." The query is
     *       ambiguous; clarify.</li>
     *   <li>{@link #needsMultiple} — "no SINGLE candidate can answer this; it needs several." The query
     *       is not ambiguous at all, it is multi-facet. Fan out.</li>
     * </ul>
     *
     * <p>Collapsing the second into the first is what made the hero prompt ("a full portfolio review:
     * holdings, YTD performance, risk profile, settlement status, corporate actions") return zero
     * agents and ask the user to be more specific — after the embedding search had already found ten
     * strong candidates with a confident leader.
     *
     * <p>The outcome is structured, never inferred from {@code reason}: that text is LLM prose.
     */
    record Decision(String candidateId, boolean abstain, boolean needsMultiple, String reason) {
        public static Decision pick(String candidateId, String reason) {
            return new Decision(candidateId, false, false, reason);
        }

        /** The candidates are indistinguishable or none fit → the caller clarifies. */
        public static Decision abstain(String reason) {
            return new Decision(null, true, false, reason);
        }

        /** One agent cannot serve this query → the caller keeps the candidate set and fans out. */
        public static Decision needsMultiple(String reason) {
            return new Decision(null, false, true, reason);
        }
    }
}
