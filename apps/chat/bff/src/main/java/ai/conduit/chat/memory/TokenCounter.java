package ai.conduit.chat.memory;

import ai.conduit.chat.config.AppProperties;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Estimates the token size of text using a real JVM tiktoken implementation
 * ({@code jtokkit}). The encoding is config-driven ({@code conduit.chat.context.token-encoding},
 * e.g. {@code cl100k_base} or {@code o200k_base}).
 *
 * <p>If the configured encoding cannot be resolved (or the library is unavailable at
 * runtime) the component degrades gracefully to a {@code chars/4} heuristic so context
 * assembly never fails on a tokenizer problem.
 */
@Component
public class TokenCounter {

    private static final Logger log = LoggerFactory.getLogger(TokenCounter.class);

    /** Rough bytes/token ratio used only when the tokenizer is unavailable. */
    static final int HEURISTIC_CHARS_PER_TOKEN = 4;

    private final Encoding encoding;

    public TokenCounter(AppProperties appProperties) {
        String encodingName = appProperties.context().tokenEncoding();
        Encoding resolved = null;
        try {
            EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
            resolved = registry.getEncoding(encodingName).orElse(null);
            if (resolved == null) {
                log.warn("[tokens] unknown encoding '{}'; falling back to chars/{} heuristic",
                        encodingName, HEURISTIC_CHARS_PER_TOKEN);
            }
        } catch (RuntimeException | LinkageError ex) {
            log.warn("[tokens] tokenizer unavailable ({}); falling back to chars/{} heuristic",
                    ex.getMessage(), HEURISTIC_CHARS_PER_TOKEN);
        }
        this.encoding = resolved;
    }

    /**
     * @return estimated token count of {@code text} (0 for null/empty).
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (encoding != null) {
            try {
                return encoding.countTokens(text);
            } catch (RuntimeException ex) {
                log.debug("[tokens] countTokens failed ({}); using heuristic", ex.getMessage());
            }
        }
        return heuristic(text);
    }

    private static int heuristic(String text) {
        return Math.max(1, (text.length() + HEURISTIC_CHARS_PER_TOKEN - 1) / HEURISTIC_CHARS_PER_TOKEN);
    }
}
