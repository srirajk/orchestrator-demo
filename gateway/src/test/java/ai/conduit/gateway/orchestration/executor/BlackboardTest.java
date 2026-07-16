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

    private static AgentManifest.Consume fromRef(String type, boolean required, String select) {
        return new AgentManifest.Consume(null, type, required, select);
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

    /** A node builder that also carries a (derived) input schema, for {@code checkComposable} tests. */
    private static PlanNode nodeWithSchema(String id, AgentManifest.Io io, JsonNode inputSchema,
                                           JsonNode input, String... deps) {
        AgentManifest agentWithSchema = new AgentManifest(
                id, id, null, null, null, null, null, null, null, "http",
                null, null, null, null, io, inputSchema, null, null, true, null);
        return new PlanNode(id, agentWithSchema, input, List.of(deps));
    }

    /** A minimal JSON Schema requiring the given fields (mirrors an introspected consumer schema). */
    private static JsonNode schemaRequiring(String... fields) {
        var s = MAPPER.createObjectNode();
        s.put("type", "object");
        var props = s.putObject("properties");
        for (String f : fields) props.putObject(f).put("type", "array");
        var required = s.putArray("required");
        for (String f : fields) required.add(f);
        return s;
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

    // ── select (JMESPath edge projection) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("no select → identity pass-through (byte-for-byte, same reference) — default behavior unchanged")
    void noSelectIsIdentity() {
        PlanNode consumer = node("cons", io(List.of(fromRef("typeA", true)), List.of()), null, "prod");
        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        JsonNode producerOutput = obj("holdings", "payload");
        bb.project(node("prod", io(List.of(), List.of(produce("out", "typeA"))), null), producerOutput);

        assertThat(bb.bind(consumer)).isSameAs(producerOutput);
    }

    @Test
    @DisplayName("select reshapes the producer output into exactly the declared projection")
    void selectProjectsFields() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {"positions": [{"ticker":"AAPL","value":100}], "total_value": 100,
                 "agent_narrative": "some prose the consumer should never see"}
                """);
        AgentManifest.Consume edge = fromRef("wealth.holdings", true,
                "{'positions': has(input.positions) ? input.positions : null, 'total_value': has(input.total_value) ? input.total_value : null}");
        PlanNode consumer = node("cons", io(List.of(edge), List.of()), null, "prod");

        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        bb.project(node("prod", io(List.of(), List.of(produce("holdings", "wealth.holdings"))), null), raw);

        JsonNode bound = bb.bind(consumer);
        assertThat(bound.has("agent_narrative")).isFalse();
        // CEL canonicalizes JSON numbers to int64 (LongNode), so compare by value, not node identity
        // (a JMESPath IntNode and a CEL LongNode both represent 100 — see EngineParityTest's comparator).
        assertThat(bound.get("positions").get(0).get("ticker").asText()).isEqualTo("AAPL");
        assertThat(bound.get("positions").get(0).get("value").asInt()).isEqualTo(100);
        assertThat(bound.get("total_value").asInt()).isEqualTo(100);
    }

    // ── checkComposable: the fail-safe pre-dispatch gate ─────────────────────────────────────────

    @Test
    @DisplayName("entity-only consumer is never gated by checkComposable (untouched by this contract)")
    void entityOnlyConsumerNeverGated() {
        PlanNode leaf = nodeWithSchema("leaf", io(List.of(entityRef("entity")), List.of()),
                schemaRequiring("whatever"), obj("entity", "E-1"));
        Blackboard bb = new Blackboard(Set.of("entity"), Map.of(), MAPPER);
        assertThat(bb.checkComposable(leaf, leaf.input())).isNull();
    }

    @Test
    @DisplayName("well-formed select + matching schema → composable, no reason returned")
    void wellFormedInputIsComposable() {
        AgentManifest.Consume edge = fromRef("wealth.holdings", true,
                "{'positions': has(input.positions) ? input.positions : null}");
        JsonNode schema = schemaRequiring("positions");
        PlanNode consumer = nodeWithSchema("cons", io(List.of(edge), List.of()), schema, null, "prod");

        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        JsonNode raw = MAPPER.createObjectNode().set("positions",
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("ticker", "AAPL")));
        bb.project(node("prod", io(List.of(), List.of(produce("holdings", "wealth.holdings"))), null), raw);

        JsonNode bound = bb.bind(consumer);
        assertThat(bb.checkComposable(consumer, bound)).isNull();
    }

    @Test
    @DisplayName("WRONG-MAPPING FAILS SAFE: a select that maps the wrong field is caught before dispatch")
    void wrongMappingFailsSafe() {
        // The select maps a field that doesn't exist upstream ('positions_typo') into 'positions' —
        // the exact shape of a hand-aligned-then-drifted mapping. The consumer's schema requires
        // 'positions'; the projected input will have it null/absent.
        AgentManifest.Consume wrongEdge = fromRef("wealth.holdings", true,
                "{'positions': has(input.positions_typo) ? input.positions_typo : null}");
        JsonNode schema = schemaRequiring("positions");
        PlanNode consumer = nodeWithSchema("cons", io(List.of(wrongEdge), List.of()), schema, null, "prod");

        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        JsonNode raw = MAPPER.createObjectNode().set("positions",
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("ticker", "AAPL")));
        bb.project(node("prod", io(List.of(), List.of(produce("holdings", "wealth.holdings"))), null), raw);

        JsonNode bound = bb.bind(consumer);
        String reason = bb.checkComposable(consumer, bound);
        assertThat(reason).isNotNull();
        assertThat(reason).contains("could not compose").contains("positions");
    }

    @Test
    @DisplayName("CEL guard: an ABSENT required field projects key-present-null and is attributed by name")
    void requiredFieldAbsentStillAttributesField() {
        // The guarded select unconditionally emits the key (value null) when the source field is absent —
        // reproducing JMESPath's key-present-value-null shape so one absent field does not collapse the
        // whole edge to MissingNode, and missingFields still names the exact producer/field.
        AgentManifest.Consume edge = fromRef("wealth.holdings", true,
                "{'positions': has(input.positions) ? input.positions : null}");
        JsonNode schema = schemaRequiring("positions");
        PlanNode consumer = nodeWithSchema("cons", io(List.of(edge), List.of()), schema, null, "prod");

        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        JsonNode raw = MAPPER.createObjectNode().put("unrelated", "value");   // no 'positions'
        bb.project(node("prod", io(List.of(), List.of(produce("holdings", "wealth.holdings"))), null), raw);

        JsonNode bound = bb.bind(consumer);
        assertThat(bound.has("positions")).as("guarded key is present even when absent upstream").isTrue();
        assertThat(bound.get("positions").isNull()).isTrue();

        String reason = bb.checkComposable(consumer, bound);
        assertThat(reason).isNotNull();
        assertThat(reason).contains("positions");
    }

    @Test
    @DisplayName("INCOMPLETE INPUT REFUSED: a producer output marked _complete=false fails the consumer")
    void incompleteUpstreamFailsSafe() {
        AgentManifest.Consume edge = fromRef("wealth.holdings", true, "{'positions': has(input.positions) ? input.positions : null}");
        JsonNode schema = schemaRequiring("positions");
        PlanNode consumer = nodeWithSchema("cons", io(List.of(edge), List.of()), schema, null, "prod");

        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        JsonNode raw = MAPPER.createObjectNode()
                .put("_complete", false)
                .set("positions", MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("ticker", "AAPL")));
        bb.project(node("prod", io(List.of(), List.of(produce("holdings", "wealth.holdings"))), null), raw);

        JsonNode bound = bb.bind(consumer);
        String reason = bb.checkComposable(consumer, bound);
        assertThat(reason).isNotNull();
        assertThat(reason).containsIgnoringCase("incomplete");
    }

    @Test
    @DisplayName("no schema declared → checkComposable is a no-op (today's behavior for pre-existing agents)")
    void noSchemaIsPassOpen() {
        AgentManifest.Consume edge = fromRef("wealth.holdings", true, "{'positions': has(input.positions_typo) ? input.positions_typo : null}");
        PlanNode consumer = node("cons", io(List.of(edge), List.of()), null, "prod");   // no inputSchema

        Blackboard bb = new Blackboard(Set.of(), Map.of(), MAPPER);
        JsonNode raw = obj("positions", "irrelevant");
        bb.project(node("prod", io(List.of(), List.of(produce("holdings", "wealth.holdings"))), null), raw);

        JsonNode bound = bb.bind(consumer);
        assertThat(bb.checkComposable(consumer, bound)).isNull();
    }
}
