package ai.conduit.gateway.infrastructure.telemetry.event;

import ai.conduit.gateway.domain.chat.PreparedRoute;

/**
 * Glass-box / audit frame for the pre-routing preparation pipeline (routing spec V2 Piece 3). Records
 * the masking mode, mention + masked-span counts, alignment misses, the residual class, the relaxation
 * decision, and stable (non-reversible) hashes of the raw and masked routing text — NEVER any raw
 * entity value. It lets an auditor confirm that resolved entities were masked before routing without
 * ever seeing the names.
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

    public static RoutePreparedData from(PreparedRoute.MaskDiagnostics d, boolean relaxationAllowed) {
        return new RoutePreparedData(d.maskMode(), d.mentionCount(), d.maskedSpanCount(),
                d.alignmentMisses(), d.residualClass(), relaxationAllowed,
                d.rawTextHash(), d.maskedTextHash());
    }
}
