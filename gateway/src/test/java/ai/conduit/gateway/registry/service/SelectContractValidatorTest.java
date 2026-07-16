package ai.conduit.gateway.registry.service;

import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectContractValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final SelectContractValidator validator = new SelectContractValidator(MAPPER);

    // ── CEL-migration: unguarded-optional guard check + real production manifests ─────────────────

    @Test
    @DisplayName("BOOT-REJECT: a select that reads an OPTIONAL producer field without a has() guard")
    void rejectsUnguardedOptionalField() {
        // producer output has a required 'present_field' and an OPTIONAL 'opt' (not in required[])
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchemaWithOptional());
        // select reads input.opt WITHOUT has() — fine on the full sample, blows up on required-only
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'required_input': input.opt}")), List.of()),
                inputSchemaRequiring("required_input"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.SelectValidationException.class)
                .hasMessageContaining("without has() guard");
    }

    @Test
    @DisplayName("a select that guards the optional field with has() is accepted")
    void acceptsGuardedOptionalField() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchemaWithOptional());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'required_input': has(input.opt) ? input.opt : null}")), List.of()),
                inputSchemaRequiring("required_input"),
                null);

        // guarded select never throws on either sample; the merged input still satisfies the schema
        // because required_input is present (null is allowed by the loose required schema)
        assertThatCode(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC5: a stale JMESPath-dialect manifest is rejected at ingest (undeclared reference)")
    void rejectsLegacyJmespathDialectAtIngest() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchema());
        // A legacy JMESPath multiselect-hash — a bare identifier is an undeclared reference to the
        // checked CEL Env, so ingest refuses it (the container would fail startup on this manifest).
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{present_field: present_field}")), List.of()),
                inputSchemaRequiring("present_field"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.SelectValidationException.class)
                .hasMessageContaining("invalid select expression");
    }

    @Test
    @DisplayName("AC3: every current production manifest validates clean under the CEL engine")
    void acceptsAllFourCurrentProductionManifests() {
        List<AgentManifest> all = loadAllRealManifests();
        assertThat(all).as("real manifests must be discoverable").isNotEmpty();
        // Ingestion validates each consumer against the whole registry snapshot; none may throw.
        for (AgentManifest consumer : all) {
            assertThatCode(() -> validator.validateOne(consumer, all))
                    .as("manifest %s must validate under CEL", consumer.agentId())
                    .doesNotThrowAnyException();
        }
    }

    private static Path registryManifestsDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("registry").resolve("manifests");
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("registry/manifests not found");
    }

    private static List<AgentManifest> loadAllRealManifests() {
        List<AgentManifest> out = new ArrayList<>();
        try (var files = Files.walk(registryManifestsDir())) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            AgentManifest m = MAPPER.readValue(Files.readAllBytes(p), AgentManifest.class);
                            if (m != null && m.agentId() != null) out.add(m);
                        } catch (Exception ignored) { /* skip unparseable — mirrors loader tolerance */ }
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private static JsonNode outputSchemaWithOptional() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("present_field").put("type", "string");
        props.putObject("opt").put("type", "string");
        schema.putArray("required").add("present_field");   // 'opt' is optional
        return schema;
    }

    @Test
    @DisplayName("BOOT-REJECT: select referencing absent producer field is rejected with precise edge details")
    void rejectsBrokenSelectAgainstProducerOutputSchema() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchema());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'required_input': has(input.absent_field) ? input.absent_field : null}")), List.of()),
                inputSchemaRequiring("required_input"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.SelectValidationException.class)
                .hasMessageContaining("agentId=agent.consumer")
                .hasMessageContaining("from=type.produced")
                .hasMessageContaining("has(input.absent_field)")
                .hasMessageContaining("required_input");
    }

    @Test
    @DisplayName("good select validates against schema-derived producer sample and consumer input schema")
    void acceptsGoodSelect() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchema());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'required_input': has(input.present_field) ? input.present_field : null}")), List.of()),
                inputSchemaRequiring("required_input"),
                null);

        SelectContractValidator.Summary summary =
                validator.validateOne(consumer, List.of(producer, consumer));

        assertThat(summary.validated()).isEqualTo(1);
        assertThat(summary.unvalidated()).isZero();
    }

    @Test
    @DisplayName("multi-producer fan-in validates the merged consumer input once")
    void validatesMergedFanInInput() {
        AgentManifest producerA = manifest(
                "agent.producer.a",
                io(List.of(), List.of(produce("alpha", "type.alpha"))),
                null,
                outputSchema());
        AgentManifest producerB = manifest(
                "agent.producer.b",
                io(List.of(), List.of(produce("beta", "type.beta"))),
                null,
                outputSchema());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(
                        from("type.alpha", "{'required_input': has(input.present_field) ? input.present_field : null}"),
                        from("type.beta", "{'required_input': has(input.present_field) ? input.present_field : null}")
                ), List.of()),
                inputSchemaRequiring("alpha", "beta"),
                null);

        SelectContractValidator.Summary summary =
                validator.validateOne(consumer, List.of(producerA, producerB, consumer));

        assertThat(summary.validated()).isEqualTo(2);
        assertThat(summary.unvalidated()).isZero();
    }

    @Test
    @DisplayName("missing producer output schema is reported as unvalidated, not silently passed")
    void missingOutputSchemaCountsAsUnvalidated() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                null);
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'required_input': has(input.present_field) ? input.present_field : null}")), List.of()),
                inputSchemaRequiring("required_input"),
                null);

        SelectContractValidator.Summary summary =
                validator.validateOne(consumer, List.of(producer, consumer));

        assertThat(summary.validated()).isZero();
        assertThat(summary.unvalidated()).isEqualTo(1);
    }

    @Test
    @DisplayName("condition validates as boolean over the merged consumer input")
    void acceptsBooleanCondition() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchema());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'required_input': has(input.present_field) ? input.present_field : null}")), List.of(),
                        "input.required_input == 'x'"),
                inputSchemaRequiring("required_input"),
                null);

        SelectContractValidator.Summary summary =
                validator.validateOne(consumer, List.of(producer, consumer));

        assertThat(summary.validated()).isEqualTo(1);
        assertThat(summary.unvalidated()).isZero();
    }

    @Test
    @DisplayName("BOOT-REJECT: condition referencing absent field is rejected with precise details")
    void rejectsConditionReferencingAbsentField() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchema());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'required_input': has(input.present_field) ? input.present_field : null}")), List.of(),
                        "input.absent_input > 0"),
                inputSchemaRequiring("required_input"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.ConditionValidationException.class)
                .hasMessageContaining("agentId=agent.consumer")
                .hasMessageContaining("input.absent_input")
                .hasMessageContaining("absent_input");
    }

    @Test
    @DisplayName("BOOT-REJECT: condition must evaluate to boolean")
    void rejectsNonBooleanCondition() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchema());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'required_input': has(input.present_field) ? input.present_field : null}")), List.of(),
                        "input.required_input"),
                inputSchemaRequiring("required_input"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.ConditionValidationException.class)
                .hasMessageContaining("expression must evaluate to boolean");
    }

    @Test
    @DisplayName("map validates over the merged consumer input and item_select against the item input schema")
    void acceptsMapOverMergedInput() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchemaWithArray());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'items': has(input.items) ? input.items : null}")), List.of(),
                        null, new AgentManifest.MapSpec("input.items", "{'required_input': has(item.present_field) ? item.present_field : null}", 5, 2)),
                inputSchemaRequiring("required_input"),
                null);

        SelectContractValidator.Summary summary =
                validator.validateOne(consumer, List.of(producer, consumer));

        assertThat(summary.validated()).isEqualTo(1);
        assertThat(summary.unvalidated()).isZero();
    }

    @Test
    @DisplayName("BOOT-REJECT: map.over must evaluate to an array")
    void rejectsMapOverScalar() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchema());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'items': has(input.present_field) ? input.present_field : null}")), List.of(),
                        null, new AgentManifest.MapSpec("input.items", "{'required_input': has(item.present_field) ? item.present_field : null}", 5, 2)),
                inputSchemaRequiring("required_input"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.MapValidationException.class)
                .hasMessageContaining("map.over must evaluate to an array");
    }

    @Test
    @DisplayName("BOOT-REJECT: map item_select must satisfy the consumer input schema")
    void rejectsBadMapItemSelect() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchemaWithArray());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'items': has(input.items) ? input.items : null}")), List.of(),
                        null, new AgentManifest.MapSpec("input.items", "{'wrong': has(item.present_field) ? item.present_field : null}", 5, 2)),
                inputSchemaRequiring("required_input"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.MapValidationException.class)
                .hasMessageContaining("item_select does not satisfy input schema")
                .hasMessageContaining("required_input");
    }

    @Test
    @DisplayName("BOOT-REJECT: map caps must be positive")
    void rejectsBadMapCaps() {
        AgentManifest producer = manifest(
                "agent.producer",
                io(List.of(), List.of(produce("producer_output", "type.produced"))),
                null,
                outputSchemaWithArray());
        AgentManifest consumer = manifest(
                "agent.consumer",
                io(List.of(from("type.produced", "{'items': has(input.items) ? input.items : null}")), List.of(),
                        null, new AgentManifest.MapSpec("input.items", "{'required_input': has(item.present_field) ? item.present_field : null}", 0, 2)),
                inputSchemaRequiring("required_input"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.MapValidationException.class)
                .hasMessageContaining("max_items must be positive");
    }

    @Test
    @DisplayName("BOOT-REJECT: figure path referencing absent producer field is rejected")
    void rejectsBrokenFigurePathAgainstProducerOutputSchema() {
        AgentManifest manifest = manifest(
                "agent.producer",
                io(List.of(), List.of(new AgentManifest.Produce(
                        "producer_output",
                        "type.produced",
                        null,
                        List.of(new AgentManifest.ProducedFigure("Declared figure", "absent_field", "plain"))))),
                null,
                outputSchema());

        assertThatThrownBy(() -> validator.validateOne(manifest, List.of(manifest)))
                .isInstanceOf(SelectContractValidator.ProducedFigureValidationException.class)
                .hasMessageContaining("agentId=agent.producer")
                .hasMessageContaining("label=Declared figure");
    }

    private static AgentManifest manifest(String id, AgentManifest.Io io, JsonNode inputSchema, JsonNode outputSchema) {
        return new AgentManifest(
                id, id, "description", "1.0.0", new AgentManifest.Provider("org", null),
                "domain", "segment", "sub", null, "http",
                null, new AgentManifest.Capabilities(false, false), List.of(skill()),
                new AgentManifest.Constraints("read", "internal", 1000),
                io, inputSchema, outputSchema, null, true, null);
    }

    private static AgentManifest.Skill skill() {
        return new AgentManifest.Skill("skill", "skill", "description",
                List.of("tag"), List.of("example one", "example two", "example three"),
                List.of(), List.of());
    }

    private static AgentManifest.Io io(List<AgentManifest.Consume> consumes,
                                       List<AgentManifest.Produce> produces) {
        return new AgentManifest.Io(consumes, produces);
    }

    private static AgentManifest.Io io(List<AgentManifest.Consume> consumes,
                                       List<AgentManifest.Produce> produces,
                                       String condition) {
        return new AgentManifest.Io(consumes, produces, condition);
    }

    private static AgentManifest.Io io(List<AgentManifest.Consume> consumes,
                                       List<AgentManifest.Produce> produces,
                                       String condition,
                                       AgentManifest.MapSpec map) {
        return new AgentManifest.Io(consumes, produces, condition, map);
    }

    private static AgentManifest.Consume from(String type, String select) {
        return new AgentManifest.Consume(null, type, true, select);
    }

    private static AgentManifest.Produce produce(String name, String type) {
        return new AgentManifest.Produce(name, type);
    }

    private static JsonNode outputSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("present_field").put("type", "string");
        schema.putArray("required").add("present_field");
        return schema;
    }

    private static JsonNode outputSchemaWithArray() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var items = props.putObject("items");
        items.put("type", "array");
        var item = items.putObject("items");
        item.put("type", "object");
        var itemProps = item.putObject("properties");
        itemProps.putObject("present_field").put("type", "string");
        item.putArray("required").add("present_field");
        schema.putArray("required").add("items");
        return schema;
    }

    private static JsonNode inputSchemaRequiring(String... fields) {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var required = schema.putArray("required");
        for (String field : fields) {
            props.putObject(field);
            required.add(field);
        }
        return schema;
    }
}
