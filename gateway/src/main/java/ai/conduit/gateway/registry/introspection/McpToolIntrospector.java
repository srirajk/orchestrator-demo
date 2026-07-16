package ai.conduit.gateway.registry.introspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches tool input/output schemas from an MCP server via JSON-RPC {@code tools/list}.
 *
 * <p>Mirrors {@link ai.conduit.gateway.adapter.mcp.McpAdapter}'s transport handling: Streamable
 * HTTP by default (single {@code /mcp} endpoint: initialize → tools/list, JSON-or-SSE body),
 * falling back to the legacy HTTP+SSE two-channel handshake only for agents that declare
 * {@code transport: "sse"}. The MCP protocol version is config/manifest-driven, never a Java
 * literal.
 */
@Component
public class McpToolIntrospector {

    private static final Logger log = LoggerFactory.getLogger(McpToolIntrospector.class);
    private static final int TIMEOUT_MS = 15_000;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String protocolVersion;        // streamable HTTP
    private final String legacyProtocolVersion;  // legacy HTTP+SSE

    public McpToolIntrospector(
            ObjectMapper mapper,
            @Value("${conduit.mcp.protocol-version}") String protocolVersion,
            @Value("${conduit.mcp.legacy-protocol-version}") String legacyProtocolVersion) {
        this.mapper = mapper;
        this.protocolVersion = protocolVersion;
        this.legacyProtocolVersion = legacyProtocolVersion;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record ToolSchemas(JsonNode inputSchema, JsonNode outputSchema) {}

    /**
     * Fetch JSON Schema contracts for a named MCP tool.
     * Falls back only for input schema so older tools keep registering; output schema is returned
     * as null when the tool does not declare one, allowing boot select validation to report the
     * unvalidated edge explicitly.
     *
     * @param legacySse      when true, use the legacy HTTP+SSE handshake; otherwise Streamable HTTP.
     * @param protoOverride  per-agent {@code connection.protocol_version} override (nullable → config).
     */
    public ToolSchemas getToolSchemas(String serverUrl, String toolName,
                                      boolean legacySse, String protoOverride) {
        try {
            String version = resolveProtocolVersion(protoOverride, legacySse);
            JsonNode tools;
            if (legacySse) {
                String baseUrl = serverUrl.contains("/sse")
                        ? serverUrl.substring(0, serverUrl.lastIndexOf("/sse")) : serverUrl;
                tools = fetchToolsListSse(baseUrl, version);
            } else {
                tools = fetchToolsListStreamable(serverUrl, version);
            }

            if (tools != null && tools.isArray()) {
                for (JsonNode tool : tools) {
                    if (toolName.equals(tool.path("name").asText())) {
                        JsonNode schema = tool.path("inputSchema");
                        JsonNode outputSchema = tool.path("outputSchema");
                        if (!schema.isMissingNode()) {
                            log.debug("Derived input schema for MCP tool '{}': {}", toolName, schema);
                            if (!outputSchema.isMissingNode()) {
                                log.debug("Derived output schema for MCP tool '{}': {}", toolName, outputSchema);
                            }
                            return new ToolSchemas(schema, outputSchema.isMissingNode() ? null : outputSchema);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MCP introspection failed for tool '{}' at {}: {} — using fallback schema",
                    toolName, serverUrl, e.getMessage());
        }

        return new ToolSchemas(fallbackSchema(toolName), null);
    }

    /**
     * Fetch the JSON Schema inputSchema for a named MCP tool over Streamable HTTP (default
     * transport). Falls back to a generic schema if introspection fails.
     */
    public JsonNode getToolInputSchema(String serverUrl, String toolName) {
        return getToolSchemas(serverUrl, toolName, false, null).inputSchema();
    }

    // ── Streamable HTTP (default) ──────────────────────────────────────────────

    /**
     * Runs the Streamable HTTP handshake against the single endpoint: initialize (capture
     * {@code Mcp-Session-Id} + negotiated version) → tools/list (echo them). The response body of
     * each POST may be plain JSON or an SSE-upgraded stream.
     */
    private JsonNode fetchToolsListStreamable(String serverUrl, String version) throws Exception {
        String initBody = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", version,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "conduit-gateway-introspector", "version", "1.0")
                )
        ));
        HttpResponse<String> initResp = postStreamable(serverUrl, initBody, null, null);
        if (initResp.statusCode() >= 400) {
            throw new RuntimeException("MCP initialize on " + serverUrl + " returned HTTP "
                    + initResp.statusCode() + ": " + initResp.body());
        }
        String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElse(null);
        JsonNode initResult = parseStreamableBody(initResp);
        String negotiated = initResult.path("result").path("protocolVersion").asText(version);
        log.debug("McpToolIntrospector streamable init: session={}, protocolVersion={}",
                sessionId, negotiated);

        String listBody = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of()
        ));
        HttpResponse<String> listResp = postStreamable(serverUrl, listBody, sessionId, negotiated);
        if (listResp.statusCode() >= 400) {
            throw new RuntimeException("MCP tools/list on " + serverUrl + " returned HTTP "
                    + listResp.statusCode() + ": " + listResp.body());
        }
        return parseStreamableBody(listResp).path("result").path("tools");
    }

    private HttpResponse<String> postStreamable(String url, String body,
                                                String sessionId, String protoVersion) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofMillis(TIMEOUT_MS));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protoVersion != null && !protoVersion.isBlank()) {
            builder.header("MCP-Protocol-Version", protoVersion);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);   // cancel the in-flight exchange
            throw te;
        }
    }

    /** Parses a Streamable HTTP body that may be plain JSON or an SSE-upgraded stream. */
    private JsonNode parseStreamableBody(HttpResponse<String> resp) throws Exception {
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        String body = resp.body();
        String json = contentType.toLowerCase().contains("text/event-stream")
                ? extractSseJson(body) : body;
        return mapper.readTree(json);
    }

    /** The JSON-RPC payload of the last SSE event: its concatenated {@code data:} lines. */
    private static String extractSseJson(String sseBody) {
        StringBuilder current = new StringBuilder();
        String last = null;
        for (String rawLine : sseBody.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.startsWith("data:")) {
                String part = line.substring("data:".length()).strip();
                if (current.length() > 0) current.append('\n');
                current.append(part);
            } else if (line.isBlank()) {
                if (current.length() > 0) {
                    last = current.toString();
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) last = current.toString();
        if (last == null) {
            throw new RuntimeException("SSE response contained no data payload: " + sseBody);
        }
        return last;
    }

    // ── Legacy HTTP+SSE (only when transport: "sse") ───────────────────────────

    /**
     * Runs the legacy MCP SSE handshake: connect → initialize → tools/list → result.
     * The SSE connection stays open throughout (same pattern as McpAdapter's legacy path).
     */
    private JsonNode fetchToolsListSse(String baseUrl, String version) throws Exception {
        CompletableFuture<String> endpointFuture = new CompletableFuture<>();
        CompletableFuture<String> toolsResponseFuture = new CompletableFuture<>();

        HttpRequest sseRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sse"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(TIMEOUT_MS))   // bound the handshake (VT-pinning discipline)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        CompletableFuture<HttpResponse<InputStream>> sseConn =
                httpClient.sendAsync(sseRequest, HttpResponse.BodyHandlers.ofInputStream());

        Thread.startVirtualThread(() -> {
            try {
                HttpResponse<InputStream> resp = sseConn.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("SSE endpoint returned HTTP " + resp.statusCode());
                }
                parseSseStream(resp.body(), endpointFuture, toolsResponseFuture);
            } catch (Exception e) {
                endpointFuture.completeExceptionally(e);
                toolsResponseFuture.completeExceptionally(e);
            }
        });

        String sessionPath;
        try {
            sessionPath = endpointFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            sseConn.cancel(true);
            throw te;
        }
        String sessionUrl  = buildSessionUrl(baseUrl + "/sse", sessionPath);
        log.debug("McpToolIntrospector session URL: {}", sessionUrl);

        // Send initialize
        String initBody = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", version,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "conduit-gateway-introspector", "version", "1.0")
                )
        ));
        postJson(sessionUrl, initBody);

        // Send tools/list — SSE reader skips initialize ACK and waits for this result
        String listBody = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of()
        ));
        postJson(sessionUrl, listBody);

        String rawResponse;
        try {
            rawResponse = toolsResponseFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            sseConn.cancel(true);
            throw te;
        }
        JsonNode root = mapper.readTree(rawResponse);
        return root.path("result").path("tools");
    }

    /**
     * Resolves the MCP protocol version for introspection. Precedence: per-agent manifest override
     * → config ({@code conduit.mcp.protocol-version} / {@code legacy-protocol-version}). No Java
     * literal default.
     */
    private String resolveProtocolVersion(String override, boolean legacy) {
        if (override != null && !override.isBlank()) return override.strip();
        String configured = legacy ? legacyProtocolVersion : protocolVersion;
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("No MCP protocol version configured (conduit.mcp."
                    + (legacy ? "legacy-protocol-version" : "protocol-version") + ")");
        }
        return configured.strip();
    }

    /**
     * Reads SSE events: endpoint → (initialize ACK skipped) → tools/list result.
     */
    private void parseSseStream(
            InputStream body,
            CompletableFuture<String> endpointFuture,
            CompletableFuture<String> toolsResponseFuture) {

        String currentEvent = null;
        StringBuilder currentData = new StringBuilder();
        int messageCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    currentEvent = line.substring("event:".length()).strip();
                    currentData.setLength(0);
                } else if (line.startsWith("data:")) {
                    String part = line.substring("data:".length()).strip();
                    if (!currentData.isEmpty()) currentData.append('\n');
                    currentData.append(part);
                } else if (line.isBlank()) {
                    if (currentEvent != null && !currentData.isEmpty()) {
                        String data = currentData.toString();
                        switch (currentEvent) {
                            case "endpoint" -> {
                                log.debug("McpToolIntrospector: endpoint event = {}", data);
                                endpointFuture.complete(data);
                            }
                            case "message" -> {
                                messageCount++;
                                if (messageCount == 1) {
                                    log.debug("McpToolIntrospector: initialize ACK received (skipped)");
                                } else {
                                    log.debug("McpToolIntrospector: tools/list result received");
                                    toolsResponseFuture.complete(data);
                                    return;
                                }
                            }
                            default -> log.debug("McpToolIntrospector: unknown event '{}': {}", currentEvent, data);
                        }
                    }
                    currentEvent = null;
                    currentData.setLength(0);
                }

                if (toolsResponseFuture.isDone()) return;
            }
            if (!toolsResponseFuture.isDone()) {
                toolsResponseFuture.completeExceptionally(
                        new RuntimeException("SSE stream closed before tools/list response"));
            }
        } catch (Exception e) {
            endpointFuture.completeExceptionally(e);
            toolsResponseFuture.completeExceptionally(e);
        }
    }

    private void postJson(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("POST " + url + " returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
    }

    private String buildSessionUrl(String serverUrl, String sessionPath) {
        URI serverUri = URI.create(serverUrl);
        String origin = serverUri.getScheme() + "://" + serverUri.getAuthority();
        return sessionPath.startsWith("/") ? origin + sessionPath
                : serverUrl.substring(0, serverUrl.lastIndexOf('/') + 1) + sessionPath;
    }

    /**
     * Domain-neutral fallback used only when an MCP server returns no usable inputSchema.
     * The gateway holds no domain knowledge, so it cannot invent the tool's field names here
     * (World B). It returns a permissive empty-properties object schema; binding then relies on
     * the manifest's declared entity types rather than a gateway-hardcoded shape.
     */
    private JsonNode fallbackSchema(String toolName) {
        log.debug("Using domain-neutral fallback schema for MCP tool '{}'", toolName);
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }
}
