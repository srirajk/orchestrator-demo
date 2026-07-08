package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.cap;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.entity;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.from;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.produce;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TECHNIQUE 2 — Determinism / order-independence.
 *
 * <p>The candidate list is a <i>set</i> semantically; the derived plan must not depend on the order
 * candidates happen to arrive in. These tests shuffle the candidate list across many permutations and
 * assert the resulting plan (node order + edges) is IDENTICAL every time — proving the resolver has no
 * hidden ordering dependence (its {@code PriorityQueue}/{@code TreeSet} internals give a stable output).
 */
class DagResolverDeterminismTest {

    private final DagResolver resolver = new DagResolver();

    private static Map<String, List<String>> edges(Plan plan) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (PlanNode n : plan.nodes()) m.put(n.nodeId(), n.dependsOn());
        return m;
    }

    private static List<String> order(Plan plan) {
        return plan.nodes().stream().map(PlanNode::nodeId).toList();
    }

    @Test
    @DisplayName("order-independence: a fixed diamond graph resolves identically under 200 candidate permutations")
    void diamondIsPermutationInvariant() {
        var base = cap("cap.base", List.of(entity("entE")), List.of(produce("c", "typeC")));
        var mid1 = cap("cap.mid1", List.of(from("typeC")), List.of(produce("a", "typeA")));
        var mid2 = cap("cap.mid2", List.of(from("typeC")), List.of(produce("b", "typeB")));
        var top  = cap("cap.top",  List.of(from("typeA"), from("typeB")), List.of(produce("t", "typeT")));
        List<AgentManifest> candidates = new ArrayList<>(List.of(base, mid1, mid2, top));

        DagResolution canonical = resolver.resolve("cap.top", candidates, java.util.Set.of("entE"));
        assertThat(canonical.ok()).isTrue();
        List<String> canonicalOrder = order(canonical.plan());
        Map<String, List<String>> canonicalEdges = edges(canonical.plan());

        Random rnd = new Random(20260707L);
        for (int i = 0; i < 200; i++) {
            List<AgentManifest> permuted = new ArrayList<>(candidates);
            Collections.shuffle(permuted, rnd);
            DagResolution r = resolver.resolve("cap.top", permuted, java.util.Set.of("entE"));
            assertThat(r.ok()).as("perm %d ok", i).isTrue();
            assertThat(order(r.plan())).as("perm %d order == canonical", i).isEqualTo(canonicalOrder);
            assertThat(edges(r.plan())).as("perm %d edges == canonical", i).isEqualTo(canonicalEdges);
        }
    }

    @Test
    @DisplayName("order-independence: 20 random graphs each stable across 50 candidate shuffles")
    void randomGraphsArePermutationInvariant() {
        Random rnd = new Random(0xD37E271111L);
        for (int graphIx = 0; graphIx < 20; graphIx++) {
            DagGraphFixtures.RandomGraph g = DagGraphFixtures.randomAcyclicGraph(rnd);

            DagResolution canonical = resolver.resolve(g.goal(), g.candidates(), g.available());
            assertThat(canonical.ok()).as("graph %d ok", graphIx).isTrue();
            List<String> canonicalOrder = order(canonical.plan());
            Map<String, List<String>> canonicalEdges = edges(canonical.plan());

            for (int s = 0; s < 50; s++) {
                List<AgentManifest> permuted = new ArrayList<>(g.candidates());
                Collections.shuffle(permuted, rnd);
                DagResolution r = resolver.resolve(g.goal(), permuted, g.available());
                assertThat(order(r.plan())).as("graph %d shuffle %d order", graphIx, s).isEqualTo(canonicalOrder);
                assertThat(edges(r.plan())).as("graph %d shuffle %d edges", graphIx, s).isEqualTo(canonicalEdges);
            }
        }
    }
}
