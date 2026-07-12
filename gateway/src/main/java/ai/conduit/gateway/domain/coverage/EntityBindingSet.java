package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedMention;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-entity bindings derived from an ALREADY-computed {@link GroundedReferenceSet} — the substrate
 * of multi-entity COMPARE. Pure and deterministic: it makes ZERO new coverage calls, consuming the
 * per-mention RESOLVE+CHECK statuses grounding already produced.
 *
 * <p><b>Derivation (all deterministic, no LLM):</b>
 * <ol>
 *   <li>Consider only mentions with {@code source() == EXPLICIT} <b>and</b> {@code messageIndex() ==}
 *       the latest user turn. A carried ANAPHORA (or a prior-turn) mention NEVER creates a binding —
 *       this keeps the multi-turn SWITCH/CARRY behaviour byte-identical: a carried client can be the
 *       focal subject but can never multiply the fan-out.</li>
 *   <li>Group interpretations by {@code canonicalId} within the same {@code entityKey}. Statuses
 *       {@code RESOLVED_ALLOWED} / {@code DENIED} / {@code UNAVAILABLE} form a binding (they carry a
 *       resolution or a fail-closed obligation); {@code NOT_FOUND} / {@code AMBIGUOUS} (null id) never
 *       do. Two mentions resolving to the SAME id dedupe to one binding.</li>
 *   <li>{@code multiEntity} = the dominant {@code entityKey} has {@code >= 2} distinct-id bindings. At
 *       size {@code <= 1} the whole feature is inert and every existing path is byte-identical.</li>
 * </ol>
 *
 * <p><b>World-B.</b> No domain literal is authored here; every value is runtime resolver data or a
 * manifest key carried through from grounding.
 */
public record EntityBindingSet(List<EntityBinding> bindings, String entityKey) {

    public EntityBindingSet {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public static EntityBindingSet empty() {
        return new EntityBindingSet(List.of(), null);
    }

    /** {@code true} when ≥2 distinct-id bindings of one entity type were named explicitly this turn. */
    public boolean multiEntity() {
        return bindings.size() >= 2;
    }

    /** Number of bindings whose id passed its own coverage CHECK (has ≥1 RESOLVED_ALLOWED interp). */
    public int allowedCount() {
        return (int) bindings.stream().filter(EntityBinding::hasAllowed).count();
    }

    /** True when at least one binding carries a terminal coverage DENY. */
    public boolean anyDenied() {
        return bindings.stream().anyMatch(EntityBinding::hasDenied);
    }

    /** The first withheld binding (a DENY preferred, else a fail-closed UNAVAILABLE), or {@code null}. */
    public EntityBinding firstWithheld() {
        for (EntityBinding b : bindings) {
            if (b.hasDenied()) return b;
        }
        for (EntityBinding b : bindings) {
            if (b.hasUnavailable()) return b;
        }
        return null;
    }

    /**
     * Derive the bindings from a grounded set. Returns {@link #empty()} (feature inert) unless one
     * {@code entityKey} has ≥2 distinct-id EXPLICIT latest-turn bindings. This is the multi-entity
     * COMPARE gate (terminal lattice + per-entity expansion); the single-entity path is byte-identical.
     */
    public static EntityBindingSet derive(GroundedReferenceSet set) {
        List<EntityBinding> dominant = deriveDominant(set);
        if (dominant.size() < 2) return empty();          // the compare feature is inert below two entities
        return new EntityBindingSet(dominant, dominant.get(0).entityKey());
    }

    /**
     * Derive ALL EXPLICIT latest-turn bindings of the dominant entity type — <b>without</b> the ≥2 gate.
     * The Compare-CLARIFY predicate needs the bound-and-covered entities even at size 1 (PC-1 binds only
     * the focal client; the second reference never binds), so this returns a one-binding set where
     * {@link #derive} would return {@link #empty()}. Same deterministic derivation; same EXPLICIT
     * latest-turn rule (a carried anaphora still never binds → S8/S9 safe).
     */
    public static EntityBindingSet deriveAll(GroundedReferenceSet set) {
        List<EntityBinding> dominant = deriveDominant(set);
        if (dominant.isEmpty()) return empty();
        return new EntityBindingSet(dominant, dominant.get(0).entityKey());
    }

    /**
     * The shared, gate-free derivation core: the dominant entity type's EXPLICIT latest-turn bindings, in
     * mention order (may be size 0, 1, or more). {@link #derive} applies the ≥2 gate on top; {@link
     * #deriveAll} does not.
     */
    private static List<EntityBinding> deriveDominant(GroundedReferenceSet set) {
        if (set == null || set.isEmpty()) return List.of();

        // 1) Latest user turn = the highest messageIndex among EXPLICIT mentions.
        int latestTurn = Integer.MIN_VALUE;
        for (GroundedMention gm : set.mentions()) {
            Mention m = gm.mention();
            if (m != null && m.source() == MentionSource.EXPLICIT) {
                latestTurn = Math.max(latestTurn, m.messageIndex());
            }
        }
        if (latestTurn == Integer.MIN_VALUE) return List.of();

        // 2) Group EXPLICIT latest-turn interpretations by (entityKey, canonicalId), in mention order.
        Map<String, Builder> byId = new LinkedHashMap<>();
        int order = 0;
        for (GroundedMention gm : set.mentions()) {
            Mention m = gm.mention();
            if (m == null || m.source() != MentionSource.EXPLICIT || m.messageIndex() != latestTurn) {
                continue;
            }
            int mentionOrder = order++;
            for (GroundedInterpretation gi : gm.interpretations()) {
                if (gi.canonicalId() == null) continue; // NOT_FOUND / AMBIGUOUS never bind
                if (gi.status() != GroundStatus.RESOLVED_ALLOWED
                        && gi.status() != GroundStatus.DENIED
                        && gi.status() != GroundStatus.UNAVAILABLE) {
                    continue;
                }
                String key = gi.entityKey() + "::" + gi.canonicalId();
                Builder b = byId.computeIfAbsent(key, k -> new Builder(
                        gi.canonicalId(), m.verbatimText(), gi.entityKey(), mentionOrder));
                b.interpretations.add(gi);
            }
        }
        if (byId.isEmpty()) return List.of();

        // 3) Pick the dominant entityKey (most distinct-id bindings; ties → earliest mention order).
        Map<String, List<Builder>> byEntityKey = new LinkedHashMap<>();
        for (Builder b : byId.values()) {
            byEntityKey.computeIfAbsent(b.entityKey, k -> new ArrayList<>()).add(b);
        }
        List<Builder> dominant = null;
        for (List<Builder> group : byEntityKey.values()) {
            if (dominant == null
                    || group.size() > dominant.size()
                    || (group.size() == dominant.size()
                        && firstOrder(group) < firstOrder(dominant))) {
                dominant = group;
            }
        }
        if (dominant == null) return List.of();

        List<Builder> ordered = new ArrayList<>(dominant);
        ordered.sort((a, c) -> Integer.compare(a.mentionOrder, c.mentionOrder));
        List<EntityBinding> out = new ArrayList<>();
        for (Builder b : ordered) {
            out.add(new EntityBinding(b.canonicalId, b.userVerbatim, b.entityKey,
                    List.copyOf(b.interpretations), b.mentionOrder));
        }
        return out;
    }

    private static int firstOrder(List<Builder> group) {
        int min = Integer.MAX_VALUE;
        for (Builder b : group) min = Math.min(min, b.mentionOrder);
        return min;
    }

    /** Mutable accumulator during derivation (one per distinct id). */
    private static final class Builder {
        final String canonicalId;
        final String userVerbatim;
        final String entityKey;
        final int mentionOrder;
        final List<GroundedInterpretation> interpretations = new ArrayList<>();

        Builder(String canonicalId, String userVerbatim, String entityKey, int mentionOrder) {
            this.canonicalId = canonicalId;
            this.userVerbatim = userVerbatim;
            this.entityKey = entityKey;
            this.mentionOrder = mentionOrder;
        }
    }
}
