package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic, domain-agnostic dependency-graph resolver for multi-step orchestration.
 *
 * <p>Given a goal capability, a snapshot of candidate {@link AgentManifest manifests}, and the set of
 * entity keys already available (e.g. resolved entities), it walks each capability's
 * {@link AgentManifest.Io io} contract and wires a directed acyclic graph:
 * <ul>
 *   <li>an {@code io.consumes[].entity} reference is a <b>leaf</b> — satisfied outside the graph by
 *       entity resolution when the key is in the available set (an unmet <i>required</i> entity is an
 *       {@link ResolutionError.Code#UNMET_REQUIRED_INPUT});</li>
 *   <li>an {@code io.consumes[].from} reference matches some capability's {@code io.produces[].type}
 *       and creates a producer→consumer edge; the resolver recurses into the producer.</li>
 * </ul>
 *
 * <p>The result is a {@link Plan} whose {@link PlanNode#dependsOn()} edges are populated and whose
 * nodes are in topological order (Kahn's algorithm: producers before consumers). All structural
 * failures are returned as classified {@link ResolutionError}s rather than thrown — the caller
 * decides how to surface them.
 *
 * <p><b>World B:</b> this class contains no domain vocabulary. Every string it reasons over — entity
 * keys, produced/consumed type symbols, capability ids — comes from the manifests at runtime and is
 * matched by equality only.
 *
 * <p><b>Ambiguity policy (deterministic, for now):</b> if a {@code from} type is produced by more than
 * one candidate, the resolver refuses to guess and returns an
 * {@link ResolutionError.Code#AMBIGUOUS_PRODUCER} listing the candidates. A priority/specificity
 * tie-break is a documented future extension; it is intentionally not an LLM decision.
 *
 * <p>Stateless and side-effect free — a single instance is safe to share across threads.
 */
@Component
public final class DagResolver {

    /**
     * Resolve a plan for {@code goalAgentId} over the given candidate manifests.
     *
     * @param goalAgentId       the capability the user ultimately wants (the DAG's sink)
     * @param candidates        registry snapshot of candidate manifests to wire from
     * @param availableEntities entity keys already available (resolved entities); may be empty
     * @return an ok resolution with a topologically ordered {@link Plan}, or a failure with the
     *         classified errors found
     */
    public DagResolution resolve(String goalAgentId,
                                 Collection<AgentManifest> candidates,
                                 Set<String> availableEntities) {

        Set<String> available = availableEntities == null ? Set.of() : availableEntities;

        // ── Indexes over the candidate snapshot ──────────────────────────────────────────────
        Map<String, AgentManifest> byId = new HashMap<>();
        Map<String, List<String>> producersByType = new HashMap<>();   // produced type → capability ids
        for (AgentManifest m : candidates) {
            if (m == null || m.agentId() == null) continue;
            byId.put(m.agentId(), m);
            AgentManifest.Io io = m.io();
            if (io != null && io.produces() != null) {
                for (AgentManifest.Produce p : io.produces()) {
                    if (p != null && p.type() != null) {
                        producersByType.computeIfAbsent(p.type(), k -> new ArrayList<>()).add(m.agentId());
                    }
                }
            }
        }
        // Deterministic candidate ordering for tie-break reporting and single-producer selection.
        producersByType.values().forEach(l -> l.sort(String::compareTo));

        List<ResolutionError> errors = new ArrayList<>();

        if (!byId.containsKey(goalAgentId)) {
            errors.add(ResolutionError.of(ResolutionError.Code.UNKNOWN_NODE,
                    "goal capability '" + goalAgentId + "' is not among the candidate capabilities",
                    goalAgentId));
            return DagResolution.failure(errors);
        }

        // ── Phase 1: expand the graph from the goal (iterative; a visited guard prevents any
        //             cycle from causing infinite expansion — the cycle is caught in phase 2). ──
        Map<String, List<String>> dependsOn = new HashMap<>();   // capability id → sorted dependency ids
        Set<String> expanded = new HashSet<>();
        Deque<String> toExpand = new ArrayDeque<>();

        toExpand.push(goalAgentId);
        while (!toExpand.isEmpty()) {
            String id = toExpand.pop();
            if (!expanded.add(id)) continue;

            AgentManifest m = byId.get(id);   // always present: goal is checked; producers are ids from byId
            Set<String> deps = new TreeSet<>();   // sorted → deterministic edge order
            AgentManifest.Io io = m.io();
            List<AgentManifest.Consume> consumes =
                    (io == null || io.consumes() == null) ? List.of() : io.consumes();

            for (AgentManifest.Consume c : consumes) {
                if (c == null) continue;

                if (c.isEntityRef()) {
                    // Leaf input: satisfied by entity resolution, never by an upstream capability.
                    if (!available.contains(c.entity()) && c.isRequired()) {
                        errors.add(ResolutionError.of(ResolutionError.Code.UNMET_REQUIRED_INPUT,
                                "required input '" + c.entity() + "' for capability '" + id
                                        + "' is not available",
                                id, c.entity()));
                    }
                    // optional + missing → no edge, no error
                    continue;
                }

                if (c.isProducedRef()) {
                    String type = c.from();
                    List<String> producers = producersByType.getOrDefault(type, List.of());
                    if (producers.isEmpty()) {
                        errors.add(ResolutionError.of(ResolutionError.Code.MISSING_PRODUCER,
                                "no capability produces '" + type + "' required by capability '" + id + "'",
                                id, type));
                    } else if (producers.size() > 1) {
                        List<String> details = new ArrayList<>();
                        details.add(id);
                        details.add(type);
                        details.addAll(producers);
                        errors.add(ResolutionError.of(ResolutionError.Code.AMBIGUOUS_PRODUCER,
                                "type '" + type + "' required by capability '" + id
                                        + "' is produced by " + producers.size() + " capabilities "
                                        + producers + " — deterministic resolution requires exactly one",
                                details));
                        // do not add an edge; do not recurse — refuse to guess
                    } else {
                        String producerId = producers.get(0);
                        deps.add(producerId);
                        toExpand.push(producerId);
                    }
                }
                // A malformed consume (neither entity nor from) is ignored: the schema's oneOf
                // forbids it, so it cannot appear in a validated manifest.
            }

            dependsOn.put(id, List.copyOf(deps));
        }

        // ── Referential integrity: every dependency target must be a known node. ──────────────
        for (var e : dependsOn.entrySet()) {
            for (String dep : e.getValue()) {
                if (!dependsOn.containsKey(dep)) {
                    errors.add(ResolutionError.of(ResolutionError.Code.UNKNOWN_NODE,
                            "capability '" + e.getKey() + "' depends on unknown capability '" + dep + "'",
                            e.getKey(), dep));
                }
            }
        }

        // ── Phase 2: Kahn topological sort (producers first); leftover nodes ⇒ a cycle. ───────
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> consumersOf = new HashMap<>();   // producer id → dependent ids
        for (String id : dependsOn.keySet()) indegree.put(id, 0);
        for (var e : dependsOn.entrySet()) {
            String consumer = e.getKey();
            for (String producer : e.getValue()) {
                if (!dependsOn.containsKey(producer)) continue;   // unknown dep already reported
                indegree.merge(consumer, 1, Integer::sum);
                consumersOf.computeIfAbsent(producer, k -> new ArrayList<>()).add(consumer);
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>();   // natural order → stable topo output
        indegree.forEach((id, deg) -> { if (deg == 0) ready.add(id); });

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String consumer : consumersOf.getOrDefault(id, List.of())) {
                if (indegree.merge(consumer, -1, Integer::sum) == 0) ready.add(consumer);
            }
        }

        if (order.size() < dependsOn.size()) {
            Set<String> inCycle = new TreeSet<>(dependsOn.keySet());
            inCycle.removeAll(order);
            errors.add(ResolutionError.of(ResolutionError.Code.CYCLE,
                    "dependency cycle among capabilities " + inCycle + " — no execution order exists",
                    new ArrayList<>(inCycle)));
        }

        if (!errors.isEmpty()) {
            return DagResolution.failure(errors);
        }

        // ── Success: materialise the ordered plan. Per-node wire input is bound later by the
        //             executor from the blackboard, so it is left null here. ─────────────────
        List<PlanNode> nodes = new ArrayList<>(order.size());
        for (String id : order) {
            nodes.add(new PlanNode(id, byId.get(id), null, dependsOn.get(id)));
        }
        return DagResolution.success(new Plan(nodes));
    }
}
