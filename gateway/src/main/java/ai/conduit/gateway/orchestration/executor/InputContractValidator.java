package ai.conduit.gateway.orchestration.executor;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural validation of a bound wire input against a consumer's INTROSPECTED input schema
 * (JSON Schema shape, as derived by {@code AgentIntrospector} — never manifest domain
 * vocabulary). Used by {@link Blackboard} to decide, before dispatch, whether a composed input
 * can safely reach the agent — fail-safe, not fail-open: a mismatch skips the node rather than
 * risking a 422 or (worse) a silently wrong number.
 *
 * <h2>"Effectively required" — why not just {@code schema.required}?</h2>
 * Many real-world schemas (e.g. a Pydantic model where every field carries a default) declare
 * an EMPTY {@code required} array even though a field is, in practice, load-bearing (nothing
 * useful can be computed without it). Trusting an empty/absent {@code required} array verbatim
 * would make validation a no-op exactly when it matters most. So: when the schema's own
 * {@code required} array is non-empty, we trust it completely (no guessing beyond the author's
 * explicit contract — this keeps well-declared schemas, e.g. typical MCP tool schemas, from
 * being over-constrained). Only when it is EMPTY/ABSENT do we fall back to a structural
 * heuristic: a property whose type does not admit {@code null} (no {@code anyOf}/{@code oneOf}
 * null branch, no {@code "type":["x","null"]}) is treated as effectively required. This is pure
 * JSON Schema shape reasoning — no domain literals, no agent-specific logic.
 *
 * <p>World B: reasons only over generic JSON Schema keywords ({@code properties}, {@code
 * required}, {@code type}, {@code anyOf}, {@code oneOf}, {@code nullable}) — never a
 * domain/entity name.
 */
public final class InputContractValidator {

    private InputContractValidator() {}

    /**
     * @return the effectively-required field names in {@code input} that are missing, null, or of
     *         the wrong basic JSON type per {@code schema}. Empty means "composable" (including
     *         the common case of no schema / no declared properties — pass-open, matching today's
     *         behavior for every agent that predates this contract).
     */
    public static List<String> missingFields(JsonNode schema, JsonNode input) {
        if (schema == null || schema.isMissingNode() || schema.isNull() || !schema.path("properties").isObject()) {
            return List.of();
        }
        Set<String> required = effectiveRequiredFields(schema);
        if (required.isEmpty()) return List.of();

        JsonNode props = schema.path("properties");
        List<String> problems = new ArrayList<>();
        for (String field : required) {
            JsonNode value = (input == null) ? null : input.path(field);
            if (value == null || value.isMissingNode() || value.isNull()) {
                problems.add(field);
                continue;
            }
            String declaredType = simpleType(props.path(field));
            if (declaredType != null && !typeMatches(declaredType, value)) {
                problems.add(field + " (expected " + declaredType + ", got " + value.getNodeType() + ")");
            }
        }
        return problems;
    }

    /** Explicit {@code required} array if non-empty; otherwise every non-nullable declared property. */
    public static Set<String> effectiveRequiredFields(JsonNode schema) {
        Set<String> explicit = new LinkedHashSet<>();
        JsonNode requiredArr = schema.path("required");
        if (requiredArr.isArray()) {
            requiredArr.forEach(n -> { if (n.isTextual()) explicit.add(n.asText()); });
        }
        if (!explicit.isEmpty()) return explicit;   // trust an explicit, non-empty contract as-is

        Set<String> inferred = new LinkedHashSet<>();
        JsonNode props = schema.path("properties");
        if (props.isObject()) {
            props.fields().forEachRemaining(entry -> {
                if (!isNullable(entry.getValue())) inferred.add(entry.getKey());
            });
        }
        return inferred;
    }

    private static boolean isNullable(JsonNode propSchema) {
        if (propSchema == null || propSchema.isMissingNode()) return true;   // unknown shape → don't over-constrain
        if (propSchema.path("nullable").asBoolean(false)) return true;

        JsonNode type = propSchema.path("type");
        if (type.isArray()) {
            for (JsonNode t : type) if ("null".equals(t.asText())) return true;
        } else if ("null".equals(type.asText(null))) {
            return true;
        }

        for (String combinator : new String[]{"anyOf", "oneOf"}) {
            JsonNode alt = propSchema.path(combinator);
            if (alt.isArray()) {
                for (JsonNode branch : alt) {
                    if ("null".equals(branch.path("type").asText(null))) return true;
                }
            }
        }

        // No type info at all (e.g. `{}` = "anything goes") is too ambiguous to call required.
        return !propSchema.has("type") && !propSchema.has("anyOf")
                && !propSchema.has("oneOf") && !propSchema.has("$ref");
    }

    private static String simpleType(JsonNode propSchema) {
        if (propSchema == null) return null;
        JsonNode type = propSchema.path("type");
        if (type.isTextual()) return type.asText();
        if (type.isArray()) {
            for (JsonNode t : type) if (!"null".equals(t.asText())) return t.asText();
        }
        return null;
    }

    private static boolean typeMatches(String declaredType, JsonNode value) {
        return switch (declaredType) {
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            default -> true;
        };
    }
}
