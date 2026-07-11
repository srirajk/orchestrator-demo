package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;

/**
 * The shared, span-aware output of the pre-routing preparation pipeline (routing spec V2 Piece 3).
 *
 * <p>{@link RoutePreparer} derives this once per turn and every route-consuming path (FETCH_DATA,
 * FOLLOW_UP fallthrough, and — later — the Piece-6 decision endpoint) feeds the SAME instance to the
 * resolver, so the entity/window/mask/relaxation logic lives in one place instead of three drifting
 * copies. The reranker sees the same {@link #maskedRoutingText} automatically (resolveContextual →
 * select → rerank).
 *
 * <p><b>Interim scope.</b> This carries the full {@link #groundedSet} so Piece 4 (group model) can
 * consume every mention's interpretations; Piece 3 itself only uses it for masking + relaxation and
 * leaves the terminal disposition to the existing focal {@code ground()} lattice.
 *
 * @param maskedRoutingText the routing text handed to the resolver: the chosen turn window with every
 *                          resolved entity span replaced by a neutral deictic and, when the base
 *                          residual was empty, widened to the full masked window.
 * @param groundedSet       the full multi-reference grounding result (all mentions, all
 *                          interpretations) — carried for Piece 4; here it drives masking + relaxation.
 * @param relaxationAllowed whether the resolver may relax its margin-abstain (bias-to-fetch). True
 *                          ONLY when the focal reference RESOLVED_ALLOWED and its span was masked
 *                          (masking complete), or a deterministic non-coverage-scoped id_pattern
 *                          matched (V2.1 #4). An alignment miss / unmasked resolved mention forfeits
 *                          relaxation — normal score/margin abstention then applies.
 * @param residualClass     how much action text survived masking (drives the widen decision + trace).
 * @param maskDiagnostics   glass-box/audit fingerprint of the masking pass (no raw entity values).
 */
public record PreparedRoute(
        String maskedRoutingText,
        GroundedReferenceSet groundedSet,
        boolean relaxationAllowed,
        ResidualClass residualClass,
        MaskDiagnostics maskDiagnostics) {

    /**
     * How much routable action text survived masking of the chosen window.
     * <ul>
     *   <li>{@code HAS_ACTION} — the base window still carried content after masking (no widen).</li>
     *   <li>{@code WIDENED}    — the base residual was near-empty, so routing widened to the full
     *       masked window, which does carry action text.</li>
     *   <li>{@code EMPTY}      — even the full masked window is near-empty; the resolver will abstain
     *       and the existing required-entity clarify handles it.</li>
     * </ul>
     */
    public enum ResidualClass { HAS_ACTION, WIDENED, EMPTY }

    /**
     * Non-reversible fingerprint of one preparation pass for the glass-box / audit trail. Carries no
     * raw entity value — only counts, the mode, the residual class, and stable hashes of the raw and
     * masked routing text (so an auditor can confirm masking happened without seeing the names).
     */
    public record MaskDiagnostics(
            String maskMode,
            int mentionCount,
            int maskedSpanCount,
            int alignmentMisses,
            String residualClass,
            String rawTextHash,
            String maskedTextHash) {}
}
