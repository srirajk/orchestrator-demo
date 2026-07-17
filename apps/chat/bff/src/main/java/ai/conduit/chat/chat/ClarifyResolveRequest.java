package ai.conduit.chat.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code POST /api/clarify/resolve} — a structured-clarification RESUME. The SPA submits the
 * user's answer to an outstanding form: either a {@code selection} (one offered option's value) or a
 * {@code freeText} escape answer, tied to the form's single-use {@code nonce}.
 *
 * <p>The BFF folds this answer into a normal user turn and re-drives the gateway's chat path with the
 * nonce (+ selection) so the FULL pipeline re-runs and entitlement is re-CHECKed — the form is never
 * trusted as authorization. Free text is untrusted DATA (hard-rule 4c).
 *
 * @param conversationId the conversation the outstanding form belongs to (ownership is verified)
 * @param nonce          the form's single-use token (required — identifies the descriptor to consume)
 * @param selection      the chosen option's value for a chip selection; null/blank for a free-text escape
 * @param freeText       the free-text escape answer; null/blank for a chip selection
 */
public record ClarifyResolveRequest(
        @NotBlank(message = "is required") String conversationId,
        @NotBlank(message = "is required") String nonce,
        String selection,
        String freeText) {

    /** True when at least one of selection / freeText carries an answer. */
    public boolean hasAnswer() {
        return notBlank(selection) || notBlank(freeText);
    }

    /**
     * The user turn folded from this answer for persistence + display + free-text routing. Free text wins
     * when present (a genuine escape answer); otherwise the chosen option's value. A GROUNDED_SELECTION
     * re-drives the descriptor's ORIGINAL query gateway-side, so this folded content is not the routing
     * source for a valid chip pick — it is the human-visible turn.
     */
    public String foldedContent() {
        if (notBlank(freeText)) return freeText.strip();
        if (notBlank(selection)) return selection.strip();
        return "";
    }

    /** The chip selection value to carry to the gateway ({@code X-Clarify-Selection}), or null. */
    public String selectionHeader() {
        return notBlank(selection) ? selection.strip() : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
