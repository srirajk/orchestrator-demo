package ai.conduit.gateway.registry.introspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Fetches tool input schema from an MCP server via SSE + JSON-RPC.
 *
 * <p>Uses the same virtual-thread + CompletableFuture pattern as {@link ai.conduit.gateway.adapter.mcp.McpAdapter}:
 * keeps the SSE connection open for the full exchange (endpoint → initialize → tools/list).
 */
@Component
public class McpToolIntrospector {

    private static final Logger log = LoggerFactory.getLogger(McpToolIntrospector.class);
    private static final int TIMEOUT_MS = 15_000;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public McpToolIntrospector(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Fetch the JSON Schema inputSchema for a named MCP tool.
     * Falls back to a generic schema if introspection fails.
     */
    public JsonNode getToolInputSchema(String serverUrl, String toolName) {
        try {
            String baseUrl = serverUrl.contains("/sse") ? serverUrl.substring(0, serverUrl.lastIndexOf("/sse")) : serverUrl;
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

    /**
     * Runs the full MCP SSE handshake: connect → initialize → tools/list → result.
     * The SSE connection stays open throughout (same pattern as McpAdapter).
     */
    private JsonNode fetchToolsList(String baseUrl) throws Exception {
        CompletableFuture<String> endpointFuture = new CompletableFuture<>();
        CompletableFuture<String> toolsResponseFuture = new CompletableFuture<>();

        HttpRequest sseRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sse"))
                .version(HttpClient.Version.HTTP_1_1)
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

        String sessionPath = endpointFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        String sessionUrl  = buildSessionUrl(baseUrl + "/sse", sessionPath);
        log.debug("McpToolIntrospector session URL: {}", sessionUrl);

        // Send initialize
        String initBody = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
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

        String rawResponse = toolsResponseFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        JsonNode root = mapper.readTree(rawResponse);
        return root.path("result").path("tools");
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
