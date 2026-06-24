package ai.meridian.gateway.domain.intent;

/**
 * Output of {@link IntentClassifier}.
 *
 * @param intent     the classified routing label
 * @param confidence 0.0–1.0 as returned by the LLM (for observability / thresholding)
 * @param reasoning  one-line rationale from the LLM (logged in the OTel span)
 */
public record IntentResult(Intent intent, double confidence, String reasoning) {

    /** Fallback used when the classifier itself fails. */
    public static IntentResult fetchDataFallback() {
        return new IntentResult(Intent.FETCH_DATA, 0.5, "classifier-fallback");
    }
}
