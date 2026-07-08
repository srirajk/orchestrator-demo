package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Hermetic tests for {@link DagPlanExecutor}. Uses the <b>real</b> {@link AgentHarness} (real
 * Resilience4j registries) wired to a recording fake {@link ProtocolAdapter}, so binding, layering,
 * and partial-failure behaviour are exercised end-to-end without docker.
 *
 * <p>Plan under test (the flagship shape): {@code producer → consumer}. Symbols are generic
 * placeholders; the executor carries no domain knowledge.
 */
class DagPlanExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Tracer TRACER = OpenTelemetry.noop().getTracer("test");

    private static final String PRODUCER = "cap.producer";
    private static final String CONSUMER = "cap.consumer";
    private static final String OUT_TYPE = "typeOut";

    // ── manifest / plan builders ─────────────────────────────────────────────────────────────────

    private static AgentManifest agent(String id, AgentManifest.Io io) {
        return new AgentManifest(
                id, id, null, null, null, null, null, null, null, "http",
                null, null, null,
                new AgentManifest.Constraints("read", "internal", 5_000),
                io, null, null, null, true, null);
    }

    private static AgentManifest.Io producerIo() {
        return new AgentManifest.Io(
                List.of(new AgentManifest.Consume("entity", null, true)),
                List.of(new AgentManifest.Produce("producerOut", OUT_TYPE)));
    }

    private static AgentManifest.Io consumerIo() {
        return new AgentManifest.Io(
                List.of(new AgentManifest.Consume(null, OUT_TYPE, true)),
                List.of(new AgentManifest.Produce("consumerOut", "typeFinal")));
    }

    /** Two-node plan producer → consumer, in topological order, as the resolver would emit it. */
    private static Plan producerConsumerPlan() {
        PlanNode producer = new PlanNode(PRODUCER, agent(PRODUCER, producerIo()), null, List.of());
        PlanNode consumer = new PlanNode(CONSUMER, agent(CONSUMER, consumerIo()), null, List.of(PRODUCER));
        return new Plan(List.of(producer, consumer));
    }

    // ── data-contract plan: consumer declares a select + an introspected input schema ──────────────

    private static AgentManifest agentWithSchema(String id, AgentManifest.Io io, JsonNode inputSchema) {
        return new AgentManifest(
                id, id, null, null, null, null, null, null, null, "http",
                null, null, null,
                new AgentManifest.Constraints("read", "internal", 5_000),
                io, inputSchema, null, null, true, null);
    }

    private static JsonNode schemaRequiring(String field) {
        var s = MAPPER.createObjectNode();
        s.put("type", "object");
        var props = s.putObject("properties");
        props.putObject(field).put("type", "array");
        s.putArray("required").add(field);
        return s;
    }

    /** Consumer whose edge maps the WRONG upstream field into 'positions' (a string, not an array). */
    private static Plan producerConsumerPlanWithWrongSelect() {
        AgentManifest.Io badConsumerIo = new AgentManifest.Io(
                List.of(new AgentManifest.Consume(null, OUT_TYPE, true, "{positions: holdings}")),
                List.of(new AgentManifest.Produce("consumerOut", "typeFinal")));
        PlanNode producer = new PlanNode(PRODUCER, agent(PRODUCER, producerIo()), null, List.of());
        PlanNode consumer = new PlanNode(CONSUMER,
                agentWithSchema(CONSUMER, badConsumerIo, schemaRequiring("positions")), null, List.of(PRODUCER));
        return new Plan(List.of(producer, consumer));
    }

    /** Consumer with a correct select, requiring the upstream NOT be marked incomplete. */
    private static Plan producerConsumerPlanRequiringComplete() {
        AgentManifest.Io consumerIoOk = new AgentManifest.Io(
                List.of(new AgentManifest.Consume(null, OUT_TYPE, true, "{positions: positions}")),
                List.of(new AgentManifest.Produce("consumerOut", "typeFinal")));
        PlanNode producer = new PlanNode(PRODUCER, agent(PRODUCER, producerIo()), null, List.of());
        PlanNode consumer = new PlanNode(CONSUMER,
                agentWithSchema(CONSUMER, consumerIoOk, schemaRequiring("positions")), null, List.of(PRODUCER));
        return new Plan(List.of(producer, consumer));
    }

    // ── recording adapter ────────────────────────────────────────────────────────────────────────

    /** Fake HTTP adapter: records invocation order + the input each agent was called with. */
    private static final class RecordingAdapter implements ProtocolAdapter {
        final List<String> order = new CopyOnWriteArrayList<>();
        final Map<String, JsonNode> inputs = new ConcurrentHashMap<>();
        final boolean producerFails;
        final JsonNode producerOutput;   // null → default {"holdings":"PAYLOAD"}

        RecordingAdapter(boolean producerFails) { this(producerFails, null); }

        RecordingAdapter(boolean producerFails, JsonNode producerOutput) {
            this.producerFails = producerFails;
            this.producerOutput = producerOutput;
        }

        @Override public String protocol() { return "http"; }

        @Override public JsonNode invoke(AgentManifest m, JsonNode input) {
            order.add(m.agentId());
            if (input != null) inputs.put(m.agentId(), input);
            if (PRODUCER.equals(m.agentId())) {
                if (producerFails) throw new RuntimeException("simulated producer failure");
                return producerOutput != null ? producerOutput : MAPPER.createObjectNode().put("holdings", "PAYLOAD");
            }
            return MAPPER.createObjectNode().put("ok", true);
        }
    }

    private AgentHarness harness(ProtocolAdapter adapter) {
        return new AgentHarness(List.of(adapter), CircuitBreakerRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                5_000, 5, 20, 50, 80, 10, 30, 10, 3, 2);
    }

    private DagPlanExecutor executor(AgentHarness harness) {
        MeterRegistry meters = new SimpleMeterRegistry();
        return new DagPlanExecutor(harness, TRACER, meters, 60_000);
    }

    // ── happy path: producer output becomes the consumer's bound input ───────────────────────────

    @Test
    @DisplayName("consumer's bound input equals the producer's output; order respects the edge")
    void bindsProducerOutputAndRespectsOrder() {
        RecordingAdapter adapter = new RecordingAdapter(false);
        DagPlanExecutor exec = executor(harness(adapter));

        JsonNode leafInput = MAPPER.createObjectNode().put("entity", "E-1");
        Blackboard bb = new Blackboard(Set.of("entity"), Map.of(PRODUCER, leafInput), MAPPER);

        List<NodeResult> results = exec.execute(producerConsumerPlan(), bb);

        // Both succeeded, returned in topological order.
        assertThat(results).hasSize(2);
        assertThat(results.get(0).nodeId()).isEqualTo(PRODUCER);
        assertThat(results.get(1).nodeId()).isEqualTo(CONSUMER);
        assertThat(results).allMatch(NodeResult::isOk);

        // Execution order honoured the dependency edge.
        assertThat(adapter.order).containsExactly(PRODUCER, CONSUMER);

        // The producer was called with the seeded leaf input.
        assertThat(adapter.inputs.get(PRODUCER)).isEqualTo(leafInput);

        // THE KEY ASSERTION: the consumer's bound input == the producer's output payload.
        JsonNode producerOutput = results.get(0).data();
        assertThat(adapter.inputs.get(CONSUMER)).isEqualTo(producerOutput);
        assertThat(adapter.inputs.get(CONSUMER).get("holdings").asText()).isEqualTo("PAYLOAD");
    }

    // ── partial failure: producer fails → consumer skipped, no throw ─────────────────────────────

    @Test
    @DisplayName("producer failure → consumer marked unmet/skipped, plan never aborts, no throw")
    void producerFailureSkipsDependent() {
        RecordingAdapter adapter = new RecordingAdapter(true);
        DagPlanExecutor exec = executor(harness(adapter));

        JsonNode leafInput = MAPPER.createObjectNode().put("entity", "E-1");
        Blackboard bb = new Blackboard(Set.of("entity"), Map.of(PRODUCER, leafInput), MAPPER);

        List<NodeResult>[] holder = new List[1];
        assertThatCode(() -> holder[0] = exec.execute(producerConsumerPlan(), bb)).doesNotThrowAnyException();
        List<NodeResult> results = holder[0];

        assertThat(results).hasSize(2);

        NodeResult producer = results.get(0);
        NodeResult consumer = results.get(1);
        assertThat(producer.nodeId()).isEqualTo(PRODUCER);
        assertThat(producer.status()).isEqualTo(NodeResult.Status.FAILED);

        // Consumer is skipped (unmet) — not executed — because its dependency failed.
        assertThat(consumer.nodeId()).isEqualTo(CONSUMER);
        assertThat(consumer.isOk()).isFalse();
        assertThat(consumer.errorMessage()).containsIgnoringCase("upstream dependency");

        // The consumer's agent was never invoked.
        assertThat(adapter.order).containsExactly(PRODUCER);
        assertThat(adapter.inputs).doesNotContainKey(CONSUMER);
    }

    // ── data contract: wrong select fails safe; never a 422, never a wrong number ────────────────

    @Test
    @DisplayName("WRONG-MAPPING FAILS SAFE end-to-end: consumer never dispatched, plan degrades honestly")
    void wrongSelectNeverDispatchesConsumer() {
        RecordingAdapter adapter = new RecordingAdapter(false);
        DagPlanExecutor exec = executor(harness(adapter));

        JsonNode leafInput = MAPPER.createObjectNode().put("entity", "E-1");
        Blackboard bb = new Blackboard(Set.of("entity"), Map.of(PRODUCER, leafInput), MAPPER);

        List<NodeResult> results = exec.execute(producerConsumerPlanWithWrongSelect(), bb);

        assertThat(results).hasSize(2);
        NodeResult producer = results.get(0);
        NodeResult consumer = results.get(1);
        assertThat(producer.isOk()).isTrue();   // the producer itself is fine

        // The consumer is FAILED — but via the compose gate, never via a 422 from the agent.
        assertThat(consumer.isOk()).isFalse();
        assertThat(consumer.errorMessage()).contains("could not compose");

        // The consumer's agent was NEVER invoked — no malformed request ever left the gateway.
        assertThat(adapter.order).containsExactly(PRODUCER);
        assertThat(adapter.inputs).doesNotContainKey(CONSUMER);
    }

    @Test
    @DisplayName("INCOMPLETE INPUT REFUSED: upstream _complete=false fails the consumer, no wrong number")
    void incompleteUpstreamNeverDispatchesConsumer() {
        JsonNode incompleteHoldings = MAPPER.createObjectNode()
                .put("_complete", false)
                .set("positions", MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("ticker", "AAPL")));
        RecordingAdapter adapter = new RecordingAdapter(false, incompleteHoldings);
        DagPlanExecutor exec = executor(harness(adapter));

        JsonNode leafInput = MAPPER.createObjectNode().put("entity", "E-1");
        Blackboard bb = new Blackboard(Set.of("entity"), Map.of(PRODUCER, leafInput), MAPPER);

        List<NodeResult> results = exec.execute(producerConsumerPlanRequiringComplete(), bb);

        assertThat(results).hasSize(2);
        NodeResult producer = results.get(0);
        NodeResult consumer = results.get(1);
        assertThat(producer.isOk()).isTrue();

        assertThat(consumer.isOk()).isFalse();
        assertThat(consumer.errorMessage()).containsIgnoringCase("incomplete");

        assertThat(adapter.order).containsExactly(PRODUCER);
        assertThat(adapter.inputs).doesNotContainKey(CONSUMER);
    }
}
