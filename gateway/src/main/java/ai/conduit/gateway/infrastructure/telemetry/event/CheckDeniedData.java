package ai.conduit.gateway.infrastructure.telemetry.event;

/**
 * Trace event payload emitted when an entitlement CHECK denies access, so the glass-box
 * decision trace shows an explicit deny decision instead of silently jumping from
 * {@code agents_resolved} straight to {@code request_complete}.
 *
 * <p>{@code stage} names which authorization layer denied:
 * <ul>
 *   <li>{@code structural}  — Cerbos agent-invoke deny (audience/segment/classification) — no covered agents</li>
 *   <li>{@code coverage}    — per-domain coverage service says the entity is not in the principal's book</li>
 *   <li>{@code entitlement} — Cerbos resource (relationship) deny</li>
 * </ul>
 */
public record CheckDeniedData(
        String  stage,       // "structural" | "coverage" | "entitlement"
        String  entityId,    // resolved entity id when known, else null
        String  userId,
        String  reason,      // machine-readable reason code from the deny source
        String  source       // "cerbos" | "coverage" | "local-fallback"
) {}
