package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * TECHNIQUE 7 — Partial-failure / chaos (hard-rule d: a failed agent never cancels its siblings).
 *
 * <p>Randomly fails {@code k} of {@code n} producers feeding a consumer and asserts: the executor
 * never throws; ALL surviving producers still complete and produce their correct unique output; the
 * consumer is marked unmet/skipped (never invoked, never fed a partial input); and a failure in one
 * branch never cancels an independent branch.
 */
class DagExecutorChaosTest {

    @Test
    @DisplayName("chaos: fail k of n producers (seeded, 60 iterations) → consumer skipped, ALL survivors still OK, never throws")
    void randomProducerFailuresLeaveSurvivorsIntact() {
        int producers = 12;
        long[] seeds = {1L, 7L, 42L, 99L, 2026L, 0xC0FFEEL};

        // Plan: n producers → 1 consumer that depends on all of them.
        List<PlanNode> baseNodes = new ArrayList<>();
        List<AgentManifest.Consume> consumerConsumes = new ArrayList<>();
        List<String> producerIds = new ArrayList<>();
        for (int i = 0; i < producers; i++) {
            String pid = String.format("prod%02d", i);
            producerIds.add(pid);
            baseNodes.add(node(pid, io(List.of(entity("seed")), List.of(produce("p" + i, "t" + i))), List.of()));
            consumerConsumes.add(from("t" + i));
        }
        String consumerId = "cons";
        baseNodes.add(node(consumerId, io(consumerConsumes, List.of(produce("cout", "tFinal"))), producerIds));
        Plan plan = new Plan(baseNodes);

        JsonNode seedInput = MAPPER.createObjectNode().put("seed", "S");
        Map<String, JsonNode> preBound = new HashMap<>();
        for (String pid : producerIds) preBound.put(pid, seedInput);

        int iterations = 0;
        for (long seed : seeds) {
            Random rnd = new Random(seed);
            for (int rep = 0; rep < 10; rep++) {
                // Pick a random non-empty subset of producers to fail (1..producers of them).
                Set<String> failIds = new HashSet<>();
                int k = 1 + rnd.nextInt(producers);
                List<String> shuffled = new ArrayList<>(producerIds);
                java.util.Collections.shuffle(shuffled, rnd);
                for (int i = 0; i < k; i++) failIds.add(shuffled.get(i));
                String ctx = "seed=" + seed + " rep=" + rep + " failing " + k + "/" + producers;

                SleepyAdapter adapter = new SleepyAdapter(failIds, 2);
                var exec = executor(harness(adapter), 30_000);
                Blackboard bb = new Blackboard(Set.of("seed"), preBound, MAPPER);

                List<NodeResult>[] holder = new List[1];
                assertThatCode(() -> holder[0] = exec.execute(plan, bb))
                        .as("%s : executor must never throw", ctx).doesNotThrowAnyException();
                List<NodeResult> results = holder[0];

                Map<String, NodeResult> byId = new HashMap<>();
                for (NodeResult nr : results) byId.put(nr.nodeId(), nr);

                assertThat(results).as("%s : one result per node", ctx).hasSize(producers + 1);

                for (String pid : producerIds) {
                    NodeResult pr = byId.get(pid);
                    if (failIds.contains(pid)) {
                        assertThat(pr.isOk()).as("%s : %s failed", ctx, pid).isFalse();
                        assertThat(pr.status()).isEqualTo(NodeResult.Status.FAILED);
                    } else {
                        // Survivor: still ran, still produced its correct unique output.
                        assertThat(pr.isOk()).as("%s : survivor %s OK", ctx, pid).isTrue();
                        assertThat(pr.data()).as("%s : survivor %s output", ctx, pid)
                                .isEqualTo(uniqueOutput(pid));
                    }
                }

                // Consumer depends on a failed producer ⇒ skipped/unmet, never invoked with partial data.
                NodeResult cons = byId.get(consumerId);
                assertThat(cons.isOk()).as("%s : consumer unmet", ctx).isFalse();
                assertThat(cons.errorMessage()).as("%s : consumer skip reason", ctx)
                        .containsIgnoringCase("upstream dependency");
                assertThat(adapter.order).as("%s : consumer never invoked", ctx).doesNotContain(consumerId);

                iterations++;
            }
        }
        assertThat(iterations).isEqualTo(60);
    }

    @Test
    @DisplayName("isolation: a failed branch never cancels an independent branch (both chains, one poisoned)")
    void failedBranchDoesNotCancelIndependentBranch() {
        // Two independent chains: X = prodX → consX ; Y = prodY → consY. Only prodX fails.
        PlanNode prodX = node("prodX", io(List.of(entity("seed")), List.of(produce("px", "tX"))), List.of());
        PlanNode consX = node("consX", io(List.of(from("tX")), List.of(produce("cx", "tXo"))), List.of("prodX"));
        PlanNode prodY = node("prodY", io(List.of(entity("seed")), List.of(produce("py", "tY"))), List.of());
        PlanNode consY = node("consY", io(List.of(from("tY")), List.of(produce("cy", "tYo"))), List.of("prodY"));
        Plan plan = new Plan(List.of(prodX, consX, prodY, consY));

        JsonNode seedInput = MAPPER.createObjectNode().put("seed", "S");
        Map<String, JsonNode> preBound = Map.of("prodX", seedInput, "prodY", seedInput);

        // Run repeatedly so timing jitter cannot let the poisoned branch bleed into the healthy one.
        for (int run = 0; run < 50; run++) {
            SleepyAdapter adapter = new SleepyAdapter(Set.of("prodX"), 3);
            var exec = executor(harness(adapter), 30_000);
            Blackboard bb = new Blackboard(Set.of("seed"), preBound, MAPPER);

            List<NodeResult> results = exec.execute(plan, bb);
            Map<String, NodeResult> byId = new HashMap<>();
            for (NodeResult nr : results) byId.put(nr.nodeId(), nr);

            // Poisoned branch: prodX FAILED, consX skipped/unmet, never invoked.
            assertThat(byId.get("prodX").status()).as("run %d prodX", run).isEqualTo(NodeResult.Status.FAILED);
            assertThat(byId.get("consX").isOk()).as("run %d consX unmet", run).isFalse();
            assertThat(adapter.order).as("run %d consX not invoked", run).doesNotContain("consX");

            // Independent branch fully unaffected: prodY OK and consY OK with the correct traced value.
            assertThat(byId.get("prodY").isOk()).as("run %d prodY OK", run).isTrue();
            assertThat(byId.get("prodY").data()).isEqualTo(uniqueOutput("prodY"));
            assertThat(byId.get("consY").isOk()).as("run %d consY OK", run).isTrue();
            assertThat(byId.get("consY").data()).isEqualTo(uniqueOutput("consY"));
            // consY's bound input was the pass-through of prodY's output (no cross-wiring from branch X).
            assertThat(adapter.inputs.get("consY")).as("run %d consY input", run).isEqualTo(uniqueOutput("prodY"));
        }
    }
}
