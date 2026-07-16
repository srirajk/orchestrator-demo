package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 harness test 12 — the load-bearing reference-identity proof. After the NodeResult/PayloadHandle
 * change, {@link Blackboard#project} still stores the producer's output tree by reference and
 * {@link Blackboard#bind} of a single-producer consumer returns that SAME reference (identity
 * pass-through, no clone, no re-parse). {@code Blackboard} / {@code project} signatures are untouched, so
 * the whole DAG dataflow is bit-for-bit as before.
 */
class BlackboardHandleIdentityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentManifest agent(String id, AgentManifest.Io io) {
        return new AgentManifest(id, id, null, null, null, null, null, null, null, "http",
                null, null, null, null, io, null, null, null, true, null);
    }

    private static PlanNode node(String id, AgentManifest.Io io, JsonNode input) {
        return new PlanNode(id, agent(id, io), input, List.of());
    }

    @Test
    void bindReturnsTheSameProducedReference() {
        AgentManifest.Io producerIo = new AgentManifest.Io(
                null, List.of(new AgentManifest.Produce("out", "T")));
        AgentManifest.Io consumerIo = new AgentManifest.Io(
                List.of(new AgentManifest.Consume(null, "T", true)), null);

        PlanNode producer = node("p", producerIo, null);
        PlanNode consumer = node("c", consumerIo, null);

        JsonNode producerOutput = MAPPER.createObjectNode().put("k", "v");

        Blackboard board = new Blackboard(Set.of(), Map.of(), MAPPER);
        board.project(producer, producerOutput);

        JsonNode bound = board.bind(consumer);
        assertThat(bound)
                .as("single-producer pass-through binds the identical reference (no clone)")
                .isSameAs(producerOutput);
    }
}
