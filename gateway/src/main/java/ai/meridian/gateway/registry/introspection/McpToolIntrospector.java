package ai.meridian.gateway.registry.introspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches tool input schema from an MCP server via SSE + JSON-RPC.
 *
 * Protocol: POST a tools/list JSON-RPC request to the server's /messages endpoint,
 * read the response from the SSE stream, extract the named tool's inputSchema.
 *
 * The MCP SSE protocol requires:
 *   1. GET /sse to get a session endpoint URL (first event)
 *   2. POST to that session URL with the JSON-RPC payload
 *   3. Read the response event from the SSE stream
 *
 * For Phase 3 we use a simpler approach: directly POST to /messages (FastMCP supports this),
 * falling back to SSE connection if needed.
 */
@Component
public class McpToolIntrospector {

    private static final Logger log = LoggerFactory.getLogger(McpToolIntrospector.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public McpToolIntrospector(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)  // FastMCP/uvicorn returns 421 on HTTP/2
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * Fetch the JSON Schema inputSchema for a named MCP tool.
     * Falls back to a generic {relationship_id: string} schema if introspection fails.
     */
    public JsonNode getToolInputSchema(String serverUrl, String toolName) {
        try {
            // MCP SSE protocol: connect to /sse first to get session endpoint
            String baseUrl = serverUrl.replace("/sse", "");
            JsonNode tools = fetchToolsList(baseUrl);

            if (tools != null && tools.isArray()) {
                for (JsonNode tool : tools) {
                    if (toolName.equals(tool.path("name").asText())) {
                        JsonNode schema = tool.path("inputSchema");
                        if (!schema.isMissingNode()) {
                            log.debug("Derived input schema for MCP tool '{}': {}", toolName, schema);
                            return schema;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MCP introspection failed for tool '{}' at {}: {} — using fallback schema",
                    toolName, serverUrl, e.getMessage());
        }

        return fallbackSchema(toolName);
    }

    private JsonNode fetchToolsList(String baseUrl) throws Exception {
        String id = UUID.randomUUID().toString();
        String payload = mapper.writeValueAsString(mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/list")
                .set("params", mapper.createObjectNode()));

        // First try direct HTTP (some MCP servers expose a /messages endpoint)
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(TIMEOUT)
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            JsonNode body = mapper.readTree(resp.body());
            return body.path("result").path("tools");
        }

        // Fallback: initiate SSE session then POST
        return fetchViaSSE(baseUrl, payload);
    }

    private JsonNode fetchViaSSE(String baseUrl, String payload) throws Exception {
        // GET /sse to receive the session endpoint
        AtomicReference<String> sessionUrl = new AtomicReference<>();

        HttpRequest sseReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sse"))
                .header("Accept", "text/event-stream")
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        // Read just the first event (endpoint announcement)
        HttpResponse<java.io.InputStream> sseResp = httpClient.send(
                sseReq, HttpResponse.BodyHandlers.ofInputStream());

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(sseResp.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (data.startsWith("/")) {
                        sessionUrl.set(baseUrl + data);
                        break;
                    } else if (data.startsWith("http")) {
                        sessionUrl.set(data);
                        break;
                    }
                }
            }
        }

        if (sessionUrl.get() == null) {
            throw new RuntimeException("No session URL received from MCP SSE endpoint");
        }

        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(sessionUrl.get()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(TIMEOUT)
                .build();

        HttpResponse<String> postResp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
        JsonNode body = mapper.readTree(postResp.body());
        return body.path("result").path("tools");
    }

    private JsonNode fallbackSchema(String toolName) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // nav tool uses fund_id; all others use relationship_id
        if ("get_nav".equals(toolName)) {
            props.putObject("fund_id").put("type", "string");
            schema.putArray("required").add("fund_id");
        } else {
            props.putObject("relationship_id").put("type", "string");
            schema.putArray("required").add("relationship_id");
        }
        return schema;
    }
}
