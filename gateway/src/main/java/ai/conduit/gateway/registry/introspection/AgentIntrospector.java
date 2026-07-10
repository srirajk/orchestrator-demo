package ai.conduit.gateway.registry.introspection;

import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives input/output schemas and resolved connection from agent specs.
 *
 * HTTP: fetches OpenAPI spec → finds operation_id → extracts query params as input schema
 * MCP:  connects to MCP server → tools/list → finds tool → extracts inputSchema
 */
@Component
public class AgentIntrospector {

    private static final Logger log = LoggerFactory.getLogger(AgentIntrospector.class);

    private final ObjectMapper mapper;
    private final McpToolIntrospector mcpIntrospector;

    public AgentIntrospector(ObjectMapper mapper, McpToolIntrospector mcpIntrospector) {
        this.mapper          = mapper;
        this.mcpIntrospector = mcpIntrospector;
    }

    public AgentManifest introspect(AgentManifest submission) {
        return switch (submission.protocol()) {
            case "http" -> introspectHttp(submission);
            case "mcp"  -> introspectMcp(submission);
            default -> throw new IllegalArgumentException(
                    "Unsupported protocol: " + submission.protocol());
        };
    }

    // ── HTTP / OpenAPI ────────────────────────────────────────────────────────

    private AgentManifest introspectHttp(AgentManifest submission) {
        String openapiUrl = submission.connection().openapiUrl();
        String operationId = submission.connection().operationId();

        log.debug("Introspecting HTTP agent '{}' from {}", submission.agentId(), openapiUrl);

        OpenAPI openApi = new OpenAPIV3Parser().read(openapiUrl);
        if (openApi == null) {
            throw new RuntimeException("Failed to fetch/parse OpenAPI spec from: " + openapiUrl);
        }

        // Find the operation by id
        OperationMatch match = findOperation(openApi, operationId);
        if (match == null) {
            throw new RuntimeException(
                    "Operation '" + operationId + "' not found in spec at " + openapiUrl);
        }

        JsonNode inputSchema  = buildInputSchema(match, openapiUrl);
        JsonNode outputSchema = chooseOutputSchema(
                buildOutputSchemaFromResponse(match, openapiUrl),
                submission.outputSchema());

        // Resolved connection: base URL from servers + method + path
        String baseUrl = resolveBaseUrl(openApi, openapiUrl);
        AgentManifest.ResolvedConnection resolved = new AgentManifest.ResolvedConnection(
                baseUrl, match.method().toUpperCase(), match.path());

        return withDerived(submission, inputSchema, outputSchema, resolved);
    }

    private record OperationMatch(String path, String method, Operation operation) {}

    private OperationMatch findOperation(OpenAPI api, String operationId) {
        if (api.getPaths() == null) return null;
        for (var pathEntry : api.getPaths().entrySet()) {
            var pathItem = pathEntry.getValue();
            Map<String, Operation> ops = new LinkedHashMap<>();
            if (pathItem.getGet()    != null) ops.put("GET",    pathItem.getGet());
            if (pathItem.getPost()   != null) ops.put("POST",   pathItem.getPost());
            if (pathItem.getPut()    != null) ops.put("PUT",    pathItem.getPut());
            if (pathItem.getDelete() != null) ops.put("DELETE", pathItem.getDelete());
            for (var opEntry : ops.entrySet()) {
                Operation op = opEntry.getValue();
                if (operationId.equals(op.getOperationId())) {
                    return new OperationMatch(pathEntry.getKey(), opEntry.getKey(), op);
                }
            }
        }
        return null;
    }

    /**
     * Input schema = query/path parameters (unchanged) PLUS, for a body-taking operation (POST/PUT
     * with an {@code application/json} requestBody — the common analytics fan-in shape, e.g.
     * {@code post_concentration}), the body's own JSON Schema properties/required. Without this, a
     * body-only operation would introspect to an empty {@code {type:object,properties:{}}} — a schema
     * with no teeth, unable to catch a mis-composed input before dispatch (Blackboard's pre-dispatch
     * gate). Swagger-parser's Java {@code Schema} model is fiddly for OpenAPI-3.1-style
     * {@code anyOf}-null (Pydantic's optional-field idiom); fetching the raw spec JSON and resolving
     * {@code $ref} ourselves keeps this exact and simple.
     */
    private JsonNode buildInputSchema(OperationMatch match, String openapiUrl) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        var required = schema.putArray("required");

        List<Parameter> params = match.operation().getParameters();
        if (params != null) {
            for (Parameter p : params) {
                if ("query".equals(p.getIn()) || "path".equals(p.getIn())) {
                    ObjectNode propSchema = props.putObject(p.getName());
                    propSchema.put("type", "string");
                    if (p.getDescription() != null) propSchema.put("description", p.getDescription());
                    if (Boolean.TRUE.equals(p.getRequired())) required.add(p.getName());
                }
            }
        }

        mergeRequestBodySchema(props, required, match, openapiUrl);
        return schema;
    }

    private void mergeRequestBodySchema(ObjectNode props, com.fasterxml.jackson.databind.node.ArrayNode required,
                                        OperationMatch match, String openapiUrl) {
        if (match.operation().getRequestBody() == null) return;
        try {
            JsonNode root = mapper.readTree(URI.create(openapiUrl).toURL());
            JsonNode bodySchema = root.path("paths").path(match.path())
                    .path(match.method().toLowerCase()).path("requestBody")
                    .path("content").path("application/json").path("schema");
            bodySchema = resolveRef(root, bodySchema);
            if (!bodySchema.isObject()) return;

            JsonNode bodyProps = bodySchema.path("properties");
            if (bodyProps.isObject()) {
                bodyProps.fields().forEachRemaining(e -> props.set(e.getKey(), e.getValue()));
            }
            JsonNode bodyRequired = bodySchema.path("required");
            if (bodyRequired.isArray()) {
                Set<String> already = new java.util.HashSet<>();
                required.forEach(n -> already.add(n.asText()));
                for (JsonNode r : bodyRequired) {
                    if (r.isTextual() && already.add(r.asText())) required.add(r.asText());
                }
            }
        } catch (Exception e) {
            log.warn("Could not derive requestBody schema for '{}' from {}: {}",
                    match.operation().getOperationId(), openapiUrl, e.getMessage());
        }
    }

    /** Resolves a single {@code {"$ref": "#/components/schemas/X"}} indirection against {@code root}. */
    private JsonNode resolveRef(JsonNode root, JsonNode node) {
        if (node == null || !node.isObject() || !node.has("$ref")) return node;
        String ref = node.get("$ref").asText();
        if (!ref.startsWith("#/")) return node;
        JsonNode target = root;
        for (String segment : ref.substring(2).split("/")) {
            target = target.path(segment);
        }
        return target;
    }

    private JsonNode buildOutputSchemaFromResponse(OperationMatch match, String openapiUrl) {
        try {
            JsonNode root = mapper.readTree(URI.create(openapiUrl).toURL());
            JsonNode responses = root.path("paths").path(match.path())
                    .path(match.method().toLowerCase()).path("responses");
            if (!responses.isObject()) return null;

            JsonNode response = null;
            var fields = responses.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().startsWith("2")) {
                    response = entry.getValue();
                    break;
                }
            }
            if (response == null) return null;

            JsonNode schema = response.path("content").path("application/json").path("schema");
            if (schema.isMissingNode()) return null;
            JsonNode resolved = resolveRefs(root, schema);
            log.debug("Derived output schema for HTTP operation '{}': {}",
                    match.operation().getOperationId(), resolved);
            return resolved;
        } catch (Exception e) {
            log.warn("Could not derive response schema for '{}' from {}: {}",
                    match.operation().getOperationId(), openapiUrl, e.getMessage());
            return null;
        }
    }

    private JsonNode resolveRefs(JsonNode root, JsonNode node) {
        JsonNode direct = resolveRef(root, node);
        if (direct == null || direct.isMissingNode() || direct.isNull()) return direct;
        if (direct.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            direct.fields().forEachRemaining(entry -> out.set(entry.getKey(), resolveRefs(root, entry.getValue())));
            return out;
        }
        if (direct.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            direct.forEach(item -> out.add(resolveRefs(root, item)));
            return out;
        }
        return direct;
    }

    private String resolveBaseUrl(OpenAPI api, String openapiUrl) {
        if (api.getServers() != null && !api.getServers().isEmpty()) {
            String url = api.getServers().get(0).getUrl();
            if (url != null && !url.equals("/")) return url.replaceAll("/$", "");
        }
        // Fall back to the origin of the openapi_url
        try {
            URI uri = new URI(openapiUrl);
            return uri.getScheme() + "://" + uri.getHost() +
                   (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception e) {
            return openapiUrl;
        }
    }

    // ── MCP ──────────────────────────────────────────────────────────────────

    private AgentManifest introspectMcp(AgentManifest submission) {
        String serverUrl = submission.connection().serverUrl();
        String toolName  = submission.connection().tool();

        log.debug("Introspecting MCP agent '{}' from {}", submission.agentId(), serverUrl);

        McpToolIntrospector.ToolSchemas schemas = mcpIntrospector.getToolSchemas(
                serverUrl, toolName,
                submission.connection().isLegacySse(),
                submission.connection().protocolVersion());
        JsonNode inputSchema = schemas.inputSchema();
        JsonNode outputSchema = chooseOutputSchema(schemas.outputSchema(), submission.outputSchema());

        AgentManifest.ResolvedConnection resolved = new AgentManifest.ResolvedConnection(
                serverUrl, "MCP", "/tools/call");

        return withDerived(submission, inputSchema, outputSchema, resolved);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private AgentManifest withDerived(
            AgentManifest s,
            JsonNode inputSchema,
            JsonNode outputSchema,
            AgentManifest.ResolvedConnection resolved) {
        return new AgentManifest(
                s.agentId(), s.name(), s.description(), s.version(), s.provider(),
                s.domain(), s.audience(), s.subDomain(), s.maxResponseTokens(), s.protocol(),
                s.connection(), s.capabilities(), s.skills(),
                s.constraints(),
                s.io(),
                inputSchema, outputSchema, resolved, false, null
        );
    }

    private JsonNode chooseOutputSchema(JsonNode introspected, JsonNode submittedFallback) {
        if (hasUsableSchema(submittedFallback) && isGenericResultWrapper(introspected)) return submittedFallback;
        if (hasUsableSchema(introspected)) return introspected;
        if (hasUsableSchema(submittedFallback)) return submittedFallback;
        return introspected;
    }

    private boolean isGenericResultWrapper(JsonNode schema) {
        if (schema == null || !schema.isObject()) return false;
        JsonNode properties = schema.path("properties");
        return properties.isObject() && properties.size() == 1 && properties.has("result");
    }

    private boolean hasUsableSchema(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return false;
        if (!schema.isObject()) return true;
        JsonNode properties = schema.path("properties");
        if (properties.isObject() && properties.size() > 0) return true;
        String type = schema.path("type").asText(null);
        return type != null && !"object".equals(type);
    }
}
