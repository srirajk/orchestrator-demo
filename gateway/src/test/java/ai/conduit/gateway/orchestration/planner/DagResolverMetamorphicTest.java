package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.cap;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.entity;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.from;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.produce;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TECHNIQUE 3 — Metamorphic testing. A metamorphic relation asserts how the OUTPUT must change (or
 * not change) when the INPUT is transformed in a specific way — catching bugs an absolute oracle
 * might miss:
 * <ul>
 *   <li>(a) adding a SECOND producer of an already-consumed type flips OK → {@code AMBIGUOUS_PRODUCER};</li>
 *   <li>(b) removing the sole producer of a consumed type flips OK → {@code MISSING_PRODUCER};</li>
 *   <li>(c) adding an unrelated capability leaves the derived DAG for the same goal byte-identical.</li>
 * </ul>
 */
class DagResolverMetamorphicTest {

    private final DagResolver resolver = new DagResolver();

    private static Map<String, List<String>> edges(Plan plan) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (PlanNode n : plan.nodes()) m.put(n.nodeId(), n.dependsOn());
        return m;
    }

    private static List<String> order(Plan plan) {
        return plan.nodes().stream().map(PlanNode::nodeId).toList();
    }

    // Baseline OK graph: producer(typeA) → consumer(from typeA).
    private static final AgentManifest PRODUCER =
            cap("cap.producer", List.of(entity("entE")), List.of(produce("out", "typeA")));
    private static final AgentManifest CONSUMER =
            cap("cap.consumer", List.of(from("typeA")), List.of(produce("res", "typeR")));

    @Test
    @DisplayName("metamorphic (a): a valid resolve + a second producer of the consumed type ⇒ AMBIGUOUS_PRODUCER")
    void addingSecondProducerFlipsToAmbiguous() {
        DagResolution before = resolver.resolve("cap.consumer", List.of(PRODUCER, CONSUMER), Set.of("entE"));
        assertThat(before.ok()).as("baseline resolves").isTrue();

        var producer2 = cap("cap.producer2", List.of(entity("entE")), List.of(produce("out2", "typeA")));
        DagResolution after = resolver.resolve("cap.consumer",
                List.of(PRODUCER, producer2, CONSUMER), Set.of("entE"));

        assertThat(after.ok()).isFalse();
        assertThat(after.hasError(ResolutionError.Code.AMBIGUOUS_PRODUCER)).isTrue();
        ResolutionError amb = after.errors().stream()
                .filter(e -> e.code() == ResolutionError.Code.AMBIGUOUS_PRODUCER).findFirst().orElseThrow();
        assertThat(amb.details()).contains("cap.producer", "cap.producer2");   // both listed, no silent pick
    }

    @Test
    @DisplayName("metamorphic (b): a valid resolve minus the sole producer ⇒ MISSING_PRODUCER")
    void removingSoleProducerFlipsToMissing() {
        DagResolution before = resolver.resolve("cap.consumer", List.of(PRODUCER, CONSUMER), Set.of("entE"));
        assertThat(before.ok()).isTrue();

        DagResolution after = resolver.resolve("cap.consumer", List.of(CONSUMER), Set.of("entE"));

        assertThat(after.ok()).isFalse();
        assertThat(after.hasError(ResolutionError.Code.MISSING_PRODUCER)).isTrue();
        assertThat(after.errors().get(0).details()).contains("cap.consumer", "typeA");
    }

    @Test
    @DisplayName("metamorphic (c): adding unrelated capabilities never changes the derived DAG for the same goal")
    void addingUnrelatedCapabilityIsInvariant() {
        DagResolution before = resolver.resolve("cap.consumer", List.of(PRODUCER, CONSUMER), Set.of("entE"));
        assertThat(before.ok()).isTrue();
        List<String> baseOrder = order(before.plan());
        Map<String, List<String>> baseEdges = edges(before.plan());

        // Unrelated: produces a type nobody in the reachable graph consumes; consumes an unrelated
        // (available) entity. Distinct type ⇒ no ambiguity, unreachable ⇒ must not enter the plan.
        var unrelated1 = cap("cap.noise1", List.of(entity("entX")), List.of(produce("z", "typeUnrelated")));
        var unrelated2 = cap("cap.noise2", List.of(from("typeUnrelated")), List.of(produce("w", "typeUnrelated2")));

        List<AgentManifest> withNoise = new ArrayList<>(List.of(PRODUCER, CONSUMER, unrelated1, unrelated2));
        DagResolution after = resolver.resolve("cap.consumer", withNoise, Set.of("entE", "entX"));

        assertThat(after.ok()).isTrue();
        assertThat(order(after.plan())).as("order unchanged by unrelated caps").isEqualTo(baseOrder);
        assertThat(edges(after.plan())).as("edges unchanged by unrelated caps").isEqualTo(baseEdges);
        assertThat(order(after.plan())).doesNotContain("cap.noise1", "cap.noise2");
    }
}
