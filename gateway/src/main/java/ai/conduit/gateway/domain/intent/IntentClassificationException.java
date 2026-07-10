package ai.conduit.gateway.domain.intent;

/**
 * Thrown when intent classification cannot be completed — the LLM is unreachable, rate limited,
 * or returned a response that could not be parsed.
 *
 * <p>Deliberately <em>not</em> recoverable with a canned intent. Substituting a FETCH_DATA result
 * carrying an empty entity bag makes the deterministic CLARIFY rule
 * ({@code extracted ∩ required_context = ∅}) fire, so a provider outage reaches the user as
 * "which client did you mean?" — an assertion about the user's question that is simply false.
 * The caller surfaces the error instead (CLAUDE.md §5: never return canned/fallback data when the
 * LLM is unreachable).
 */
public class IntentClassificationException extends RuntimeException {

    public IntentClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
