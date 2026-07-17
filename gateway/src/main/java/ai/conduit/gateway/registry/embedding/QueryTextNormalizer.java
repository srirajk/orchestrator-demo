package ai.conduit.gateway.registry.embedding;

import java.util.regex.Pattern;

/**
 * Canonicalises a query string before it is embedded and used as a cache key.
 *
 * <p>The rule is deliberately narrow: {@link String#strip() strip} the ends, then collapse every
 * run of whitespace to a single space. Nothing else. In particular <b>case is preserved</b> — case
 * carries routing meaning (proper nouns, acronyms, product names), so folding it would be an
 * over-normalisation that could change which agent a question routes to. The
 * <em>only</em> difference this erases is incidental whitespace — a trailing newline the client
 * appended, a double space between words — which no embedding model should treat as a distinct
 * question and which no reasonable routing decision should depend on.
 *
 * <p>This function is the sole normalisation applied on the query embedding path, and it is applied
 * <em>whether or not the cache is enabled</em>. That is what makes the cache behaviour-preserving:
 * the request path computes {@code embedder.embed(normalize(text))} either way, and the cache is a
 * pure memoisation of that function keyed by {@code (model-id, normalize(text))}. Turning the cache
 * off changes throughput, never the vector.
 *
 * <p>It is pure, deterministic, allocation-light, and — importantly for World B — carries no domain
 * knowledge: it operates on arbitrary user text, not on any manifest-declared vocabulary.
 */
final class QueryTextNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private QueryTextNormalizer() {
    }

    /** Trim the ends and collapse internal whitespace runs to a single space. Never returns null. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return stripped;
        }
        return WHITESPACE.matcher(stripped).replaceAll(" ");
    }
}
