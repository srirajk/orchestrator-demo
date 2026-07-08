package ai.conduit.gateway.registry.service;

import ai.conduit.gateway.orchestration.executor.InputContractValidator;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.burt.jmespath.JmesPath;
import io.burt.jmespath.jackson.JacksonRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Boot-time validation for manifest-declared edge projections.
 *
 * <p>The validator reasons only over schemas and manifest-declared symbols: produced type,
 * JMESPath select, JSON Schema keywords, and the consumer's introspected input schema. It does
 * not interpret any business vocabulary.
 */
@Component
public class SelectContractValidator {

    private static final Logger log = LoggerFactory.getLogger(SelectContractValidator.class);
    private static final JmesPath<JsonNode> JMES_PATH = new JacksonRuntime();

    private final ObjectMapper mapper;

    public SelectContractValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Summary validateOne(AgentManifest consumer, List<AgentManifest> manifests) {
        int validated = 0;
        int unvalidated = 0;
        AgentManifest.Io io = consumer.io();
        List<AgentManifest.Consume> consumes =
                (io == null || io.consumes() == null) ? List.of() : io.consumes();

        List<EdgeProjection> projections = new ArrayList<>();
        for (AgentManifest.Consume consume : consumes) {
            if (consume == null || !consume.isProducedRef()) continue;

            List<AgentManifest> producers = producersFor(manifests, consume.from());
            if (producers.size() != 1) {
                log.warn("select validation: agentId={} from={} select={} UNVALIDATED (producer count={})",
                        consumer.agentId(), consume.from(), consume.select(), producers.size());
                unvalidated++;
                continue;
            }

            AgentManifest producer = producers.get(0);
            JsonNode outputSchema = producer.outputSchema();
            if (!hasUsableSchema(outputSchema)) {
                log.warn("select validation: agentId={} from={} select={} UNVALIDATED (no output schema from producer={})",
                        consumer.agentId(), consume.from(), consume.select(), producer.agentId());
                unvalidated++;
                continue;
            }

            JsonNode sample = sampleFromSchema(outputSchema);
            JsonNode projected;
            try {
                projected = consume.hasSelect() ? JMES_PATH.compile(consume.select()).search(sample) : sample;
            } catch (Exception e) {
                throw new SelectValidationException(consumer.agentId(), consume.from(), consume.select(),
                        List.of("invalid select expression: " + e.getMessage()));
            }
            projections.add(new EdgeProjection(consume, producerNameFor(producer, consume.from()), projected));
        }

        if (!projections.isEmpty() && unvalidated == 0) {
            JsonNode merged = composeLikeBlackboard(projections);
            List<String> missing = InputContractValidator.missingFields(consumer.inputSchema(), merged);
            if (!missing.isEmpty()) {
                EdgeProjection edge = attributeMissing(missing.get(0), projections);
                throw new SelectValidationException(consumer.agentId(), edge.consume().from(),
                        edge.consume().select(), missing);
            }
            validated = projections.size();
        }

        return new Summary(validated, unvalidated);
    }

    private JsonNode composeLikeBlackboard(List<EdgeProjection> projections) {
        if (projections.size() == 1) return projections.get(0).projected();
        ObjectNode merged = mapper.createObjectNode();
        for (EdgeProjection projection : projections) {
            merged.set(projection.producerName(), projection.projected());
        }
        return merged;
    }

    private EdgeProjection attributeMissing(String missingField, List<EdgeProjection> projections) {
        String field = missingField;
        int detailStart = field.indexOf(' ');
        if (detailStart > 0) field = field.substring(0, detailStart);

        for (EdgeProjection projection : projections) {
            if (projection.producerName().equals(field)) return projection;
            JsonNode value = projection.projected();
            if (value != null && value.isObject() && value.has(field)) return projection;
        }
        return projections.get(0);
    }

    private String producerNameFor(AgentManifest producer, String producedType) {
        AgentManifest.Io io = producer.io();
        List<AgentManifest.Produce> produces =
                (io == null || io.produces() == null) ? List.of() : io.produces();
        for (AgentManifest.Produce produce : produces) {
            if (produce != null && producedType.equals(produce.type()) && produce.name() != null) {
                return produce.name();
            }
        }
        return producedType;
    }

    private List<AgentManifest> producersFor(List<AgentManifest> manifests, String producedType) {
        if (manifests == null || producedType == null) return List.of();
        List<AgentManifest> matches = new ArrayList<>();
        for (AgentManifest manifest : manifests) {
            AgentManifest.Io io = manifest.io();
            List<AgentManifest.Produce> produces =
                    (io == null || io.produces() == null) ? List.of() : io.produces();
            for (AgentManifest.Produce produce : produces) {
                if (produce != null && producedType.equals(produce.type())) {
                    matches.add(manifest);
                    break;
                }
            }
        }
        return matches;
    }

    JsonNode sampleFromSchema(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return mapper.nullNode();

        JsonNode selected = nonNullBranch(schema);
        if (selected != schema) return sampleFromSchema(selected);

        String type = schemaType(schema);
        if ("object".equals(type) || schema.path("properties").isObject()) {
            ObjectNode out = mapper.createObjectNode();
            JsonNode props = schema.path("properties");
            if (props.isObject()) {
                props.fields().forEachRemaining(entry -> out.set(entry.getKey(), sampleFromSchema(entry.getValue())));
            }
            return out;
        }
        if ("array".equals(type)) {
            ArrayNode out = mapper.createArrayNode();
            out.add(sampleFromSchema(schema.path("items")));
            return out;
        }
        if ("number".equals(type) || "integer".equals(type)) return mapper.getNodeFactory().numberNode(1);
        if ("boolean".equals(type)) return mapper.getNodeFactory().booleanNode(true);
        if ("null".equals(type)) return mapper.nullNode();
        return mapper.getNodeFactory().textNode("x");
    }

    private JsonNode nonNullBranch(JsonNode schema) {
        for (String key : new String[]{"anyOf", "oneOf"}) {
            JsonNode branches = schema.path(key);
            if (!branches.isArray()) continue;
            for (JsonNode branch : branches) {
                if (!"null".equals(schemaType(branch))) return branch;
            }
        }
        return schema;
    }

    private String schemaType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) return type.asText();
        if (type.isArray()) {
            for (JsonNode item : type) {
                String value = item.asText();
                if (!"null".equals(value)) return value;
            }
            return "null";
        }
        return null;
    }

    private boolean hasUsableSchema(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return false;
        if (!schema.isObject()) return true;
        JsonNode properties = schema.path("properties");
        if (properties.isObject() && properties.size() > 0) return true;
        String type = schema.path("type").asText(null);
        return type != null && !"object".equals(type);
    }

    public record Summary(int validated, int unvalidated) {
        public Summary plus(Summary other) {
            if (other == null) return this;
            return new Summary(validated + other.validated, unvalidated + other.unvalidated);
        }
    }

    private record EdgeProjection(AgentManifest.Consume consume, String producerName, JsonNode projected) {}

    public static final class SelectValidationException extends RuntimeException {
        public SelectValidationException(String agentId, String from, String select, List<String> missing) {
            super("select validation failed: agentId=" + agentId
                    + " from=" + from
                    + " select=" + select
                    + " missing=" + missing);
        }
    }
}
