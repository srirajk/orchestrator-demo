package ai.conduit.gateway.domain.clarify;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The structured (out-of-band) rendering of a {@link ClarificationDescriptor} — the form payload the
 * SPA renders. It is emitted on the existing glass-box trace lane (a {@code structured_interaction}
 * {@link ai.conduit.gateway.infrastructure.telemetry.TraceEvent}, the {@code TraceStreamController}
 * pattern), NOT on the OpenAI chat SSE. The plain-text twin of the SAME descriptor goes on the chat
 * SSE. Both derive from one descriptor so they cannot drift.
 *
 * <p><b>Not a tool-call.</b> CLARIFY is deterministic; this is never an OpenAI {@code tool_calls}
 * payload (a tool-call is a forged model utterance that would end the completion). The chat SSE
 * carries a normal assistant message with {@code finish_reason:"stop"}; this envelope rides the
 * separate out-of-band lane.
 *
 * <p><b>Self-describing (World B).</b> Every field is DATA — labels from {@code entity_types} +
 * manifest copy + domain-agnostic templates — so the SPA renders it blind, with zero domain
 * knowledge on the client. There is deliberately NO "N more hidden" count anywhere: a count is itself
 * an enumeration oracle. The {@link #options} are the ONLY entities that passed the entitlement CHECK,
 * plus the always-present {@link #freeText} escape.
 *
 * @param kind          the interaction discriminator (extensible; {@code consent} reserved, not built)
 * @param nonce         single-use token tying this form to its server-side descriptor (Phase-2 submit)
 * @param question      the base question text (manifest copy) — shared with the plain-text twin
 * @param entityNoun    manifest display noun for the slot being clarified; may be null
 * @param options       the entitled, capped candidate choices (may be empty for a pure free-text ask)
 * @param freeText      the always-present "none of these — type it" escape (rule: never trap the user)
 * @param clarifyDepth  this form's depth in the query lineage (loop bound)
 * @param maxClarifyDepth the hard depth cap for the lineage
 * @param atCap         true when this is the last clarification the lineage may offer
 * @param blocking      true = this form REPLACES a no-service answer; false = non-blocking refinement
 *                      chips shown BESIDE an already-streamed answer (partial-answer split)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StructuredInteraction(
        InteractionKind kind,
        String nonce,
        String question,
        String entityNoun,
        List<ClarificationOption> options,
        FreeTextEscape freeText,
        int clarifyDepth,
        int maxClarifyDepth,
        boolean atCap,
        boolean blocking) {

    public StructuredInteraction {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /**
     * The free-text escape hatch. Its input is UNTRUSTED DATA (hard-rule 4c): when a Phase-2 resume
     * feeds a free-text answer into synthesis it must be delimited as DATA, never treated as an
     * instruction. {@link #inputContract} carries that contract literally on the wire so the property
     * is explicit end-to-end, not implicit.
     *
     * @param enabled       always true — the user can always opt out of the offered set
     * @param prompt        domain-agnostic escape copy (a template, not domain copy)
     * @param inputContract the fixed marker {@link #DATA} — free-text is untrusted DATA for synthesis
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FreeTextEscape(boolean enabled, String prompt, String inputContract) {
        /** The only legal input-contract value: free-text is untrusted DATA (rule 4c), never instructions. */
        public static final String DATA = "data";

        public static FreeTextEscape enabled(String prompt) {
            return new FreeTextEscape(true, prompt, DATA);
        }
    }
}
