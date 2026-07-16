package ai.conduit.gateway.infrastructure.telemetry.event;

/**
 * Glass-box / audit frame for the pre-routing preparation pipeline (routing spec V2 Piece 3). Records
 * the masking mode, mention + masked-span counts, alignment misses, the residual class, the relaxation
 * decision, and stable (non-reversible) hashes of the raw and masked routing text — NEVER any raw
 * entity value. It lets an auditor confirm that resolved entities were masked before routing without
 * ever seeing the names.
 *
 * <p>This telemetry shape is self-contained — it carries only primitives, so nothing in
 * {@code ..infrastructure..} depends on the ask-side {@code domain.chat} preparation model (the
 * core→ask boundary locked by {@code CoreAskBoundaryTest}). The ask side maps its own
 * {@code PreparedRoute.MaskDiagnostics} fields into {@link #of} at the call site.
 */
public record RoutePreparedData(
        String maskMode,
        int mentionCount,
        int maskedSpanCount,
        int alignmentMisses,
        String residualClass,
        boolean relaxationAllowed,
        String rawTextHash,
        String maskedTextHash) {

    /** Build the frame from already-extracted masking-diagnostic primitives (no ask-model dependency). */
    public static RoutePreparedData of(String maskMode, int mentionCount, int maskedSpanCount,
                                       int alignmentMisses, String residualClass, boolean relaxationAllowed,
                                       String rawTextHash, String maskedTextHash) {
        return new RoutePreparedData(maskMode, mentionCount, maskedSpanCount, alignmentMisses,
                residualClass, relaxationAllowed, rawTextHash, maskedTextHash);
    }
}
