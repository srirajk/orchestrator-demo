package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.cap;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.entity;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.from;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.produce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TECHNIQUE 5 — Scale / load. Deep and wide graphs prove the resolver is iterative and
 * visited-guarded (no recursion → no {@link StackOverflowError}), completes quickly, and still
 * produces a correct topological order at size.
 */
class DagResolverScaleTest {

    private final DagResolver resolver = new DagResolver();

    @Test
    @DisplayName("scale: 200-node linear chain resolves fast, exact 0..199 topo order, no StackOverflowError")
    void deepLinearChain() {
        int n = 200;
        List<AgentManifest> caps = new ArrayList<>(n);
        // node 0 is an entity leaf; node i (>0) consumes from node i-1's type.
        caps.add(cap(id(0), List.of(entity("seed")), List.of(produce("p0", "t0"))));
        for (int i = 1; i < n; i++) {
            caps.add(cap(id(i), List.of(from("t" + (i - 1))), List.of(produce("p" + i, "t" + i))));
        }

        long startNs = System.nanoTime();
        DagResolution[] holder = new DagResolution[1];
        assertThatCode(() -> holder[0] = resolver.resolve(id(n - 1), caps, Set.of("seed")))
                .as("deep chain must not StackOverflow").doesNotThrowAnyException();
        long ms = (System.nanoTime() - startNs) / 1_000_000;

        DagResolution r = holder[0];
        assertThat(r.ok()).as("errors=%s", r.errors()).isTrue();
        List<String> order = r.plan().nodes().stream().map(PlanNode::nodeId).toList();
        assertThat(order).hasSize(n);
        // The chain forces a unique topo order: id(0), id(1), ... id(199).
        for (int i = 0; i < n; i++) {
            assertThat(order.get(i)).as("chain position %d", i).isEqualTo(id(i));
            if (i > 0) {
                assertThat(r.plan().nodes().get(i).dependsOn()).containsExactly(id(i - 1));
            }
        }
        assertThat(ms).as("resolve latency of a 200-chain (was %d ms)", ms).isLessThan(2_000L);
    }

    @Test
    @DisplayName("scale: 1 consumer over 150 producers (wide fan-in) resolves fast, 151 nodes, consumer last, no SOE")
    void wideFanIn() {
        int producers = 150;
        List<AgentManifest> caps = new ArrayList<>(producers + 1);
        List<AgentManifest.Consume> consumerConsumes = new ArrayList<>(producers);
        for (int i = 0; i < producers; i++) {
            caps.add(cap(id(i), List.of(entity("seed")), List.of(produce("p" + i, "t" + i))));
            consumerConsumes.add(from("t" + i));
        }
        String consumerId = "cap.consumer";
        caps.add(cap(consumerId, consumerConsumes, List.of(produce("c", "tFinal"))));

        long startNs = System.nanoTime();
        DagResolution[] holder = new DagResolution[1];
        assertThatCode(() -> holder[0] = resolver.resolve(consumerId, caps, Set.of("seed")))
                .as("wide fan-in must not StackOverflow").doesNotThrowAnyException();
        long ms = (System.nanoTime() - startNs) / 1_000_000;

        DagResolution r = holder[0];
        assertThat(r.ok()).as("errors=%s", r.errors()).isTrue();
        List<String> order = r.plan().nodes().stream().map(PlanNode::nodeId).toList();
        assertThat(order).hasSize(producers + 1);
        assertThat(order.get(order.size() - 1)).as("consumer is the sink, resolved last").isEqualTo(consumerId);
        // The consumer depends on all 150 producers (deduped, sorted).
        PlanNode consumerNode = r.plan().nodes().get(order.size() - 1);
        assertThat(consumerNode.dependsOn()).hasSize(producers);
        assertThat(ms).as("resolve latency of a 150-wide fan-in (was %d ms)", ms).isLessThan(2_000L);
    }

    private static String id(int i) { return String.format("n%05d", i); }
}
