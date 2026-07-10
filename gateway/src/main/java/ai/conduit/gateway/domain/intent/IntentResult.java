package ai.conduit.gateway.domain.intent;

import ai.conduit.gateway.synthesis.input.EntityBag;

/**
 * Output of {@link IntentClassifier}.
 *
 * @param intent           the classified routing label
 * @param confidence       0.0–1.0 as returned by the LLM
 * @param reasoning        one-line rationale from the LLM
 * @param extractedEntities entity bag extracted in the same LLM call (non-null for FETCH_DATA)
 */
public record IntentResult(Intent intent, double confidence, String reasoning,
                            EntityBag extractedEntities) {

    // No fetchDataFallback(): a classifier failure must never be papered over with a canned
    // FETCH_DATA intent and an empty entity bag (CLAUDE.md §5). See IntentClassificationException.

    /** Convenience ctor without entities (for FOLLOW_UP / CHITCHAT / CLARIFY). */
    public static IntentResult of(Intent intent, double confidence, String reasoning) {
        return new IntentResult(intent, confidence, reasoning, null);
    }
}
