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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.TimeoutException;

/**
 * ProtocolAdapter for MCP agents (Asset Servicing domain).
 *
 * <p>Speaks two MCP HTTP transports, selected per agent by {@code connection.transport}:
 *
 * <p><b>Streamable HTTP</b> (default — spec {@code 2025-11-25}). A single endpoint
 * ({@code connection.server_url}, e.g. {@code /mcp}) handles the whole exchange:
 * <ol>
 *   <li>{@code POST} JSON-RPC {@code initialize} with
 *       {@code Accept: application/json, text/event-stream} → the response carries an
 *       {@code Mcp-Session-Id} header and a negotiated {@code result.protocolVersion}.</li>
 *   <li>{@code POST} JSON-RPC {@code tools/call}, echoing the {@code Mcp-Session-Id} and
 *       {@code MCP-Protocol-Version} headers → tool result.</li>
 * </ol>
 * Each response body may be either a plain JSON object or an SSE-upgraded stream; both are
 * handled.
 *
 * <p><b>Legacy HTTP+SSE</b> (deprecated — reachable only when a manifest explicitly declares
 * {@code transport: "sse"}). Two-channel handshake: {@code GET} opens an SSE stream that first
 * emits an {@code endpoint} event with a session path, then JSON-RPC calls are {@code POST}ed to
 * that path and their results arrive back as {@code message} SSE events.
 *
 * <p><b>Invariants (all preserved across both transports):</b>
 * <ul>
 *   <li><b>HTTP/1.1</b> is forced on the shared client AND every request — FastMCP/uvicorn
 *       returns 421 on an HTTP/2 upgrade, and an h2c upgrade over cleartext 404s.</li>
 *   <li><b>Bearer token is propagated fail-closed</b>: a blank token aborts the invocation
 *       (the caller identity was never propagated upstream — refuse rather than call
 *       unauthenticated).</li>
 *   <li><b>W3C trace context</b> ({@code traceparent}) is injected on every outbound request.</li>
 *   <li><b>Tool-execution errors are failures</b>: a JSON-RPC {@code error} member (protocol
 *       error) OR a successful {@code result.isError == true} (tool ran and failed) both throw.</li>
 * </ul>
 *
 * <p>The MCP protocol version is never a Java string literal: it comes from
 * {@code connection.protocol_version} (per-agent) else {@code conduit.mcp.protocol-version}
 * (streamable) / {@code conduit.mcp.legacy-protocol-version} (sse).
 *
 * <p><b>Forward compatibility (MCP 2.0, spec {@code 2026-07-28}):</b> that spec is a Release
 * Candidate and the Python SDK v2 is pre-release only, so it is designed-for but not adopted here.
 * Adoption is a config change (bump {@code conduit.mcp.protocol-version}) plus any additive
 * headers — no code fork on this path.
 */
@Service
public class McpAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpAdapter.class);
    private static final TextMapSetter<HttpRequest.Builder> REQUEST_BUILDER_SETTER =
            (carrier, key, value) -> {
                if (carrier != null && key != null && value != null) {
                    carrier.header(key, value);
                }
            };

    private final ObjectMapper objectMapper;

    // Negotiated MCP protocol versions — sourced from config, overridable per-agent by the
    // manifest. No Java literal default (World B): resolved via resolveProtocolVersion().
    private final String protocolVersion;        // streamable HTTP
    private final String legacyProtocolVersion;  // legacy HTTP+SSE

    // Force HTTP/1.1: FastMCP/uvicorn returns 421 on HTTP/2 upgrade attempts.
    private final HttpClient httpClient;

    // No SLA declared on a manifest → this default budget for the whole invoke() (both the
    // initialize and the tools/call share it). Config, not a Java constant (World B / §5).
    private final int defaultTimeoutMs;

    @Autowired
    public McpAdapter(ObjectMapper objectMapper,
                      @Value("${conduit.mcp.protocol-version}") String protocolVersion,
                      @Value("${conduit.mcp.legacy-protocol-version}") String legacyProtocolVersion,
                      @Value("${conduit.mcp.default-timeout-ms:10000}") int defaultTimeoutMs,
                      @Value("${conduit.mcp.connect-timeout-ms:10000}") int connectTimeoutMs) {
        this.objectMapper = objectMapper;
        this.protocolVersion = protocolVersion;
        this.legacyProtocolVersion = legacyProtocolVersion;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    /**
     * Test-only convenience constructor. Protocol versions come from configuration in production;
     * unit tests that exercise only response parsing ({@link #extractResult}) do not need them.
     */
    McpAdapter(ObjectMapper objectMapper) {
        this(objectMapper, null, null, 10000, 10000);
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
        // ONE end-to-end deadline for the whole invoke(). Both sequential legs (initialize, then
        // tools/call) draw down the SAME budget, so a slow-healthy agent whose initialize eats 80%
        // leaves tools/call only the remainder and the call fails inside the SLA — instead of each
        // leg getting the full budget (worst case ≈ 2× SLA). remaining(deadlineNanos) is what every
        // request timeout and future-wait below uses.
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;

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

        if (manifest.connection().isLegacySse()) {
            log.debug("McpAdapter → tool '{}' on {} (legacy SSE) for agent {}",
                    toolName, serverUrl, manifest.agentId());
            return invokeSse(manifest, input, toolName, serverUrl, bearerToken, deadlineNanos);
        }

        log.debug("McpAdapter → tool '{}' on {} (streamable HTTP) for agent {}",
                toolName, serverUrl, manifest.agentId());
        return invokeStreamable(manifest, input, toolName, serverUrl, bearerToken, deadlineNanos);
    }

    /** Milliseconds left until {@code deadlineNanos}, floored at 1 so a callee always gets a positive,
     *  finite budget (never 0/negative, which some HTTP timeouts reject or read as "no timeout"). */
    private static long remaining(long deadlineNanos) {
        long ms = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return ms < 1 ? 1 : ms;
    }

    // ── Streamable HTTP transport (default) ────────────────────────────────────

    private JsonNode invokeStreamable(AgentManifest manifest, JsonNode input, String toolName,
                                      String serverUrl, String bearerToken, long deadlineNanos)
            throws Exception {

        String requestedVersion = resolveProtocolVersion(manifest, false);

        // 1) initialize — capture Mcp-Session-Id and the negotiated protocolVersion.
        String initBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id",      UUID.randomUUID().toString(),
                "method",  "initialize",
                "params",  Map.of(
                        "protocolVersion", requestedVersion,
                        "capabilities",   Map.of(),
                        "clientInfo",     Map.of("name", "conduit-gateway", "version", "1.0")
                )
        ));
        HttpResponse<String> initResp =
                postStreamable(serverUrl, initBody, bearerToken, null, null, deadlineNanos);
        if (initResp.statusCode() >= 400) {
            throw new RuntimeException("MCP initialize on " + serverUrl + " returned HTTP "
                    + initResp.statusCode() + ": " + initResp.body());
        }
        String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElse(null);
        JsonNode initResult = parseStreamableBody(initResp);
        String negotiatedVersion = initResult.path("result").path("protocolVersion")
                .asText(requestedVersion);
        String verifiedSub = initResp.headers().firstValue("X-Conduit-Verified-Sub").orElse(null);
        log.debug("McpAdapter streamable init: session={}, negotiated protocolVersion={}",
                sessionId, negotiatedVersion);

        // 2) tools/call — echo Mcp-Session-Id and MCP-Protocol-Version.
        String callBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id",      UUID.randomUUID().toString(),
                "method",  "tools/call",
                "params",  Map.of("name", toolName, "arguments", jsonNodeToMap(input))
        ));
        HttpResponse<String> callResp =
                postStreamable(serverUrl, callBody, bearerToken, sessionId, negotiatedVersion, deadlineNanos);
        if (callResp.statusCode() >= 400) {
            throw new RuntimeException("MCP tools/call on " + serverUrl + " returned HTTP "
                    + callResp.statusCode() + ": " + callResp.body());
        }
        if (verifiedSub == null) {
            verifiedSub = callResp.headers().firstValue("X-Conduit-Verified-Sub").orElse(null);
        }
        JsonNode callRoot = parseStreamableBody(callResp);
        log.debug("McpAdapter streamable raw response ({}): {}", manifest.agentId(),
                truncate(callRoot.toString()));

        return withVerifiedSub(extractResult(callRoot), verifiedSub);
    }

    /**
     * POSTs one JSON-RPC message on the Streamable HTTP endpoint. Sets {@code Accept} for a
     * JSON-or-SSE response, propagates the bearer token and W3C trace context, and echoes the
     * session id / protocol version when known.
     */
    private HttpResponse<String> postStreamable(String url, String body, String bearerToken,
                                                String sessionId, String protoVersion, long deadlineNanos)
            throws Exception {
        long remaining = remaining(deadlineNanos);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(remaining))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protoVersion != null && !protoVersion.isBlank()) {
            builder.header("MCP-Protocol-Version", protoVersion);
        }
        injectTraceContext(builder);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        // HttpRequest.timeout() bounds time-to-headers; sendWithTimeout(remaining) additionally bounds
        // the BODY phase and cancels the in-flight exchange on expiry.
        return sendWithTimeout(request, remaining);
    }

    /**
     * Parses a Streamable HTTP response body, which may be a plain JSON object OR an
     * SSE-upgraded stream (the SDK's default when {@code json_response} is off). For SSE, the
     * JSON-RPC message is the concatenated {@code data:} lines of the (last) event.
     */
    private JsonNode parseStreamableBody(HttpResponse<String> resp) throws Exception {
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        String body = resp.body();
        String json = contentType.toLowerCase().contains("text/event-stream")
                ? extractSseJson(body)
                : body;
        return objectMapper.readTree(json);
    }

    /**
     * Extracts the JSON-RPC payload from an SSE body: the {@code data:} lines of the last
     * complete event, concatenated. MCP sends exactly one JSON-RPC response per POST.
     */
    static String extractSseJson(String sseBody) {
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
        if (current.length() > 0) {
            last = current.toString();   // final event with no trailing blank line
        }
        if (last == null) {
            throw new RuntimeException("SSE response contained no data payload: " + sseBody);
        }
        return last;
    }

    /**
     * Sends a request and waits up to {@code timeoutMs}. On timeout the in-flight exchange is
     * cancelled ({@code future.cancel(true)}) rather than left running against a caller that has
     * already given up.
     */
    private HttpResponse<String> sendWithTimeout(HttpRequest request, long timeoutMs) throws Exception {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw te;
        } catch (InterruptedException ie) {
            // The harness cancelled this call at its SLA (or a shutdown interrupt). Abort the
            // in-flight exchange rather than leak the socket, then re-assert the interrupt so the
            // caller's cancellation contract is honoured.
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    // ── Legacy HTTP+SSE transport (only when transport: "sse") ─────────────────

    private JsonNode invokeSse(AgentManifest manifest, JsonNode input, String toolName,
                               String serverUrl, String bearerToken, long deadlineNanos) throws Exception {

        String requestedVersion = resolveProtocolVersion(manifest, true);

        // Two futures the SSE reader will complete.
        CompletableFuture<String> endpointFuture = new CompletableFuture<>();
        CompletableFuture<String> toolResponseFuture = new CompletableFuture<>();
        CompletableFuture<String> verifiedSubFuture = new CompletableFuture<>();

        // The live response body stream, captured by the reader VT once the connection is up.
        // Needed because once the response has arrived, sseConn.cancel(true) is a no-op — cancelling
        // an already-completed future does NOT close the socket, so a server that connects then goes
        // silent would leave the reader VT parked on readLine() and the socket open forever. On
        // deadline expiry we close THIS stream explicitly, which unblocks the reader.
        final java.util.concurrent.atomic.AtomicReference<InputStream> liveBody =
                new java.util.concurrent.atomic.AtomicReference<>();

        // SSE request — must be HTTP/1.1; uvicorn rejects HTTP/2 with 421. .timeout() bounds
        // time-to-headers (the handshake); the body stream stays open after headers arrive.
        HttpRequest.Builder sseBuilder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(remaining(deadlineNanos)))
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
                HttpResponse<InputStream> resp = sseConn.get(remaining(deadlineNanos), TimeUnit.MILLISECONDS);
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("SSE endpoint returned HTTP " + resp.statusCode());
                }
                liveBody.set(resp.body());
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
        String sessionPath;
        try {
            sessionPath = endpointFuture.get(remaining(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            abortSse(sseConn, liveBody);
            throw te;
        }
        String sessionUrl = buildSessionUrl(serverUrl, sessionPath);
        log.debug("McpAdapter session URL: {}", sessionUrl);

        // Send initialize.
        String initBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id",      UUID.randomUUID().toString(),
                "method",  "initialize",
                "params",  Map.of(
                        "protocolVersion", requestedVersion,
                        "capabilities",   Map.of(),
                        "clientInfo",     Map.of("name", "conduit-gateway", "version", "1.0")
                )
        ));
        postJson(sessionUrl, initBody, bearerToken, deadlineNanos);

        // Send tools/call — SSE reader skips the initialize ACK and waits for this result.
        String callBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id",      UUID.randomUUID().toString(),
                "method",  "tools/call",
                "params",  Map.of("name", toolName, "arguments", jsonNodeToMap(input))
        ));
        postJson(sessionUrl, callBody, bearerToken, deadlineNanos);

        // Wait for the tool-call SSE response (second message event).
        String rawResponse;
        try {
            rawResponse = toolResponseFuture.get(remaining(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            abortSse(sseConn, liveBody);
            throw te;
        }
        log.debug("McpAdapter raw response ({}): {}", manifest.agentId(), truncate(rawResponse));

        return withVerifiedSub(extractResult(rawResponse), verifiedSubFuture.getNow(null));
    }

    /**
     * Aborts an in-flight legacy-SSE exchange on deadline expiry. Cancels the connect future (a
     * no-op if headers already arrived) AND closes the live body stream — closing the stream is what
     * unblocks a reader VT parked on {@code readLine()} against a silent server, so no reader thread
     * or socket is leaked.
     */
    private void abortSse(CompletableFuture<HttpResponse<InputStream>> sseConn,
                          java.util.concurrent.atomic.AtomicReference<InputStream> liveBody) {
        sseConn.cancel(true);
        InputStream body = liveBody.get();
        if (body != null) {
            try {
                body.close();
            } catch (Exception ignored) {
                // best-effort: the reader VT will still unwind once the stream is closed/errored
            }
        }
    }

    /**
     * Reads the legacy SSE stream line by line.
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

    private void postJson(String url, String body, String bearerToken, long deadlineNanos) throws Exception {
        long remaining = remaining(deadlineNanos);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(remaining))
                .header("Content-Type", "application/json");
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        injectTraceContext(builder);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        // Was httpClient.send() — a blocking, UNTIMED send that could park past the invoke deadline.
        // Now bounded by the remaining budget AND cancelled on expiry (sendWithTimeout).
        HttpResponse<String> response = sendWithTimeout(request, remaining);
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

    /**
     * Resolves the MCP protocol version to negotiate.
     *
     * <p>Precedence: {@code connection.protocol_version} (per-agent manifest override) →
     * {@code conduit.mcp.protocol-version} (streamable) / {@code conduit.mcp.legacy-protocol-version}
     * (sse). No Java literal default — an unconfigured version is a boot/config error, not a
     * silent fallback.
     */
    private String resolveProtocolVersion(AgentManifest manifest, boolean legacy) {
        String override = manifest.connection().protocolVersion();
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        String configured = legacy ? legacyProtocolVersion : protocolVersion;
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("No MCP protocol version configured (conduit.mcp."
                    + (legacy ? "legacy-protocol-version" : "protocol-version") + ")");
        }
        return configured.strip();
    }

    // ── Response extraction ───────────────────────────────────────────────────

    /**
     * Parses the JSON-RPC tool response from its raw string form.
     */
    JsonNode extractResult(String rawResponse) throws Exception {
        return extractResult(objectMapper.readTree(rawResponse));
    }

    /**
     * Parses the JSON-RPC tool response.
     *
     * <p>MCP tool result shape:
     * <pre>
     * { "jsonrpc":"2.0", "id":"...", "result":{ "content":[{"type":"text","text":"{...}"}] } }
     * </pre>
     */
    JsonNode extractResult(JsonNode root) {
        // (1) PROTOCOL error — the JSON-RPC "error" member. Malformed request, unknown tool, etc.
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            throw new RuntimeException("MCP JSON-RPC error: "
                    + error.path("message").asText(root.toString()));
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

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

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
        return defaultTimeoutMs;
    }
}
