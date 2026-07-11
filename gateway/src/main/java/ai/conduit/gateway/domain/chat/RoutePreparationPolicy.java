package ai.conduit.gateway.domain.chat;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Config-driven knobs for the pre-routing preparation pipeline (routing spec V2 Piece 3). Bound from
 * {@code conduit.routing.*} by {@code RoutePreparationConfig} — there is NO Java static word list
 * (CLAUDE.md §5): the mask token and the near-empty stopword set both come from configuration.
 *
 * @param maskToken            the neutral deictic that replaces a resolved entity span in routing
 *                             text (e.g. {@code "the subject"}). Must not be an
 *                             {@code EntityType.display/resolveType/key} — {@link RoutePreparer}
 *                             enforces that ban at prepare time and falls back to a pure deictic.
 * @param residualStopwords    lowercase function/request words that do NOT count as routable action
 *                             text when deciding whether a masked residual is "near-empty".
 * @param minContentTokens     the minimum number of non-stopword, non-mask-token content tokens a
 *                             masked residual must carry to be considered non-empty.
 */
public record RoutePreparationPolicy(
        String maskToken,
        Set<String> residualStopwords,
        int minContentTokens) {

    public RoutePreparationPolicy {
        residualStopwords = residualStopwords == null ? Set.of() : Set.copyOf(residualStopwords);
        minContentTokens = Math.max(1, minContentTokens);
    }

    /** Parses a comma/whitespace-separated stopword string (config form) into a lowercase set. */
    public static Set<String> parseStopwords(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String tok : raw.split("[,\\s]+")) {
            String t = tok.strip().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
