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
     *
     * <p><b>Piece 5 — {@code multiple} now carries an explicit shortlist.</b> A multi-facet answer no
     * longer means "keep everything the embedding search returned"; it names the precise subset of
     * shortlist ids that together cover the request ({@link #candidateIds}), one id per distinct facet.
     * The caller validates them (in-shortlist, unique, non-empty, capped) and Piece 4 forms one
     * requested group per selected id. A {@code multiple} with no explicit ids (or an unusable list) is
     * an <i>invalid</i> multi-facet answer — the caller decides how to treat it per trigger path. The
     * embedding {@code score} the re-ranker was shown is diagnostic input only; it never becomes the
     * selection — {@link #candidateIds} is the selection.
     */
    record Decision(String candidateId, List<String> candidateIds, boolean abstain,
                    boolean needsMultiple, String reason) {

        public Decision {
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        }

        public static Decision pick(String candidateId, String reason) {
            return new Decision(candidateId, List.of(), false, false, reason);
        }

        /** The candidates are indistinguishable or none fit → the caller clarifies. */
        public static Decision abstain(String reason) {
            return new Decision(null, List.of(), true, false, reason);
        }

        /**
         * No single agent serves this query — the named shortlist ids each cover a distinct requested
         * facet. The caller validates the ids against the shortlist it presented and (Piece 4) forms one
         * requested group per id.
         */
        public static Decision multiple(List<String> candidateIds, String reason) {
            return new Decision(null, candidateIds, false, true, reason);
        }

        /**
         * A multi-facet answer with no explicit shortlist — the re-ranker declined a single winner but
         * did not (or could not) name the facets. Retained for callers that only need the "fan out"
         * signal; {@link #multiple(List, String)} is preferred so Piece 4 can name the groups. Treated
         * as an invalid {@code multiple} by the id validation (empty list).
         */
        public static Decision needsMultiple(String reason) {
            return new Decision(null, List.of(), false, true, reason);
        }

        /** True when {@code multiple} named a non-empty, unique shortlist subset. Validated by the caller. */
        public boolean hasExplicitCandidateIds() {
            return candidateIds != null && !candidateIds.isEmpty();
        }
    }
}
