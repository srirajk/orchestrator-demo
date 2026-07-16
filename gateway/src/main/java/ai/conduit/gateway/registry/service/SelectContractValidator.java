package ai.conduit.gateway.registry.service;

import ai.conduit.gateway.infrastructure.expression.CelEvalEngine;
import ai.conduit.gateway.infrastructure.expression.CompiledExpr;
import ai.conduit.gateway.infrastructure.expression.EvalEngine;
import ai.conduit.gateway.infrastructure.expression.ExpressionCompileException;
import ai.conduit.gateway.infrastructure.expression.ExpressionEvalException;
import ai.conduit.gateway.infrastructure.expression.RootVar;
import ai.conduit.gateway.orchestration.executor.InputContractValidator;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Boot-time validation for manifest-declared edge projections, conditions, maps, produced entities and
 * figures.
 *
 * <p>Every expression is compiled and evaluated through the {@link EvalEngine} seam (CEL). Two guards
 * come for free from the seam and replace the old regex path-scraper:
 * <ol>
 *   <li><b>Checked compile</b> — each expression is compiled against the exact {@link RootVar} its site
 *       binds ({@code input} for selects/conditions/{@code map.over}, {@code item} for
 *       {@code item_select} and per-item selectors, {@code output} for figure paths / produced-entity
 *       selects). A reference to any other root — or a legacy JMESPath-dialect string — is an undeclared
 *       reference at compile time and is rejected loudly.</li>
 *   <li><b>Strict evaluation</b> — expressions are evaluated in {@link EvalEngine.Mode#STRICT} against a
 *       schema-derived sample, so an absent field or a type error surfaces as a thrown error here rather
 *       than a silent {@code MissingNode} at runtime.</li>
 * </ol>
 *
 * <p>An additional <b>unguarded-optional check</b> evaluates each consume {@code select} in strict mode
 * against the producer's <i>required-only</i> sample: if it fails there but succeeds on the full sample,
 * the select reaches an optional field without a {@code has(...)} guard — a runtime footgun — and is
 * rejected.
 *
 * <p>The validator reasons only over schemas and manifest-declared expressions; it interprets no
 * business vocabulary.
 */
@Component
public class SelectContractValidator {

    private static final Logger log = LoggerFactory.getLogger(SelectContractValidator.class);

    private final ObjectMapper mapper;
    private final EvalEngine evalEngine;
    private final int globalMapMaxItems;
    private final int globalMapMaxConcurrency;

    public SelectContractValidator(ObjectMapper mapper) {
        this(mapper, 100, 8, new CelEvalEngine(mapper));
    }

    @Autowired
    public SelectContractValidator(
            ObjectMapper mapper,
            @Value("${conduit.orchestration.map.max-items:100}") int globalMapMaxItems,
            @Value("${conduit.orchestration.map.max-concurrency:8}") int globalMapMaxConcurrency,
            EvalEngine evalEngine) {
        this.mapper = mapper;
        this.globalMapMaxItems = globalMapMaxItems;
        this.globalMapMaxConcurrency = globalMapMaxConcurrency;
        this.evalEngine = evalEngine;
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

            JsonNode sample = sampleFromSchema(outputSchema, false);
            JsonNode projected;
            if (consume.hasSelect()) {
                CompiledExpr compiled = compileOrReject(consume.select(), RootVar.INPUT,
                        () -> new SelectValidationException(consumer.agentId(), consume.from(), consume.select(),
                                List.of("invalid select expression")));
                projected = evalOrReject(compiled, sample,
                        e -> new SelectValidationException(consumer.agentId(), consume.from(), consume.select(),
                                List.of("invalid select expression: " + e.getMessage())));
                // Unguarded-optional check: a select that fails on the required-only sample but not on
                // the full one reaches an optional field without a has() guard.
                assertOptionalFieldsGuarded(consumer, consume, compiled, outputSchema);
            } else {
                projected = sample;
            }
            projections.add(new EdgeProjection(consume, producerNameFor(producer, consume.from()), projected));
        }

        JsonNode merged = !projections.isEmpty()
                ? composeLikeBlackboard(projections)
                : sampleFromSchema(consumer.inputSchema(), false);

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
        Summary figureSummary = validateProducedFigures(consumer);
        return new Summary(validated, unvalidated).plus(entitySummary).plus(figureSummary);
    }

    /**
     * Reject a consume {@code select} that accesses an optional producer field without a {@code has(...)}
     * guard: it evaluates cleanly on the full sample but throws on the required-only sample. A guarded
     * select ({@code has(input.f) ? input.f : null}) never throws on either.
     */
    private void assertOptionalFieldsGuarded(AgentManifest consumer, AgentManifest.Consume consume,
                                             CompiledExpr compiled, JsonNode producerSchema) {
        JsonNode requiredOnly = sampleFromSchema(producerSchema, true);
        try {
            evalEngine.eval(compiled, requiredOnly, EvalEngine.Mode.STRICT);
        } catch (ExpressionEvalException e) {
            throw new SelectValidationException(consumer.agentId(), consume.from(), consume.select(),
                    List.of("optional field accessed without has() guard: " + e.getMessage()));
        }
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
            JsonNode sample = sampleFromSchema(manifest.outputSchema(), false);
            for (AgentManifest.ProducedEntity entity : entities) {
                if (entity == null || entity.select() == null || entity.select().isBlank()) {
                    throw new ProducedEntityValidationException(manifest.agentId(), produce.name(), null,
                            "select is required");
                }
                CompiledExpr compiled = compileOrReject(entity.select(), RootVar.OUTPUT,
                        () -> new ProducedEntityValidationException(manifest.agentId(), produce.name(),
                                entity.select(), "invalid expression"));
                JsonNode selected = evalOrReject(compiled, sample,
                        e -> new ProducedEntityValidationException(manifest.agentId(), produce.name(),
                                entity.select(), "invalid expression: " + e.getMessage()));
                if (!isStringOrStringArray(selected)) {
                    throw new ProducedEntityValidationException(manifest.agentId(), produce.name(), entity.select(),
                            "must evaluate to a string or array of strings");
                }
                validated++;
            }
        }
        return new Summary(validated, unvalidated);
    }

    private Summary validateProducedFigures(AgentManifest manifest) {
        AgentManifest.Io io = manifest == null ? null : manifest.io();
        List<AgentManifest.Produce> produces =
                (io == null || io.produces() == null) ? List.of() : io.produces();
        int validated = 0;
        int unvalidated = 0;
        for (AgentManifest.Produce produce : produces) {
            List<AgentManifest.ProducedFigure> figures =
                    (produce == null || produce.figures() == null) ? List.of() : produce.figures();
            if (figures.isEmpty()) continue;
            if (!hasUsableSchema(manifest.outputSchema())) {
                log.warn("figure path validation: agentId={} produce={} UNVALIDATED (no output schema)",
                        manifest.agentId(), produce.name());
                unvalidated += figures.size();
                continue;
            }
            JsonNode sample = sampleFromSchema(manifest.outputSchema(), false);
            for (AgentManifest.ProducedFigure figure : figures) {
                if (figure == null || figure.label() == null || figure.label().isBlank()) {
                    throw new ProducedFigureValidationException(manifest.agentId(), produce.name(), null,
                            "label is required");
                }
                if (figure.path() == null || figure.path().isBlank()) {
                    throw new ProducedFigureValidationException(manifest.agentId(), produce.name(), figure.label(),
                            "path is required");
                }
                CompiledExpr compiled = compileOrReject(figure.path(), RootVar.OUTPUT,
                        () -> new ProducedFigureValidationException(manifest.agentId(), produce.name(),
                                figure.label(), "invalid expression"));
                JsonNode selected = evalOrReject(compiled, sample,
                        e -> new ProducedFigureValidationException(manifest.agentId(), produce.name(),
                                figure.label(), "invalid expression: " + e.getMessage()));
                if (selected == null || selected.isMissingNode() || selected.isNull()
                        || !(selected.isNumber() || selected.isTextual() || selected.isBoolean())) {
                    throw new ProducedFigureValidationException(manifest.agentId(), produce.name(), figure.label(),
                            "must evaluate to a scalar value");
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
        CompiledExpr compiled = compileOrReject(expression, RootVar.INPUT,
                () -> new ConditionValidationException(consumer.agentId(), expression, "invalid expression"));
        JsonNode result = evalOrReject(compiled, mergedInput,
                e -> new ConditionValidationException(consumer.agentId(), expression,
                        "references absent field or invalid expression: " + e.getMessage()));
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

        CompiledExpr over = compileOrReject(map.over(), RootVar.INPUT,
                () -> new MapValidationException(consumer.agentId(), map, "invalid map.over expression"));
        JsonNode collection = evalOrReject(over, mergedInput,
                e -> new MapValidationException(consumer.agentId(), map,
                        "map.over references absent field or is invalid: " + e.getMessage()));
        if (collection == null || !collection.isArray()) {
            throw new MapValidationException(consumer.agentId(), map,
                    "map.over must evaluate to an array");
        }

        JsonNode itemSample = collection.isEmpty() ? mapper.createObjectNode() : collection.get(0);
        JsonNode projected;
        if (map.hasItemSelect()) {
            CompiledExpr itemSelect = compileOrReject(map.itemSelect(), RootVar.ITEM,
                    () -> new MapValidationException(consumer.agentId(), map, "invalid item_select expression"));
            projected = evalOrReject(itemSelect, itemSample,
                    e -> new MapValidationException(consumer.agentId(), map,
                            "invalid item_select expression: " + e.getMessage()));
        } else {
            projected = itemSample;
        }

        List<String> missing = InputContractValidator.missingFields(consumer.inputSchema(), projected);
        if (!missing.isEmpty()) {
            throw new MapValidationException(consumer.agentId(), map,
                    "item_select does not satisfy input schema: " + missing);
        }
    }

    // ── seam helpers ─────────────────────────────────────────────────────────────────────────────

    private CompiledExpr compileOrReject(String source, RootVar var,
                                         java.util.function.Supplier<RuntimeException> onError) {
        try {
            return evalEngine.compile(source, var);
        } catch (ExpressionCompileException e) {
            throw onError.get();
        }
    }

    private JsonNode evalOrReject(CompiledExpr compiled, JsonNode sample,
                                  java.util.function.Function<RuntimeException, RuntimeException> onError) {
        try {
            return evalEngine.eval(compiled, sample, EvalEngine.Mode.STRICT);
        } catch (ExpressionEvalException e) {
            throw onError.apply(e);
        }
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

    /**
     * A schema-derived sample. {@code requiredOnly} keeps only effectively-required properties at each
     * object level — used to prove a select is guarded against absent optional fields.
     */
    JsonNode sampleFromSchema(JsonNode schema) {
        return sampleFromSchema(schema, false);
    }

    JsonNode sampleFromSchema(JsonNode schema, boolean requiredOnly) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return mapper.nullNode();

        JsonNode selected = nonNullBranch(schema);
        if (selected != schema) return sampleFromSchema(selected, requiredOnly);

        String type = schemaType(schema);
        if ("object".equals(type) || schema.path("properties").isObject()) {
            ObjectNode out = mapper.createObjectNode();
            JsonNode props = schema.path("properties");
            if (props.isObject()) {
                Set<String> required = requiredOnly
                        ? InputContractValidator.effectiveRequiredFields(schema)
                        : null;
                props.fields().forEachRemaining(entry -> {
                    if (required == null || required.contains(entry.getKey())) {
                        out.set(entry.getKey(), sampleFromSchema(entry.getValue(), requiredOnly));
                    }
                });
            }
            return out;
        }
        if ("array".equals(type)) {
            ArrayNode out = mapper.createArrayNode();
            out.add(sampleFromSchema(schema.path("items"), requiredOnly));
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

    public static final class ProducedFigureValidationException extends RuntimeException {
        public ProducedFigureValidationException(String agentId, String produce, String label, String reason) {
            super("produced figure validation failed: agentId=" + agentId
                    + " produce=" + produce
                    + " label=" + label
                    + " reason=" + reason);
        }
    }
}
