package ai.conduit.gateway.synthesis.input;

/**
 * How a {@link Mention} entered the conversation.
 *
 * <ul>
 *   <li>{@link #EXPLICIT} — the reference is stated verbatim in the LATEST user message.</li>
 *   <li>{@link #ANAPHORA} — the latest message only back-references the entity (a pronoun such
 *       as "that"/"them"); the verbatim text is carried from an earlier user turn.</li>
 * </ul>
 *
 * <p>Structural, language-generic provenance — no domain knowledge (World-B clean).
 */
public enum MentionSource {
    EXPLICIT,
    ANAPHORA
}
