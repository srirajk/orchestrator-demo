package ai.conduit.gateway.registry.service;

import ai.conduit.gateway.orchestration.executor.InputContractValidator;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.burt.jmespath.JmesPath;
import io.burt.jmespath.jackson.JacksonRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern PATH_TOKEN =
            Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*\\b");
    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "and", "or", "not");

    private final ObjectMapper mapper;
    private final int globalMapMaxItems;
    private final int globalMapMaxConcurrency;

    public SelectContractValidator(ObjectMapper mapper) {
        this(mapper, 100, 8);
    }

    @Autowired
    public SelectContractValidator(
            ObjectMapper mapper,
            @Value("${conduit.orchestration.map.max-items:100}") int globalMapMaxItems,
            @Value("${conduit.orchestration.map.max-concurrency:8}") int globalMapMaxConcurrency) {
        this.mapper = mapper;
        this.globalMapMaxItems = globalMapMaxItems;
        this.globalMapMaxConcurrency = globalMapMaxConcurrency;
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

        JsonNode merged = !projections.isEmpty()
                ? composeLikeBlackboard(projections)
                : sampleFromSchema(consumer.inputSchema());

        if (hasCondition(consumer)) {
            if (unvalidated > 0) {
                throw new ConditionValidationException(consumer.agentId(), consumer.io().condition(),
                        "could not derive all producer output schemas");
            }
            validateCondition(consumer, merged);
        }

        if (hasMap(consumer)) {
            if (unvalidated > 0) {
                throw new MapValidationException(consumer.agentId(), consumer.io().map(),
                        "could not derive all producer output schemas");
            }
            validateMap(consumer, merged);
        }

        if (!projections.isEmpty() && unvalidated == 0) {
            if (!hasMap(consumer)) {
                List<String> missing = InputContractValidator.missingFields(consumer.inputSchema(), merged);
                if (!missing.isEmpty()) {
                    EdgeProjection edge = attributeMissing(missing.get(0), projections);
                    throw new SelectValidationException(consumer.agentId(), edge.consume().from(),
                            edge.consume().select(), missing);
                }
            }
            validated = projections.size();
        }

        Summary entitySummary = validateProducedEntities(consumer);
        return new Summary(validated, unvalidated).plus(entitySummary);
    }

    private Summary validateProducedEntities(AgentManifest manifest) {
        AgentManifest.Io io = manifest == null ? null : manifest.io();
        List<AgentManifest.Produce> produces =
                (io == null || io.produces() == null) ? List.of() : io.produces();
        int validated = 0;
        int unvalidated = 0;
        for (AgentManifest.Produce produce : produces) {
            List<AgentManifest.ProducedEntity> entities =
                    (produce == null || produce.entities() == null) ? List.of() : produce.entities();
            if (entities.isEmpty()) continue;
            if (!hasUsableSchema(manifest.outputSchema())) {
                log.warn("entity select validation: agentId={} produce={} UNVALIDATED (no output schema)",
                        manifest.agentId(), produce.name());
                unvalidated += entities.size();
                continue;
            }
            JsonNode sample = sampleFromSchema(manifest.outputSchema());
            for (AgentManifest.ProducedEntity entity : entities) {
                if (entity == null || entity.select() == null || entity.select().isBlank()) {
                    throw new ProducedEntityValidationException(manifest.agentId(), produce.name(), null,
                            "select is required");
                }
                JsonNode selected;
                try {
                    selected = JMES_PATH.compile(entity.select()).search(sample);
                } catch (Exception e) {
                    throw new ProducedEntityValidationException(manifest.agentId(), produce.name(), entity.select(),
                            "invalid expression: " + e.getMessage());
                }
                if (!isStringOrStringArray(selected)) {
                    throw new ProducedEntityValidationException(manifest.agentId(), produce.name(), entity.select(),
                            "must evaluate to a string or array of strings");
                }
                validated++;
            }
        }
        return new Summary(validated, unvalidated);
    }

    private boolean isStringOrStringArray(JsonNode selected) {
        if (selected == null || selected.isMissingNode() || selected.isNull()) return false;
        if (selected.isTextual()) return true;
        if (!selected.isArray()) return false;
        for (JsonNode item : selected) {
            if (item == null || !item.isTextual()) return false;
        }
        return true;
    }

    private boolean hasCondition(AgentManifest manifest) {
        AgentManifest.Io io = manifest == null ? null : manifest.io();
        return io != null && io.hasCondition();
    }

    private boolean hasMap(AgentManifest manifest) {
        AgentManifest.Io io = manifest == null ? null : manifest.io();
        return io != null && io.hasMap();
    }

    private void validateCondition(AgentManifest consumer, JsonNode mergedInput) {
        String expression = consumer.io().condition();
        for (String path : referencedPaths(expression)) {
            if (!pathExists(mergedInput, path)) {
                throw new ConditionValidationException(consumer.agentId(), expression,
                        "references absent field '" + path + "'");
            }
        }
        JsonNode result;
        try {
            result = JMES_PATH.compile(expression).search(mergedInput);
        } catch (Exception e) {
            throw new ConditionValidationException(consumer.agentId(), expression,
                    "invalid expression: " + e.getMessage());
        }
        if (result == null || !result.isBoolean()) {
            throw new ConditionValidationException(consumer.agentId(), expression,
                    "expression must evaluate to boolean");
        }
    }

    private void validateMap(AgentManifest consumer, JsonNode mergedInput) {
        AgentManifest.MapSpec map = consumer.io().map();
        if (map == null || !map.hasOver()) {
            throw new MapValidationException(consumer.agentId(), map, "map.over is required");
        }
        if (globalMapMaxItems <= 0 || globalMapMaxConcurrency <= 0) {
            throw new MapValidationException(consumer.agentId(), map,
                    "global map ceilings must be positive");
        }
        if (map.maxItems() != null && map.maxItems() <= 0) {
            throw new MapValidationException(consumer.agentId(), map, "max_items must be positive");
        }
        if (map.maxConcurrency() != null && map.maxConcurrency() <= 0) {
            throw new MapValidationException(consumer.agentId(), map, "max_concurrency must be positive");
        }

        for (String path : referencedPaths(map.over())) {
            if (!pathExists(mergedInput, path)) {
                throw new MapValidationException(consumer.agentId(), map,
                        "map.over references absent field '" + path + "'");
            }
        }

        JsonNode collection;
        try {
            collection = JMES_PATH.compile(map.over()).search(mergedInput);
        } catch (Exception e) {
            throw new MapValidationException(consumer.agentId(), map,
                    "invalid map.over expression: " + e.getMessage());
        }
        if (collection == null || !collection.isArray()) {
            throw new MapValidationException(consumer.agentId(), map,
                    "map.over must evaluate to an array");
        }

        JsonNode itemSample = collection.isEmpty() ? mapper.createObjectNode() : collection.get(0);
        JsonNode projected;
        if (map.hasItemSelect()) {
            try {
                projected = JMES_PATH.compile(map.itemSelect()).search(itemSample);
            } catch (Exception e) {
                throw new MapValidationException(consumer.agentId(), map,
                        "invalid item_select expression: " + e.getMessage());
            }
        } else {
            projected = itemSample;
        }

        List<String> missing = InputContractValidator.missingFields(consumer.inputSchema(), projected);
        if (!missing.isEmpty()) {
            throw new MapValidationException(consumer.agentId(), map,
                    "item_select does not satisfy input schema: " + missing);
        }
    }

    private Set<String> referencedPaths(String expression) {
        if (expression == null || expression.isBlank()) return Set.of();
        String scrubbed = expression
                .replaceAll("`[^`]*`", " ")
                .replaceAll("'[^']*'", " ")
                .replaceAll("\"[^\"]*\"", " ");
        Matcher matcher = PATH_TOKEN.matcher(scrubbed);
        Set<String> paths = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (KEYWORDS.contains(token)) continue;
            int next = matcher.end();
            while (next < scrubbed.length() && Character.isWhitespace(scrubbed.charAt(next))) next++;
            if (next < scrubbed.length() && scrubbed.charAt(next) == '(') continue;
            paths.add(token);
        }
        return paths;
    }

    private boolean pathExists(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return false;
        JsonNode current = root;
        for (String rawSegment : path.split("\\.")) {
            String segment = rawSegment;
            int arrayStart = segment.indexOf('[');
            if (arrayStart >= 0) segment = segment.substring(0, arrayStart);
            if (segment.isBlank()) continue;
            if (!current.isObject() || !current.has(segment)) return false;
            current = current.get(segment);
        }
        return true;
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

    public static final class ConditionValidationException extends RuntimeException {
        public ConditionValidationException(String agentId, String condition, String reason) {
            super("condition validation failed: agentId=" + agentId
                    + " condition=" + condition
                    + " reason=" + reason);
        }
    }

    public static final class MapValidationException extends RuntimeException {
        public MapValidationException(String agentId, AgentManifest.MapSpec map, String reason) {
            super("map validation failed: agentId=" + agentId
                    + " over=" + (map == null ? null : map.over())
                    + " item_select=" + (map == null ? null : map.itemSelect())
                    + " reason=" + reason);
        }
    }

    public static final class ProducedEntityValidationException extends RuntimeException {
        public ProducedEntityValidationException(String agentId, String produce, String select, String reason) {
            super("produced entity validation failed: agentId=" + agentId
                    + " produce=" + produce
                    + " select=" + select
                    + " reason=" + reason);
        }
    }
}
