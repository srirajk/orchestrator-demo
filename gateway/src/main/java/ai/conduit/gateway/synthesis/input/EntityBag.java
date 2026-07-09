package ai.conduit.gateway.synthesis.input;

import java.util.List;
import java.util.Map;

/**
 * Generic, manifest-driven carrier for extracted and resolved entities.
 *
 * <p>There are no entity-type-specific fields: adding a new entity type is a manifest edit,
 * not a code change. The maps are keyed off the manifest's {@code entity_types} declarations:
 *
 * <ul>
 *   <li>{@code references} — {@code extract_as} → raw extracted value (resolvable + literal kinds)</li>
 *   <li>{@code lists}      — {@code extract_as} → list value (list kinds)</li>
 *   <li>{@code resolved}   — {@code key} → resolved id/value (filled by the resolver)</li>
 * </ul>
 */
public record EntityBag(
    Map<String, String> references,
    Map<String, List<String>> lists,
    Map<String, String> resolved,
    boolean needsClarification,
    List<EntityCandidate> candidates
) {

    public record EntityCandidate(String entityId, String name) {}

    /** Normalise nulls and defensively copy. */
    public EntityBag {
        references = references == null ? Map.of() : Map.copyOf(references);
        lists      = lists == null ? Map.of() : Map.copyOf(lists);
        resolved   = resolved == null ? Map.of() : Map.copyOf(resolved);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static EntityBag empty() {
        return new EntityBag(Map.of(), Map.of(), Map.of(), false, List.of());
    }

    /** Builds an extracted (un-resolved) bag from the raw extraction maps. */
    public static EntityBag of(Map<String, String> references, Map<String, List<String>> lists) {
        return new EntityBag(references, lists, Map.of(), false, List.of());
    }

    // ── Generic accessors (keyed by manifest declarations) ──────────────────────

    /** Raw extracted value for an {@code extract_as} field (resolvable/literal kinds). */
    public String reference(String extractAs) {
        return references.get(extractAs);
    }

    /** Resolved id/value for an entity {@code key}. */
    public String resolved(String key) {
        return resolved.get(key);
    }

    /** List value for an {@code extract_as} field (list kinds); never null. */
    public List<String> list(String extractAs) {
        return lists.getOrDefault(extractAs, List.of());
    }

    // ── Immutable transforms ────────────────────────────────────────────────────

    public EntityBag withResolved(Map<String, String> resolved, boolean needsClarification) {
        return new EntityBag(this.references, this.lists, resolved, needsClarification, List.of());
    }

    /** Returns a copy with one resolved entity key overridden. */
    public EntityBag withResolvedValue(String key, String value) {
        var next = new java.util.LinkedHashMap<>(this.resolved);
        if (value == null) next.remove(key); else next.put(key, value);
        return new EntityBag(this.references, this.lists, next, this.needsClarification, this.candidates);
    }

    public EntityBag withCandidates(List<EntityCandidate> candidates) {
        return new EntityBag(this.references, this.lists, this.resolved, true, candidates);
    }

    /** Returns a copy with one reference field overridden (used to inject a resolved id pre-resolution). */
    public EntityBag withReference(String extractAs, String value) {
        var next = new java.util.LinkedHashMap<>(this.references);
        if (value == null) next.remove(extractAs); else next.put(extractAs, value);
        return new EntityBag(next, this.lists, this.resolved, this.needsClarification, this.candidates);
    }
}
