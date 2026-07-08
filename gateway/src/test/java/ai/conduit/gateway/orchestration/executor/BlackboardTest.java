package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link Blackboard} binding convention. Type/entity/name symbols are generic
 * placeholders — the blackboard reasons over manifest strings only and embeds no domain knowledge.
 */
class BlackboardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── builders ────────────────────────────────────────────────────────────────────────────────

    private static AgentManifest.Io io(List<AgentManifest.Consume> consumes,
                                       List<AgentManifest.Produce> produces) {
        return new AgentManifest.Io(consumes, produces);
    }

    private static AgentManifest agent(String id, AgentManifest.Io io) {
        return new AgentManifest(
                id, id, null, null, null, null, null, null, null, "http",
                null, null, null, null, io, null, null, null, true, null);
    }

    private static PlanNode node(String id, AgentManifest.Io io, JsonNode input, String... deps) {
        return new PlanNode(id, agent(id, io), input, List.of(deps));
    }

    private static AgentManifest.Consume fromRef(String type, boolean required) {
        return new AgentManifest.Consume(null, type, required);
    }

    private static AgentManifest.Consume entityRef(String key) {
        return new AgentManifest.Consume(key, null, true);
    }

    private static AgentManifest.Produce produce(String name, String type) {
        return new AgentManifest.Produce(name, type);
    }

    private static JsonNode obj(String k, String v) {
        return MAPPER.createObjectNode().put(k, v);
    }

    // ── entity-only consumer: keeps today's pre-bound leaf input ─────────────────────────────────

    @Test
    @DisplayName("entity-only consumer keeps its pre-bound leaf input (undisturbed)")
    void entityConsumerKeepsPreBoundInput() {
        JsonNode leafInput = obj("entity", "E-1");
        PlanNode leaf = node("leaf", io(List.of(entityRef("entity")), List.of(produce("out", "typeA"))), null);

        Blackboard bb = new Blackboard(Set.of("entity"), Map.of("leaf", leafInput), MAPPER);

        assertThat(bb.bind(leaf)).isEqualTo(leafInput);
    }

    // ── single producer: pass-through ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("exactly one produced input → producer output passed through verbatim")
    void singleProducerPassThrough() {
        PlanNode producer = node("prod", io(List.of(entityRef("entity")), List.of(produce("holdings", "typeA"))), null);
        PlanNode consumer = node("cons", io(List.of(fromRef("typeA", true)), List.of(produce("conc", "typeB"))), null, "prod");

        Blackboard bb = new Blackboard(Set.of("entity"), Map.of(), MAPPER);
        JsonNode producerOutput = obj("holdings", "payload");
        bb.project(producer, producerOutput);

        assertThat(bb.hasType("typeA")).isTrue();
        assertThat(bb.bind(consumer)).isSameAs(producerOutput);
    }

    // ── one required + one optional-missing → still single pass-through ──────────────────────────

    @Test
    @DisplayName("one required present + one optional missing → pass-through the required output")
    void requiredPresentOptionalMissing() {
        PlanNode consumer = node("cons",
                io(List.of(fromRef("typeA", true), fromRef("typeB", false)),
                        List.of(produce("conc", "typeC"))), null, "prodA");

        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        JsonNode outA = obj("a", "1");
        bb.project(node("prodA", io(List.of(), List.of(produce("a", "typeA"))), null), outA);
        // typeB never produced (optional producer didn't run)

        assertThat(bb.bind(consumer)).isSameAs(outA);
    }

    // ── multiple producers: merge keyed by produced name ────────────────────────────────────────

    @Test
    @DisplayName("multiple produced inputs → merged into one object keyed by produced name")
    void multipleProducersMerge() {
        PlanNode consumer = node("cons",
                io(List.of(fromRef("typeA", true), fromRef("typeB", true)),
                        List.of(produce("conc", "typeC"))), null, "prodA", "prodB");

        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        JsonNode outA = obj("x", "1");
        JsonNode outB = obj("y", "2");
        bb.project(node("prodA", io(List.of(), List.of(produce("alpha", "typeA"))), null), outA);
        bb.project(node("prodB", io(List.of(), List.of(produce("beta", "typeB"))), null), outB);

        JsonNode bound = bb.bind(consumer);
        assertThat(bound.get("alpha")).isEqualTo(outA);
        assertThat(bound.get("beta")).isEqualTo(outB);
    }

    // ── not-yet-produced upstream → falls back to pre-bound/null (no crash) ──────────────────────

    @Test
    @DisplayName("from-consumer with nothing produced yet falls back to pre-bound input")
    void fromConsumerBeforeProduction() {
        PlanNode consumer = node("cons", io(List.of(fromRef("typeA", true)), List.of()), null, "prod");
        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        assertThat(bb.bind(consumer)).isNull();   // no producer output, no pre-bound input
    }
}
