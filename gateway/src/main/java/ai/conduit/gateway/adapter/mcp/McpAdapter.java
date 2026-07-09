package ai.conduit.gateway.adapter.mcp;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ProtocolAdapter for MCP/SSE agents (Asset Servicing domain).
 *
 * <p>MCP SSE handshake per invocation:
 * <ol>
 *   <li>GET {@code serverUrl} with {@code Accept: text/event-stream} →
 *       server sends {@code event: endpoint} with session path.</li>
 *   <li>POST session path with JSON-RPC {@code initialize} →
 *       server sends {@code event: message} with initialize ACK.</li>
 *   <li>POST session path with JSON-RPC {@code tools/call} →
 *       server sends {@code event: message} with tool result.</li>
 * </ol>
 *
 * <p><b>Implementation notes:</b>
 * <ul>
 *   <li>Forces {@code HTTP_1_1} — FastMCP/uvicorn does not support HTTP/2 and
 *       returns 421 Misdirected Request when the client upgrades.</li>
 *   <li>SSE reading runs on a virtual thread (blocking I/O) using two
 *       {@link CompletableFuture}s: one for the endpoint path, one for the
 *       tools/call result.  The initialize ACK (first message event) is counted
 *       and skipped; the second message event is the actual tool result.</li>
 * </ul>
 */
@Service
public class McpAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpAdapter.class);
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final TextMapSetter<HttpRequest.Builder> REQUEST_BUILDER_SETTER =
            (carrier, key, value) -> {
                if (carrier != null && key != null && value != null) {
                    carrier.header(key, value);
                }
            };

    private final ObjectMapper objectMapper;

    // Force HTTP/1.1: FastMCP/uvicorn returns 421 on HTTP/2 upgrade attempts.
    private final HttpClient httpClient;

    public McpAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String protocol() {
        return "mcp";
    }

    @Override
    public JsonNode invoke(AgentManifest manifest, JsonNode input, String bearerToken) throws Exception {
        String serverUrl = manifest.connection().serverUrl();
        String toolName  = manifest.connection().tool();
        int timeoutMs    = resolveTimeout(manifest);

        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Agent " + manifest.agentId() + " has no serverUrl");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "Agent " + manifest.agentId() + " has no tool name");
        }
        // F-IDENTITY: a tools/call is a data-plane call — it must carry the caller's verified
        // identity. bearerToken is passed explicitly by the harness (never read from
        // SecurityContextHolder, which does not survive the hop onto the DAG/flat executor's
        // virtual threads). A missing token here means the security context was not propagated
        // upstream — refuse rather than silently calling the agent unauthenticated.
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalStateException(
                    "No caller identity available for agent " + manifest.agentId()
                    + " — refusing to invoke without a bearer token");
        }

        log.debug("McpAdapter → tool '{}' on {} for agent {}", toolName, serverUrl, manifest.agentId());

        // Two futures the SSE reader will complete.
        CompletableFuture<String> endpointFuture = new CompletableFuture<>();
        CompletableFuture<String> toolResponseFuture = new CompletableFuture<>();
        CompletableFuture<String> verifiedSubFuture = new CompletableFuture<>();

        // SSE request — must be HTTP/1.1; uvicorn rejects HTTP/2 with 421.
        HttpRequest.Builder sseBuilder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");
        if (bearerToken != null) {
            sseBuilder.header("Authorization", "Bearer " + bearerToken);
            log.debug("McpAdapter: propagating JWT to agent SSE connection");
        }
        injectTraceContext(sseBuilder);
        HttpRequest sseRequest = sseBuilder.GET().build();

        // Open SSE stream; read the response body on a virtual thread so we can
        // block on InputStream without pinning a platform thread.
        CompletableFuture<HttpResponse<InputStream>> sseConn =
                httpClient.sendAsync(sseRequest, HttpResponse.BodyHandlers.ofInputStream());

        // Start SSE reader virtual thread.
        Thread.startVirtualThread(() -> {
            try {
                HttpResponse<InputStream> resp = sseConn.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("SSE endpoint returned HTTP " + resp.statusCode());
                }
                verifiedSubFuture.complete(resp.headers()
                        .firstValue("X-Conduit-Verified-Sub").orElse(null));
                parseSseStream(resp.body(), endpointFuture, toolResponseFuture);
            } catch (Exception e) {
                endpointFuture.completeExceptionally(e);
                toolResponseFuture.completeExceptionally(e);
                verifiedSubFuture.complete(null);
            }
        });

        // Wait for the session endpoint path.
        String sessionPath = endpointFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        String sessionUrl  = buildSessionUrl(serverUrl, sessionPath);
        log.debug("McpAdapter session URL: {}", sessionUrl);

        // Send initialize.
        String initId = UUID.randomUUID().toString();
        String initBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id",      initId,
                "method",  "initialize",
                "params",  Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities",   Map.of(),
                        "clientInfo",     Map.of("name", "conduit-gateway", "version", "1.0")
                )
        ));
        postJson(sessionUrl, initBody, bearerToken);

        // Send tools/call — SSE reader skips the initialize ACK and waits for this result.
        String callId = UUID.randomUUID().toString();
        String callBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id",      callId,
                "method",  "tools/call",
                "params",  Map.of("name", toolName, "arguments", jsonNodeToMap(input))
        ));
        postJson(sessionUrl, callBody, bearerToken);

        // Wait for the tool-call SSE response (second message event).
        String rawResponse = toolResponseFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        log.debug("McpAdapter raw response ({}): {}", manifest.agentId(),
                rawResponse.length() > 200 ? rawResponse.substring(0, 200) + "…" : rawResponse);

        return withVerifiedSub(extractResult(rawResponse), verifiedSubFuture.getNow(null));
    }

    // ── SSE stream reader ─────────────────────────────────────────────────────

    /**
     * Reads the SSE stream line by line.
     *
     * <p>The server sends exactly three events per invocation:
     * <ol>
     *   <li>{@code event: endpoint} — session path → completes {@code endpointFuture}</li>
     *   <li>{@code event: message} (first) — initialize ACK → skipped</li>
     *   <li>{@code event: message} (second) — tool result → completes {@code toolResponseFuture}</li>
     * </ol>
     */
    private void parseSseStream(
            InputStream body,
            CompletableFuture<String> endpointFuture,
            CompletableFuture<String> toolResponseFuture) {

        String currentEvent = null;
        StringBuilder currentData = new StringBuilder();
        int messageCount = 0;   // counts "message" events seen; we want the 2nd one

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
                    // Event boundary.
                    if (currentEvent != null && !currentData.isEmpty()) {
                        String data = currentData.toString();
                        switch (currentEvent) {
                            case "endpoint" -> {
                                log.debug("SSE endpoint received: {}", data);
                                endpointFuture.complete(data);
                            }
                            case "message" -> {
                                messageCount++;
                                if (messageCount == 1) {
                                    // First message = initialize ACK; skip it.
                                    log.debug("SSE initialize ACK received (skipped)");
                                } else {
                                    // Second message = tools/call result.
                                    log.debug("SSE tool result received (message #{})", messageCount);
                                    toolResponseFuture.complete(data);
                                    return;   // Done — stop reading.
                                }
                            }
                            default -> log.debug("SSE unknown event '{}': {}", currentEvent, data);
                        }
                    }
                    currentEvent = null;
                    currentData.setLength(0);
                }

                if (toolResponseFuture.isDone()) return;
            }
            // Stream closed without a second message event.
            if (!toolResponseFuture.isDone()) {
                toolResponseFuture.completeExceptionally(
                        new RuntimeException("SSE stream closed before tools/call response"));
            }
        } catch (Exception e) {
            endpointFuture.completeExceptionally(e);
            toolResponseFuture.completeExceptionally(e);
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private void postJson(String url, String body, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json");
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        injectTraceContext(builder);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("POST " + url + " returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        log.debug("POST {} → HTTP {}", url, response.statusCode());
    }

    private void injectTraceContext(HttpRequest.Builder builder) {
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), builder, REQUEST_BUILDER_SETTER);
    }

    /**
     * Builds the absolute session URL from the SSE server URL and the relative
     * session path returned by the server.
     *
     * <p>Example: serverUrl={@code http://servicing-mcp:8082/sse},
     * sessionPath={@code /messages/?session_id=abc}
     * → {@code http://servicing-mcp:8082/messages/?session_id=abc}
     */
    private String buildSessionUrl(String serverUrl, String sessionPath) {
        URI serverUri = URI.create(serverUrl);
        String origin = serverUri.getScheme() + "://" + serverUri.getAuthority();
        return sessionPath.startsWith("/") ? origin + sessionPath
                : serverUrl.substring(0, serverUrl.lastIndexOf('/') + 1) + sessionPath;
    }

    // ── Response extraction ───────────────────────────────────────────────────

    /**
     * Parses the JSON-RPC tool response.
     *
     * <p>MCP tool result shape:
     * <pre>
     * { "jsonrpc":"2.0", "id":"...", "result":{ "content":[{"type":"text","text":"{...}"}] } }
     * </pre>
     */
    JsonNode extractResult(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);

        // (1) PROTOCOL error — the JSON-RPC "error" member. Malformed request, unknown tool, etc.
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            throw new RuntimeException("MCP JSON-RPC error: " + error.path("message").asText(rawResponse));
        }

        JsonNode result = root.path("result");
        if (result.isMissingNode()) {
            return root;
        }

        // (2) TOOL EXECUTION error — a *successful* JSON-RPC response whose result carries
        // isError=true. This is the only signal MCP gives for "the tool ran and failed"; there is
        // no status code as there is over HTTP. Without this check, an agent reporting
        // "llm_unavailable" is indistinguishable from one that answered: the call is counted OK,
        // the request is recorded ANSWERED, and the error object is handed to the synthesizer as
        // ground truth (hard rule c — agent outputs are the only ground truth). Throwing here
        // makes the harness record a failed node, so okCount / no_data / PARTIAL tell the truth.
        if (isToolError(result)) {
            throw new RuntimeException("MCP tool error: "
                    + firstContentText(result).orElseGet(result::toString));
        }

        return firstContentText(result)
                .map(text -> {
                    try {
                        return objectMapper.readTree(text);
                    } catch (Exception e) {
                        return (JsonNode) objectMapper.createObjectNode().put("text", text);
                    }
                })
                .orElse(result);
    }

    /** True when the tool ran and reported failure (MCP {@code CallToolResult.isError}). */
    static boolean isToolError(JsonNode result) {
        return result.path("isError").asBoolean(false);
    }

    /** The first {@code content[].text} of an MCP tool result, if present. */
    static Optional<String> firstContentText(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray() && !content.isEmpty()) {
            String text = content.get(0).path("text").asText(null);
            if (text != null) return Optional.of(text);
        }
        return Optional.empty();
    }

    private JsonNode withVerifiedSub(JsonNode data, String verifiedSub) {
        if (verifiedSub == null || verifiedSub.isBlank() || data == null || !data.isObject()) {
            return data;
        }
        ObjectNode copy = objectMapper.createObjectNode();
        copy.put("_verified_sub", verifiedSub);
        copy.setAll((ObjectNode) data);
        return copy;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            map.put(e.getKey(), jsonNodeToValue(e.getValue()));
        }
        return map;
    }

    private Object jsonNodeToValue(JsonNode node) {
        return switch (node.getNodeType()) {
            case STRING  -> node.asText();
            case NUMBER  -> node.isIntegralNumber() ? node.asLong() : node.asDouble();
            case BOOLEAN -> node.asBoolean();
            case NULL    -> null;
            case OBJECT  -> jsonNodeToMap(node);
            case ARRAY   -> {
                List<Object> list = new ArrayList<>();
                node.elements().forEachRemaining(el -> list.add(jsonNodeToValue(el)));
                yield list;
            }
            default -> node.toString();
        };
    }

    private int resolveTimeout(AgentManifest manifest) {
        if (manifest.constraints() != null && manifest.constraints().slaTimeoutMs() > 0) {
            return manifest.constraints().slaTimeoutMs();
        }
        return DEFAULT_TIMEOUT_MS;
    }
}
