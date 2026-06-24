package ai.meridian.gateway.synthesis.input;

import java.util.List;

/**
 * Holds entity references extracted from a user prompt by the LLM (verbatim),
 * and the resolved system IDs produced by {@link EntityResolver}.
 *
 * Verbatim references come from the LLM — they are exactly what the user typed.
 * Resolved IDs are set deterministically after extraction; the LLM never produces IDs.
 */
public record EntityBag(

        // ── Extracted verbatim by LLM (Extract stage) ────────────────────────
        String relationshipReference,   // e.g. "Whitman Family Office", or null
        String fundReference,           // e.g. "FND-7781" or "Innovation Fund", or null
        List<String> tickerReferences,  // e.g. ["AAPL", "MSFT"], may be empty
        String period,                  // e.g. "QTD" (default when not mentioned)

        // ── Resolved by EntityResolver (Resolve stage) ───────────────────────
        String relationshipId,          // "REL-XXXXX" or null if unresolved
        String fundId,                  // "FND-XXXX" or null if unresolved
        boolean needsClarification      // true when an essential reference could not be resolved

) {

    /**
     * Convenience factory: creates a minimal EntityBag from extracted fields only,
     * with resolved fields left null / false. Used by EntityExtractor before resolution.
     */
    public static EntityBag extracted(String relationshipReference,
                                       String fundReference,
                                       List<String> tickerReferences,
                                       String period) {
        return new EntityBag(
                relationshipReference,
                fundReference,
                tickerReferences == null ? List.of() : List.copyOf(tickerReferences),
                period != null ? period : "QTD",
                null,
                null,
                false
        );
    }

    /** Returns a copy of this bag with resolved IDs and clarification flag applied. */
    public EntityBag withResolved(String relationshipId, String fundId, boolean needsClarification) {
        return new EntityBag(
                this.relationshipReference,
                this.fundReference,
                this.tickerReferences,
                this.period,
                relationshipId,
                fundId,
                needsClarification
        );
    }
}
