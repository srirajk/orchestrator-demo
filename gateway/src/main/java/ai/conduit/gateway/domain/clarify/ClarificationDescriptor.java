package ai.conduit.gateway.domain.clarify;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The ONE server-side source object for a structured clarification. It renders TWICE — a plain-text
 * question onto the OpenAI chat SSE (the legacy, byte-correct path) and a {@link StructuredInteraction}
 * form onto the out-of-band glass-box lane — and BOTH renders read this single object, so they cannot
 * drift. It is also the state Phase-2 resume consumes: stored in Redis (gateway namespace) keyed by
 * {@code conversationId}, single-use, TTL-bounded, and invalidated latest-turn-wins by any newer
 * free-text user turn.
 *
 * <p><b>Security (enumeration-oracle fix).</b> {@link #offeredCandidates} are ONLY entities that
 * already passed the entitlement CHECK — the {@link ClarificationDescriptorFactory} intersects raw
 * resolve candidates against the principal's entitled book before they reach this object. There is no
 * hidden-count field: a count of withheld candidates is itself an oracle.
 *
 * <p><b>World B.</b> Every string here is DATA (manifest {@code entity_types} display, coverage labels,
 * manifest clarification copy, domain-agnostic templates). The gateway authors no domain literal.
 *
 * @param nonce             single-use token; a resume must present it (Phase 2)
 * @param conversationId    the Redis key scope
 * @param kind              interaction discriminator ({@code consent} reserved, not implemented)
 * @param entityNoun        manifest display noun for the clarified slot; may be null
 * @param question          base question text (manifest copy) — the shared core of both renders
 * @param plainText         the fully authored SSE text (deterministic template OR composed wording) —
 *                          the plain-text twin; streamed verbatim on the chat SSE
 * @param offeredCandidates the entitled, capped choices (the offered set the submit predicate checks)
 * @param freeTextEnabled   always true — the free-text escape is never removed
 * @param freeTextPrompt    domain-agnostic escape copy
 * @param idPattern         the slot entity's manifest {@code id_pattern} (Phase-2 submit id validation)
 * @param originatingQuery  the user query that triggered this clarification (untrusted; carried for resume)
 * @param clarifyDepth      this clarification's depth in the query lineage (loop bound)
 * @param maxClarifyDepth   the hard depth cap
 * @param createdAtEpochMs  creation time (TTL diagnostics)
 * @param ttlSeconds        Redis TTL
 * @param singleUse         true — a second consume is rejected
 * @param blocking          true = form replaces no-service; false = non-blocking refinement chips
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClarificationDescriptor(
        String nonce,
        String conversationId,
        InteractionKind kind,
        String entityNoun,
        String question,
        String plainText,
        List<ClarificationOption> offeredCandidates,
        boolean freeTextEnabled,
        String freeTextPrompt,
        String idPattern,
        String originatingQuery,
        int clarifyDepth,
        int maxClarifyDepth,
        long createdAtEpochMs,
        long ttlSeconds,
        boolean singleUse,
        boolean blocking) {

    /** The disposition of a submitted answer against the offered set (Phase-2 enforces on submit). */
    public enum Submission {
        /** The submitted value is one of the offered, entitled candidates. */
        IN_SET,
        /** Not in the offered set — demote to free text (never silently ground it). */
        DEMOTE_TO_FREE_TEXT
    }

    public ClarificationDescriptor {
        offeredCandidates = offeredCandidates == null ? List.of() : List.copyOf(offeredCandidates);
    }

    /** The offered-set: the exact submit tokens a Phase-2 resume may treat as IN_SET. */
    @JsonIgnore
    public List<String> offeredValues() {
        return offeredCandidates.stream().map(ClarificationOption::value).toList();
    }

    /**
     * The offered-set validation predicate (the Phase-2 resume contract). A submission whose token is
     * NOT one of the offered, entitled candidates DEMOTES to free text — it is never silently grounded
     * to an entity the user did not actually pick from an offered choice. Blank/null demotes too.
     */
    public Submission validate(String submitted) {
        if (submitted == null || submitted.isBlank()) return Submission.DEMOTE_TO_FREE_TEXT;
        String needle = submitted.strip();
        for (ClarificationOption o : offeredCandidates) {
            if (o.value().equalsIgnoreCase(needle)) return Submission.IN_SET;
        }
        return Submission.DEMOTE_TO_FREE_TEXT;
    }

    /** True when this clarification is at/over the lineage depth cap — the caller must degrade to no-service. */
    @JsonIgnore
    public boolean atCap() {
        return clarifyDepth >= maxClarifyDepth;
    }

    /**
     * The structured (out-of-band) render. Reads THIS object's {@link #question} + entitled
     * {@link #offeredCandidates} + free-text escape — the same fields the plain-text twin is built from
     * — so mutating the descriptor changes both planes together.
     */
    public StructuredInteraction toStructuredInteraction() {
        StructuredInteraction.FreeTextEscape escape = freeTextEnabled
                ? StructuredInteraction.FreeTextEscape.enabled(freeTextPrompt)
                : null;
        return new StructuredInteraction(kind, nonce, question, entityNoun,
                offeredCandidates, escape, clarifyDepth, maxClarifyDepth, atCap(), blocking);
    }

    // ── Immutable withers — used to prove single-source (mutate → both renders change) and to
    //    down-convert a blocking form into non-blocking refinement chips (partial-answer split). ──

    public ClarificationDescriptor withPlainText(String newPlainText) {
        return new ClarificationDescriptor(nonce, conversationId, kind, entityNoun, question, newPlainText,
                offeredCandidates, freeTextEnabled, freeTextPrompt, idPattern, originatingQuery,
                clarifyDepth, maxClarifyDepth, createdAtEpochMs, ttlSeconds, singleUse, blocking);
    }

    public ClarificationDescriptor withQuestion(String newQuestion) {
        return new ClarificationDescriptor(nonce, conversationId, kind, entityNoun, newQuestion, plainText,
                offeredCandidates, freeTextEnabled, freeTextPrompt, idPattern, originatingQuery,
                clarifyDepth, maxClarifyDepth, createdAtEpochMs, ttlSeconds, singleUse, blocking);
    }

    public ClarificationDescriptor withOfferedCandidates(List<ClarificationOption> newOffered) {
        return new ClarificationDescriptor(nonce, conversationId, kind, entityNoun, question, plainText,
                newOffered, freeTextEnabled, freeTextPrompt, idPattern, originatingQuery,
                clarifyDepth, maxClarifyDepth, createdAtEpochMs, ttlSeconds, singleUse, blocking);
    }

    /** Down-convert to a non-blocking refinement (partial-answer split: chips beside an answer). */
    public ClarificationDescriptor asRefinement() {
        return new ClarificationDescriptor(nonce, conversationId, kind, entityNoun, question, plainText,
                offeredCandidates, freeTextEnabled, freeTextPrompt, idPattern, originatingQuery,
                clarifyDepth, maxClarifyDepth, createdAtEpochMs, ttlSeconds, singleUse, false);
    }
}
