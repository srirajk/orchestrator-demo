package ai.conduit.gateway.synthesis.input;

/**
 * One extracted reference to an entity, keyed by manifest entity {@code key}. Replaces the old
 * "one scalar per resolvable field" model so that multiple references of the SAME entity kind
 * ("compare X and Y", two ids) and span-precise masking are representable.
 *
 * <p>Everything here is either a manifest key ({@link #entityKey}, {@link #extractAs}), a verbatim
 * copy of the user's own words ({@link #verbatimText}), a structural index/offset, or a generic
 * provenance enum — no domain literal, id pattern, or entity-type word (World-B clean).
 *
 * @param entityKey    manifest {@code EntityType.key()} this reference resolves into.
 * @param extractAs    manifest {@code EntityType.extract_as()} the reference was extracted under.
 * @param verbatimText the reference EXACTLY as the user wrote it (never normalized or an id the
 *                     LLM recalled).
 * @param messageIndex index of the message (in the client-sent window) the reference appears in,
 *                     or {@code -1} when the owning message could not be determined.
 * @param span         {@code [start,end)} over the ORIGINAL {@code messageIndex} message content,
 *                     or {@code null} on an alignment miss (recorded, never guessed).
 * @param source       {@link MentionSource#EXPLICIT} (stated in the latest turn) or
 *                     {@link MentionSource#ANAPHORA} (carried from an earlier turn).
 */
public record Mention(
        String entityKey,
        String extractAs,
        String verbatimText,
        int messageIndex,
        MentionSpan span,
        MentionSource source
) {
    /** True when a deterministic span was computed for this mention. */
    public boolean aligned() {
        return span != null;
    }

    /** True when the reference is stated in the latest user turn. */
    public boolean explicit() {
        return source == MentionSource.EXPLICIT;
    }
}
