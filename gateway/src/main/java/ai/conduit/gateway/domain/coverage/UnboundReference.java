package ai.conduit.gateway.domain.coverage;

import java.util.List;

/**
 * One entity reference the user named this turn that the pipeline did NOT bind — the signal that a
 * multi-entity COMPARE would otherwise silently serve one-sided (or abstain). Produced by
 * {@link ReferenceGroundingService#detectUnboundReferences} and consumed ONLY to DECIDE a deterministic
 * CLARIFY (Compare-CLARIFY spec §1). It never auto-binds and never reaches an agent call.
 *
 * <p><b>SECURITY / no-info-disclosure.</b> {@link #verbatim} is the user's OWN words, EXACTLY as typed —
 * it is the only field ever rendered to the user (as the {@code {unresolved}} placeholder). {@link
 * #resolvedId} (Tier-B's principal-agnostic resolve result) is carried ONLY so the detector can apply the
 * "∉ bound ids" guard; it MUST NOT be disclosed — we never tell the user "that resolves to REL-00099".
 *
 * <p><b>World-B.</b> Every field is the user's verbatim, a resolver-produced id, or a boolean/list — no
 * domain literal is authored here.
 *
 * @param verbatim     the reference EXACTLY as the user wrote it (the ONLY field rendered to the user).
 * @param resolvedId   Tier-B's unique principal-agnostic resolution (for the ∉-bound guard), or {@code
 *                     null} for a Tier-A (grounding-status) detection. NEVER rendered.
 * @param ambiguous    {@code true} when this reference is AMBIGUOUS (Tier-A grounding status) — carried
 *                     for a possible discover∩ suggestion; never a confirmation on its own in Tier B.
 * @param candidateIds any disambiguation candidate ids associated with an ambiguous Tier-A reference
 *                     (empty otherwise) — intersected with the caller's book before any suggestion render.
 */
public record UnboundReference(
        String verbatim,
        String resolvedId,
        boolean ambiguous,
        List<String> candidateIds) {

    public UnboundReference {
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
    }

    static UnboundReference tierA(String verbatim, boolean ambiguous, List<String> candidateIds) {
        return new UnboundReference(verbatim, null, ambiguous, candidateIds);
    }

    static UnboundReference tierB(String verbatim, String resolvedId) {
        return new UnboundReference(verbatim, resolvedId, false, List.of());
    }
}
