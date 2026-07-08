package ai.conduit.gateway.registry.service;

import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectContractValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SelectContractValidator validator = new SelectContractValidator(MAPPER);

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
                io(List.of(from("type.produced", "{required_input: absent_field}")), List.of()),
                inputSchemaRequiring("required_input"),
                null);

        assertThatThrownBy(() -> validator.validateOne(consumer, List.of(producer, consumer)))
                .isInstanceOf(SelectContractValidator.SelectValidationException.class)
                .hasMessageContaining("agentId=agent.consumer")
                .hasMessageContaining("from=type.produced")
                .hasMessageContaining("select={required_input: absent_field}")
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
                io(List.of(from("type.produced", "{required_input: present_field}")), List.of()),
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
                        from("type.alpha", "{required_input: present_field}"),
                        from("type.beta", "{required_input: present_field}")
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
                io(List.of(from("type.produced", "{required_input: present_field}")), List.of()),
                inputSchemaRequiring("required_input"),
                null);

        SelectContractValidator.Summary summary =
                validator.validateOne(consumer, List.of(producer, consumer));

        assertThat(summary.validated()).isZero();
        assertThat(summary.unvalidated()).isEqualTo(1);
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
