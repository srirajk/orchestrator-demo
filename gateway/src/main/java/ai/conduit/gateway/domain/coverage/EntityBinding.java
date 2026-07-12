package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;

import java.util.List;

/**
 * One entity (one resolved {@code canonicalId} of one manifest {@code entityKey}) that a single turn
 * named EXPLICITLY, together with every grounded interpretation of that id. The unit a multi-entity
 * COMPARE request fans out over: {@link ai.conduit.gateway.domain.chat.RequestedPlan.RequestedGroup}
 * carries one binding per entity, so each per-client agent call binds THIS entity's own coverage-checked
 * id and no other (the no-leak invariant — a served id can only ever be one that passed this entity's own
 * CHECK).
 *
 * <p><b>World-B.</b> Every field is resolver-produced runtime data (a canonical id, the user's own
 * verbatim words, a manifest {@code EntityType.key()}) or a structural index — no domain literal, id
 * pattern, or entity-type word is authored here. Bound ids originate exclusively from the coverage
 * resolver via {@link GroundedInterpretation#canonicalId()} (rule 4b — the LLM never produces an id).
 *
 * @param canonicalId    the resolver-produced canonical id (never LLM-authored). For a fail-closed
 *                       {@code UNAVAILABLE}-only binding this is the raw reference carried for the trace.
 * @param userVerbatim   the mention EXACTLY as the user wrote it — used to attribute a withheld entity
 *                       WITHOUT disclosing the resolver's canonical name (S4 parity).
 * @param entityKey      the manifest {@code EntityType.key()} this id resolves into.
 * @param interpretations this id's per-sub-domain grounded interpretations (each with its own status).
 * @param mentionOrder   recorded order = the order the user named the entity (deterministic capping).
 */
public record EntityBinding(
        String canonicalId,
        String userVerbatim,
        String entityKey,
        List<GroundedInterpretation> interpretations,
        int mentionOrder) {

    public EntityBinding {
        interpretations = interpretations == null ? List.of() : List.copyOf(interpretations);
    }

    /** True when this id passed its own coverage CHECK for at least one sub-domain interpretation. */
    public boolean hasAllowed() {
        return interpretations.stream().anyMatch(GroundedInterpretation::isAllowed);
    }

    /** True when this id has a terminal coverage DENY on at least one sub-domain interpretation. */
    public boolean hasDenied() {
        return interpretations.stream().anyMatch(GroundedInterpretation::isDenied);
    }

    /** True when this id could not be verified (coverage unreachable) on at least one interpretation. */
    public boolean hasUnavailable() {
        return interpretations.stream()
                .anyMatch(gi -> gi.status() == GroundStatus.UNAVAILABLE);
    }

    /** The resolver's canonical entity name (from the first interpretation that carries one), or null. */
    public String canonicalName() {
        return interpretations.stream()
                .map(GroundedInterpretation::canonicalName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst().orElse(null);
    }

    /**
     * The terminal denial reason code for a withheld entity: the first DENIED interpretation's reason,
     * else {@code "coverage-unavailable"} for a fail-closed UNAVAILABLE, else {@code null}.
     */
    public String terminalDenialReason() {
        for (GroundedInterpretation gi : interpretations) {
            if (gi.isDenied()) return gi.denialReason();
        }
        return hasUnavailable() ? "coverage-unavailable" : null;
    }

    /** The owning sub-domain id of the terminal denial (for the manifest denial-copy lookup), or null. */
    public String terminalDeniedSubDomainId() {
        for (GroundedInterpretation gi : interpretations) {
            if (gi.isDenied()) return gi.subDomainId();
        }
        for (GroundedInterpretation gi : interpretations) {
            if (gi.status() == GroundStatus.UNAVAILABLE) return gi.subDomainId();
        }
        return null;
    }
}
