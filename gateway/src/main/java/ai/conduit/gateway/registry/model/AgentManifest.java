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
 * are populated by the registry pipeline after introspection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentManifest(
        @JsonProperty("agent_id")       String agentId,
        String name,
        String description,
        String version,
        Provider provider,
        String domain,
        @JsonProperty("sub_domain")          String subDomain,
        @JsonProperty("max_response_tokens") Integer maxResponseTokens,
        String protocol,
        Connection connection,
        Capabilities capabilities,
        List<Skill> skills,
        Constraints constraints,

        // ── Derived at registration time ──────────────────────────────────
        @JsonProperty("input_schema")        JsonNode inputSchema,
        @JsonProperty("output_schema")       JsonNode outputSchema,
        @JsonProperty("resolved_connection") ResolvedConnection resolvedConnection,
        @JsonProperty("indexed")             Boolean indexed,
        @JsonProperty("registered_at")       Instant registeredAt
) {

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
            @JsonProperty("is_mutating")          boolean isMutating,
            @JsonProperty("data_classification")  String dataClassification,
            @JsonProperty("sla_timeout_ms")        int slaTimeoutMs
    ) {}

    public record ResolvedConnection(
            @JsonProperty("base_url") String baseUrl,
            String method,
            String path
    ) {}

    /** Convenience: all example prompts across all skills (used for embedding). */
    public List<String> allExamples() {
        if (skills == null) return List.of();
        return skills.stream()
                .flatMap(s -> s.examples() != null ? s.examples().stream() : java.util.stream.Stream.empty())
                .toList();
    }
}
