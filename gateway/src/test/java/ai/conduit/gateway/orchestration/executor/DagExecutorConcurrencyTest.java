package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.MAPPER;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.SleepyAdapter;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.entity;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.executor;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.from;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.harness;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.io;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.node;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.produce;
import static ai.conduit.gateway.orchestration.executor.ExecutorTestSupport.uniqueOutput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TECHNIQUE 6 — Concurrency / race (the important one).
 *
 * <p>{@link DagPlanExecutor} runs sibling nodes in PARALLEL on virtual threads over one shared
 * {@link Blackboard}. Each fake node sleeps a random few ms (forcing interleaving) and returns a
 * UNIQUE, identifiable payload. Every run is checked not merely for "no exception" but against a
 * single-threaded REFERENCE ORACLE computed independently: the exact bound input every node should
 * receive (pass-through of its one producer, or merge-by-produced-name of its producers) and the
 * exact per-node result. <b>Concurrency correctness = the parallel result equals the serial
 * reference, deterministically, on every one of the runs.</b> A lost, swapped, stale or duplicated
 * value — with no exception — is a correctness bug and fails the test.
 */
class DagExecutorConcurrencyTest {

    private static final int RUNS_WIDE = 200;
    private static final int RUNS_LAYERED = 100;

    // ── wide fan-in: 30 producers → 1 consumer, run 200× ─────────────────────────────────────────

    @Test
    @DisplayName("wide fan-in (30→1) × 200 runs: consumer's merged input == reference oracle exactly, every run; no race/exception")
    void wideFanInMatchesReferenceEveryRun() {
        int producers = 30;

        // Build the plan.
        List<PlanNode> nodes = new ArrayList<>();
        List<AgentManifest.Consume> consumerConsumes = new ArrayList<>();
        List<String> producerIds = new ArrayList<>();
        for (int i = 0; i < producers; i++) {
            String pid = String.format("prod%02d", i);
            producerIds.add(pid);
            nodes.add(node(pid, io(List.of(entity("seed")), List.of(produce("p" + i, "t" + i))), List.of()));
            consumerConsumes.add(from("t" + i));
        }
        String consumerId = "cons";
        nodes.add(node(consumerId, io(consumerConsumes, List.of(produce("cout", "tFinal"))), producerIds));
        Plan plan = new Plan(nodes);

        // SINGLE-THREADED REFERENCE ORACLE (computed once; independent of the executor):
        //  - each producer's bound input == the seeded leaf input;
        //  - each producer's output == uniqueOutput(pid);
        //  - the consumer's bound input == merge keyed by produced name "p"+i → uniqueOutput(prodID).
        JsonNode seedInput = MAPPER.createObjectNode().put("seed", "S");
        ObjectNode expectedConsumerInput = MAPPER.createObjectNode();
        for (int i = 0; i < producers; i++) {
            expectedConsumerInput.set("p" + i, uniqueOutput(producerIds.get(i)));
        }

        Map<String, JsonNode> preBound = new HashMap<>();
        for (String pid : producerIds) preBound.put(pid, seedInput);

        for (int run = 0; run < RUNS_WIDE; run++) {
            SleepyAdapter adapter = new SleepyAdapter(Set.of(), 3);   // random 0..3ms sleep per node
            var exec = executor(harness(adapter), 30_000);
            Blackboard bb = new Blackboard(Set.of("seed"), preBound, MAPPER);

            List<NodeResult>[] holder = new List[1];
            assertThatCode(() -> holder[0] = exec.execute(plan, bb))
                    .as("run %d must not throw (no ConcurrentModificationException / lost update)", run)
                    .doesNotThrowAnyException();
            List<NodeResult> results = holder[0];

            // Every node terminal + OK.
            assertThat(results).as("run %d node count", run).hasSize(producers + 1);
            assertThat(results).as("run %d all OK", run).allMatch(NodeResult::isOk);

            // Value tracing: each producer output == its unique payload (no cross-wiring).
            Map<String, NodeResult> byId = new HashMap<>();
            for (NodeResult nr : results) byId.put(nr.nodeId(), nr);
            for (String pid : producerIds) {
                assertThat(byId.get(pid).data()).as("run %d producer %s output", run, pid)
                        .isEqualTo(uniqueOutput(pid));
                assertThat(adapter.inputs.get(pid)).as("run %d producer %s bound input", run, pid)
                        .isEqualTo(seedInput);
            }

            // THE KEY ORACLE: the consumer was bound with EXACTLY the merge of all 30 producers'
            // outputs, keyed by produced name — no missing, swapped, stale or duplicated value.
            JsonNode actualConsumerInput = adapter.inputs.get(consumerId);
            assertThat(actualConsumerInput).as("run %d : consumer must have been invoked", run).isNotNull();
            assertThat(actualConsumerInput.size()).as("run %d : consumer input has all 30 keys", run)
                    .isEqualTo(producers);
            assertThat(actualConsumerInput)
                    .as("run %d : consumer merged input == reference oracle\n  expected=%s\n  actual  =%s",
                            run, expectedConsumerInput, actualConsumerInput)
                    .isEqualTo(expectedConsumerInput);
        }
    }

    // ── mixed multi-layer DAG, run 100× ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("mixed 3-layer DAG × 100 runs: every node's bound input == reference oracle (pass-through + merge), every run")
    void multiLayerMatchesReferenceEveryRun() {
        // layer0: b0(→tA), b1(→tB)   layer1: m0(tA→tC), m1(tB→tD), m2(tA→tE)   layer2: top(tC,tD,tE→tF)
        // m0 and m2 both fan out from b0 (a diamond); top fans in from all three mids.
        PlanNode b0 = node("b0", io(List.of(entity("seed")), List.of(produce("a0", "tA"))), List.of());
        PlanNode b1 = node("b1", io(List.of(entity("seed")), List.of(produce("a1", "tB"))), List.of());
        PlanNode m0 = node("m0", io(List.of(from("tA")), List.of(produce("c0", "tC"))), List.of("b0"));
        PlanNode m1 = node("m1", io(List.of(from("tB")), List.of(produce("c1", "tD"))), List.of("b1"));
        PlanNode m2 = node("m2", io(List.of(from("tA")), List.of(produce("c2", "tE"))), List.of("b0"));
        PlanNode top = node("top",
                io(List.of(from("tC"), from("tD"), from("tE")), List.of(produce("f", "tF"))),
                List.of("m0", "m1", "m2"));
        Plan plan = new Plan(List.of(b0, b1, m0, m1, m2, top));

        JsonNode seedInput = MAPPER.createObjectNode().put("seed", "S");
        Map<String, JsonNode> preBound = Map.of("b0", seedInput, "b1", seedInput);

        // REFERENCE ORACLE for each node's expected bound input:
        //  b0,b1 ← seed leaf; m0,m2 ← pass-through uniqueOutput(b0); m1 ← pass-through uniqueOutput(b1);
        //  top ← merge {c0:uniqueOutput(m0), c1:uniqueOutput(m1), c2:uniqueOutput(m2)}.
        Map<String, JsonNode> expectedInput = new HashMap<>();
        expectedInput.put("b0", seedInput);
        expectedInput.put("b1", seedInput);
        expectedInput.put("m0", uniqueOutput("b0"));
        expectedInput.put("m2", uniqueOutput("b0"));
        expectedInput.put("m1", uniqueOutput("b1"));
        ObjectNode expectedTop = MAPPER.createObjectNode();
        expectedTop.set("c0", uniqueOutput("m0"));
        expectedTop.set("c1", uniqueOutput("m1"));
        expectedTop.set("c2", uniqueOutput("m2"));
        expectedInput.put("top", expectedTop);

        for (int run = 0; run < RUNS_LAYERED; run++) {
            SleepyAdapter adapter = new SleepyAdapter(Set.of(), 3);
            var exec = executor(harness(adapter), 30_000);
            Blackboard bb = new Blackboard(Set.of("seed"), preBound, MAPPER);

            List<NodeResult> results = exec.execute(plan, bb);

            assertThat(results).as("run %d node count", run).hasSize(6);
            assertThat(results).as("run %d all OK", run).allMatch(NodeResult::isOk);

            // Every node's actual bound input matches the reference oracle exactly (no cross-wiring).
            for (var e : expectedInput.entrySet()) {
                assertThat(adapter.inputs.get(e.getKey()))
                        .as("run %d : node %s bound input == oracle\n  expected=%s\n  actual  =%s",
                                run, e.getKey(), e.getValue(), adapter.inputs.get(e.getKey()))
                        .isEqualTo(e.getValue());
            }

            // Topological ordering was honoured: producers appear before their dependents.
            assertThat(adapter.order.indexOf("b0")).isLessThan(adapter.order.indexOf("m0"));
            assertThat(adapter.order.indexOf("b0")).isLessThan(adapter.order.indexOf("m2"));
            assertThat(adapter.order.indexOf("b1")).isLessThan(adapter.order.indexOf("m1"));
            assertThat(adapter.order.indexOf("m0")).isLessThan(adapter.order.indexOf("top"));
            assertThat(adapter.order.indexOf("m1")).isLessThan(adapter.order.indexOf("top"));
            assertThat(adapter.order.indexOf("m2")).isLessThan(adapter.order.indexOf("top"));
        }
    }
}
