package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TECHNIQUE 1 — Property-based / fuzz testing (seeded, reproducible).
 *
 * <p>Generates hundreds of random capability graphs that are acyclic, single-producer-per-type and
 * entities-available (see {@link DagGraphFixtures#randomAcyclicGraph}). For each such graph a correct
 * resolver MUST return {@code ok==true}; this test then checks four structural invariants against an
 * <b>independent oracle</b> (a re-derivation of the reachable set from the manifests, not from the
 * generator's bookkeeping):
 *
 * <ol>
 *   <li><b>OK</b> — every well-formed graph resolves.</li>
 *   <li><b>Valid topological order</b> — every dependency precedes its dependent in {@code plan.nodes()}.</li>
 *   <li><b>Reachability</b> — the plan's node set equals exactly the set reachable from the goal
 *       (no orphan capabilities pulled in, none dropped).</li>
 *   <li><b>Determinism</b> — resolving the same inputs twice yields byte-identical order and edges.</li>
 * </ol>
 *
 * <p>Random streams are seeded with FIXED seeds; on any failure the seed and iteration index are
 * printed via the AssertJ {@code as(...)} description, so any counterexample is exactly reproducible.
 */
class DagResolverPropertyTest {

    private static final long[] SEEDS = {0xC0FFEEL, 0xBEEFL, 42L, 2026L, 7L};
    private static final int ITERATIONS_PER_SEED = 200;   // 1000 random graphs total

    private final DagResolver resolver = new DagResolver();

    @Test
    @DisplayName("property: 1000 random acyclic graphs all resolve with valid topo order, exact reachable set, and determinism")
    void randomAcyclicGraphsSatisfyResolverInvariants() {
        int checked = 0;
        for (long seed : SEEDS) {
            Random rnd = new Random(seed);
            for (int iter = 0; iter < ITERATIONS_PER_SEED; iter++) {
                DagGraphFixtures.RandomGraph g = DagGraphFixtures.randomAcyclicGraph(rnd);
                String ctx = "seed=" + Long.toHexString(seed) + " iter=" + iter
                        + " goal=" + g.goal() + " n=" + g.n();

                DagResolution r = resolver.resolve(g.goal(), g.candidates(), g.available());

                // (1) OK — a well-formed graph must resolve.
                assertThat(r.ok()).as("%s : expected OK but got errors %s", ctx, r.errors()).isTrue();
                assertThat(r.errors()).as("%s : no errors expected", ctx).isEmpty();
                Plan plan = r.plan();
                assertThat(plan).as("%s : plan present", ctx).isNotNull();

                List<String> order = plan.nodes().stream().map(PlanNode::nodeId).toList();

                // (2) Valid topological order — every dependency comes strictly before its dependent.
                Map<String, List<String>> edges = new HashMap<>();
                for (PlanNode node : plan.nodes()) edges.put(node.nodeId(), node.dependsOn());
                for (PlanNode node : plan.nodes()) {
                    int pos = order.indexOf(node.nodeId());
                    for (String dep : node.dependsOn()) {
                        int depPos = order.indexOf(dep);
                        assertThat(depPos).as("%s : dep %s must precede %s in %s", ctx, dep, node.nodeId(), order)
                                .isGreaterThanOrEqualTo(0).isLessThan(pos);
                    }
                }

                // (3a) FULL-DAG ORACLE — node set. Plan nodes == exactly the goal's from-closure.
                Oracle oracle = buildOracle(g.goal(), g.candidates());
                assertThat(new HashSet<>(order)).as("%s : plan node set == reachable set", ctx)
                        .isEqualTo(oracle.nodes);
                assertThat(order).as("%s : no duplicate nodes in plan", ctx).doesNotHaveDuplicates();

                // (3b) FULL-DAG ORACLE — edge set. Every node's dependsOn == EXACTLY the set of unique
                //      producers of its consumed from-types (deduped, sorted). Full structural equality,
                //      not merely "some valid topo order".
                Map<String, List<String>> expectedEdges = new HashMap<>();
                for (String id : oracle.nodes) expectedEdges.put(id, oracle.edges.get(id));
                assertThat(edges).as("%s : plan edge set == oracle edge set", ctx).isEqualTo(expectedEdges);

                // (3c) topo order must be consistent with those EXACT oracle edges (already checked
                //      against plan edges above; equality of the two makes this a check against the oracle).

                // (4) Determinism — a second resolve is byte-identical (order + edges).
                DagResolution r2 = resolver.resolve(g.goal(), g.candidates(), g.available());
                List<String> order2 = r2.plan().nodes().stream().map(PlanNode::nodeId).toList();
                assertThat(order2).as("%s : deterministic order", ctx).isEqualTo(order);
                Map<String, List<String>> edges2 = new HashMap<>();
                for (PlanNode node : r2.plan().nodes()) edges2.put(node.nodeId(), node.dependsOn());
                assertThat(edges2).as("%s : deterministic edges", ctx).isEqualTo(edges);

                checked++;
            }
        }
        assertThat(checked).isEqualTo(SEEDS.length * ITERATIONS_PER_SEED);
    }

    /** The full expected DAG derived independently from the manifests: exact node set + exact edges. */
    private record Oracle(Set<String> nodes, Map<String, List<String>> edges) {}

    /**
     * INDEPENDENT FULL-DAG ORACLE. Re-derives, from the manifests the resolver sees (not from the
     * generator's bookkeeping), the EXACT set of nodes reachable from {@code goal} and, for each, its
     * EXACT {@code dependsOn} = the deduped, sorted set of the unique producers of its consumed
     * from-types. This is the whole correct DAG, not just a reachability set.
     */
    private static Oracle buildOracle(String goal, List<AgentManifest> candidates) {
        Map<String, String> producerOfType = new HashMap<>();
        Map<String, AgentManifest> byId = new HashMap<>();
        for (AgentManifest m : candidates) {
            byId.put(m.agentId(), m);
            if (m.io() != null && m.io().produces() != null) {
                for (AgentManifest.Produce p : m.io().produces()) {
                    producerOfType.merge(p.type(), m.agentId(), (a, b) -> {
                        throw new AssertionError("oracle expected a single producer per type: " + p.type());
                    });
                }
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        Map<String, List<String>> edges = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(goal);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!seen.add(id)) continue;
            AgentManifest m = byId.get(id);
            Set<String> deps = new java.util.TreeSet<>();   // sorted + deduped, mirroring the resolver
            if (m != null && m.io() != null && m.io().consumes() != null) {
                for (AgentManifest.Consume c : m.io().consumes()) {
                    if (c.isProducedRef()) {
                        String producer = producerOfType.get(c.from());
                        if (producer != null) {
                            deps.add(producer);
                            stack.push(producer);
                        }
                    }
                }
            }
            edges.put(id, new ArrayList<>(deps));
        }
        return new Oracle(new HashSet<>(seen), edges);
    }
}
