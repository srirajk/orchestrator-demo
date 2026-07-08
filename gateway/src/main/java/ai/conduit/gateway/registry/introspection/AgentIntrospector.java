package ai.conduit.gateway.registry.introspection;

import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        JsonNode inputSchema  = buildInputSchemaFromParams(match.operation());
        JsonNode outputSchema = buildOutputSchemaFromResponse(match.operation());

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

    private JsonNode buildInputSchemaFromParams(Operation op) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        var required = schema.putArray("required");

        List<Parameter> params = op.getParameters();
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
        return schema;
    }

    private JsonNode buildOutputSchemaFromResponse(Operation op) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        // Minimal — full output schema parsing from OpenAPI responses is left as a seam
        if (op.getResponses() != null && op.getResponses().get("200") != null) {
            schema.put("description", "200 OK response from agent");
        }
        return schema;
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

        JsonNode inputSchema = mcpIntrospector.getToolInputSchema(serverUrl, toolName);
        JsonNode outputSchema = mapper.createObjectNode(); // MCP output is untyped; best-effort

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
}
