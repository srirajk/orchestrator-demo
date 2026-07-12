package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.coverage.EntityBinding;
import ai.conduit.gateway.registry.model.AgentManifest;

import java.util.List;

/**
 * The requested-capability-group model (routing spec V2 Piece 4). A request is a set of
 * <b>requested groups</b>; disposition (structural authz + coverage + binding + execution + withhold)
 * runs <b>per group</b> and the allowed groups are merged into one answer. The group — not a single
 * survivor, not {@code ResolverResult.primaryCandidate} — is the fulfillment contract.
 *
 * <p><b>Derivation.</b> Groups come from the resolver's decision, never from raw embedding noise:
 * <ul>
 *   <li>when the Piece-5 reranker returned a valid multi-facet {@code multiple([ids])}
 *       ({@link ai.conduit.gateway.resolver.model.ResolverResult#rerankSelectedIds()} non-empty),
 *       there is <b>one group per id</b> — each id is a distinct requested facet;</li>
 *   <li>otherwise the request is a <b>single group</b> of every selected candidate (a single confident
 *       pick, or a legitimate balanced cross-domain fan-out — the overwhelmingly common case). The
 *       single-group path reproduces the pre-Piece-4 flat disposition byte-for-byte.</li>
 * </ul>
 *
 * <p><b>World-B.</b> Every field is an agent/domain/sub-domain id, a manifest-derived entity key, a
 * generic enum, or a diagnostic string — no domain literal, id pattern, or entity-type word.
 */
public record RequestedPlan(List<RequestedGroup> groups) {

    public RequestedPlan {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public boolean isMultiGroup() {
        return groups.size() > 1;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /**
     * One requested capability group — the unit of per-group disposition.
     *
     * @param requestedCapabilityIds the agent ids this group fulfils (the reranker-named facet, or all
     *                               selected ids in the single-group case).
     * @param candidates             the routed, pre-authorization manifests backing those ids. Retained
     *                               pre-authz so a fully-pruned group can still name its owning
     *                               sub-domain for the denial copy (never HashMap-order roulette).
     * @param kind                   {@link Kind#FLAT} (fan-out) or {@link Kind#DAG} (a fan-in goal whose
     *                               {@code io} consumes another capability's output).
     * @param goalId                 the fan-in goal agent id for a {@link Kind#DAG} group, else {@code null}.
     * @param requiredEntityKeys     manifest-declared required entity keys across the group's candidates
     *                               (diagnostic; the coverage/bind stages read the effective manifest).
     * @param routingEvidence        a short, domain-agnostic diagnostic tag describing why this group was
     *                               formed (e.g. {@code "rerank-facet"} / {@code "single-selection"} /
     *                               {@code "entity-facet"}).
     * @param binding                the entity this group is bound to for a multi-entity COMPARE fan-out
     *                               ({@code null} on every non-COMPARE group — the feature is inert below
     *                               two bindings, so every existing group keeps {@code binding == null}).
     */
    public record RequestedGroup(
            List<String> requestedCapabilityIds,
            List<AgentManifest> candidates,
            Kind kind,
            String goalId,
            List<String> requiredEntityKeys,
            String routingEvidence,
            EntityBinding binding) {

        public RequestedGroup {
            requestedCapabilityIds = requestedCapabilityIds == null ? List.of() : List.copyOf(requestedCapabilityIds);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            requiredEntityKeys = requiredEntityKeys == null ? List.of() : List.copyOf(requiredEntityKeys);
        }

        /** Legacy constructor — every existing call site keeps its 6-arg shape with {@code binding == null}. */
        public RequestedGroup(List<String> requestedCapabilityIds, List<AgentManifest> candidates,
                              Kind kind, String goalId, List<String> requiredEntityKeys,
                              String routingEvidence) {
            this(requestedCapabilityIds, candidates, kind, goalId, requiredEntityKeys, routingEvidence, null);
        }

        /** A copy of this group bound to {@code b} and re-tagged as an entity facet (COMPARE expansion). */
        public RequestedGroup withEntityBinding(EntityBinding b) {
            return new RequestedGroup(requestedCapabilityIds, candidates, kind, goalId,
                    requiredEntityKeys, "entity-facet", b);
        }

        public boolean isDag() {
            return kind == Kind.DAG;
        }

        /** How this group is fulfilled: a flat fan-out or a producer→consumer DAG. */
        public enum Kind { FLAT, DAG }
    }
}
