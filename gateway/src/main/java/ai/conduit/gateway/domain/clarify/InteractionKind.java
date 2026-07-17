package ai.conduit.gateway.domain.clarify;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The extensible discriminator for a {@link StructuredInteraction} — what KIND of structured
 * human-in-the-loop turn the gateway is offering. This is a deliberate HITL seam: the envelope +
 * descriptor pattern are shaped so new interaction kinds can be added without a new transport.
 *
 * <ul>
 *   <li>{@link #CLARIFY_ENTITY} — disambiguate which manifest-declared entity the caller means, over
 *       a set of ENTITLED candidates (the coverage AMBIGUOUS / abstain-triage surfaces).</li>
 *   <li>{@link #CLARIFY_CAPABILITY} — disambiguate which capability to invoke, over the
 *       Cerbos-filtered capability set.</li>
 * </ul>
 *
 * <p><b>Reserved (NOT implemented in this phase):</b> a {@code consent} kind — an approval/authorize
 * turn — is a future member of this lattice. It is deliberately absent: no signing, no
 * separation-of-duties, no action parameters are built here. The discriminator is String-backed so
 * that reserved kind can be added later without breaking the wire contract; unknown kinds
 * deserialize as {@code null} rather than throwing.
 */
public enum InteractionKind {
    CLARIFY_ENTITY("clarify_entity"),
    CLARIFY_CAPABILITY("clarify_capability");

    private final String wire;

    InteractionKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** Lenient parse — an unknown/reserved kind (e.g. a future {@code consent}) yields null, never throws. */
    @JsonCreator
    public static InteractionKind fromWire(String wire) {
        if (wire == null) return null;
        for (InteractionKind k : values()) {
            if (k.wire.equals(wire)) return k;
        }
        return null;
    }
}
