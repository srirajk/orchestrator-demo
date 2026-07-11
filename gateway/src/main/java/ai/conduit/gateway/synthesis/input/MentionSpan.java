package ai.conduit.gateway.synthesis.input;

/**
 * A half-open {@code [start, end)} character span over the ORIGINAL text of a single message
 * (the client-sent content, before any trimming or join). Offsets index the original message so
 * a later stage can mask exactly this range without translating offsets through {@code trim()} +
 * newline concatenation.
 *
 * <p>Gateway-DERIVED, never LLM-emitted (routing spec V2.1 resolution #2). Character offsets from
 * an LLM are unreliable; the gateway computes the span deterministically by aligning the verbatim
 * reference within the message (see {@link MentionAligner}). A span is only ever present when that
 * alignment succeeds — an alignment miss carries a {@code null} span, never a guessed one.
 *
 * @param start inclusive start offset into the original message content
 * @param end   exclusive end offset into the original message content
 */
public record MentionSpan(int start, int end) {
    public MentionSpan {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span [" + start + "," + end + ")");
        }
    }

    public int length() {
        return end - start;
    }
}
