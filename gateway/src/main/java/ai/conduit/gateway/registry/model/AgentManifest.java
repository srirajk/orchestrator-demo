package ai.conduit.gateway.registry.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full stored manifest = what the domain team submitted + what the gateway derived.
 *
 * Submitted fields mirror the pinned agent-manifest.schema.json.
 * Derived fields (input_schema, output_schema, resolved_connection, embedding vectors)
 * are populated by the registry pipeline after introspection. A submitted output_schema is
 * allowed only as a fallback when protocol introspection cannot derive one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentManifest(
        @JsonProperty("agent_id")       String agentId,
        String name,
        String description,
        String version,
        Provider provider,
        String domain,
        String audience,
        @JsonProperty("sub_domain")          String subDomain,
        @JsonProperty("max_response_tokens") Integer maxResponseTokens,
        String protocol,
        Connection connection,
        Capabilities capabilities,
        List<Skill> skills,
        Constraints constraints,

        // ── OPTIONAL semantic dataflow contract (multi-step / DAG orchestration) ──
        // Nullable: capabilities without an `io` block remain single-node (flat) only.
        // NOT the wire schema (still derived via introspection) — it declares, in
        // manifest-symbolic terms, what this capability consumes and produces so the
        // deterministic DagResolver can wire dependent steps. See agent-manifest.schema.json.
        @JsonProperty("io") Io io,

        // ── Derived at registration time ──────────────────────────────────
        @JsonProperty("input_schema")        JsonNode inputSchema,
        @JsonProperty("output_schema")       JsonNode outputSchema,
        @JsonProperty("resolved_connection") ResolvedConnection resolvedConnection,
        @JsonProperty("indexed")             Boolean indexed,
        @JsonProperty("registered_at")       Instant registeredAt
) {

    /**
     * Backward-compatible constructor with the pre-{@code io} arity. Existing call sites that
     * build a manifest without a dataflow contract keep compiling unchanged; {@code io} is null.
     * (Jackson deserializes via the canonical/all-component constructor, so this does not affect
     * manifest parsing.)
     */
    public AgentManifest(
            String agentId, String name, String description, String version, Provider provider,
            String domain, String audience, String subDomain, Integer maxResponseTokens, String protocol,
            Connection connection, Capabilities capabilities, List<Skill> skills, Constraints constraints,
            JsonNode inputSchema, JsonNode outputSchema, ResolvedConnection resolvedConnection,
            Boolean indexed, Instant registeredAt) {
        this(agentId, name, description, version, provider, domain, audience, subDomain,
                maxResponseTokens, protocol, connection, capabilities, skills, constraints,
                /* io */ null,
                inputSchema, outputSchema, resolvedConnection, indexed, registeredAt);
    }

    public record Provider(String organization, String contactEmail) {}

    public record Capabilities(boolean streaming, boolean pushNotifications) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Connection(
            // HTTP
            @JsonProperty("openapi_url")  String openapiUrl,
            @JsonProperty("operation_id") String operationId,
            // MCP
            @JsonProperty("server_url")   String serverUrl,
            String tool
    ) {}

    public record Skill(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples,
            List<String> inputModes,
            List<String> outputModes
    ) {}

    public record Constraints(
            @JsonProperty("access_mode")          String accessMode,
            @JsonProperty("data_classification")  String dataClassification,
            @JsonProperty("sla_timeout_ms")        int slaTimeoutMs
    ) {}

    public record ResolvedConnection(
            @JsonProperty("base_url") String baseUrl,
            String method,
            String path
    ) {}

    /**
     * Semantic dataflow contract: what a capability {@code consumes} (leaf entities or upstream
     * produced types) and {@code produces} (named, typed outputs). All strings are manifest-declared
     * symbols matched by equality; none are interpreted by the gateway.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Io(List<Consume> consumes, List<Produce> produces, String condition, MapSpec map) {
        /** Backward-compatible constructor (pre-{@code condition} arity). */
        public Io(List<Consume> consumes, List<Produce> produces) {
            this(consumes, produces, null, null);
        }

        /** Backward-compatible constructor (pre-{@code map} arity). */
        public Io(List<Consume> consumes, List<Produce> produces, String condition) {
            this(consumes, produces, condition, null);
        }

        public boolean hasCondition() {
            return condition != null && !condition.isBlank();
        }

        public boolean hasMap() {
            return map != null && map.hasOver();
        }
    }

    /** Declared dynamic map expansion over a bound consumer input. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MapSpec(
            String over,
            @JsonProperty("item_select") String itemSelect,
            @JsonProperty("max_items") Integer maxItems,
            @JsonProperty("max_concurrency") Integer maxConcurrency) {
        public boolean hasOver() {
            return over != null && !over.isBlank();
        }

        public boolean hasItemSelect() {
            return itemSelect != null && !itemSelect.isBlank();
        }
    }

    /**
     * One consumed input. Exactly one of {@code entity} / {@code from} is set (schema {@code oneOf}):
     * <ul>
     *   <li>{@code entity} — a sub-domain entity_types key satisfied by deterministic entity
     *       resolution (a leaf; creates no DAG edge).</li>
     *   <li>{@code from} — an upstream capability's produced output type; matching it creates a
     *       producer→consumer edge.</li>
     * </ul>
     * {@code required} defaults to {@code true} when absent (per schema).
     *
     * <p>{@code select} — OPTIONAL, meaningful only on a {@code from} edge: a JMESPath expression
     * that reshapes the producer's output into exactly what this consumer expects (field
     * projection, not a blob pass-through). {@code null}/absent = identity pass-through, i.e.
     * today's behavior — additive and backward-compatible. Evaluated by
     * {@link ai.conduit.gateway.orchestration.executor.Blackboard#bind}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Consume(String entity, String from, Boolean required, String select) {
        /** Backward-compatible 3-arg constructor (pre-{@code select} arity) — defaults select to null. */
        public Consume(String entity, String from, Boolean required) {
            this(entity, from, required, null);
        }
        public boolean isEntityRef()   { return entity != null && !entity.isBlank(); }
        public boolean isProducedRef() { return from   != null && !from.isBlank(); }
        public boolean isRequired()    { return required == null || required; }
        public boolean hasSelect()     { return select != null && !select.isBlank(); }
    }

    /** A named, typed output published for downstream binding via {@code from}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Produce(String name, String type) {}

    /** Convenience: all example prompts across all skills (used for embedding). */
    public List<String> allExamples() {
        if (skills == null) return List.of();
        return skills.stream()
                .flatMap(s -> s.examples() != null ? s.examples().stream() : java.util.stream.Stream.empty())
                .toList();
    }
}
