package ai.conduit.chat.chat;

import ai.conduit.chat.config.AppProperties;
import ai.conduit.chat.memory.ChatMessage;
import ai.conduit.chat.web.GatewayException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Client for the Conduit gateway's OpenAI-compatible {@code /v1/chat/completions}
 * endpoint.
 *
 * <p>Opens a <em>streaming</em> connection: {@link #openChatStream} returns once the
 * response headers (and therefore the status code) are available, exposing the body
 * as an {@link java.io.InputStream}. This lets the controller reject a non-2xx gateway
 * response with an error <b>before</b> any SSE bytes are written to the client, so a
 * failing gateway never leaves the client hanging.
 */
@Component
public class GatewayClient {

    private final AppProperties.Gateway config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GatewayClient(AppProperties appProperties, ObjectMapper objectMapper, ExecutorService backgroundExecutor) {
        this.config = appProperties.gateway();
        this.objectMapper = objectMapper;
        // HTTP/1.1 is pinned deliberately. The gateway is reached over cleartext http://, and an
        // HTTP/2-preferring client sends `Upgrade: h2c` on the first request; a server that does not
        // negotiate the upgrade answers 404 with an empty body, which surfaces here as an
        // unexplained gateway failure rather than a protocol error.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .executor(backgroundExecutor)
                .build();
    }

    /**
     * Bounds the wait for response <em>headers</em>, not the body. With
     * {@link HttpResponse.BodyHandlers#ofInputStream()} the request timeout expires only while we are
     * still waiting for the status line, so a long-lived SSE body is never cut short — but a gateway
     * that accepts the socket and then stalls before responding can no longer park the caller forever.
     */
    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(config.requestTimeoutMs()));
    }

    /**
     * Opens a streaming completion. Forwards the user's OIDC access token as a Bearer
     * credential so the gateway enforces that user's entitlements, and tags the request
     * with {@code X-Conversation-Id} for gateway-side trace grouping.
     *
     * @throws GatewayException if the gateway is unreachable.
     */
    public GatewayStream openChatStream(String accessToken, String conversationId, List<ChatMessage> messages) {
        String payload = serialize(messages);
        HttpRequest request = request(URI.create(config.baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Conversation-Id", conversationId)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new GatewayStream(response.statusCode(), response.body());
        } catch (java.io.IOException ex) {
            throw new GatewayException("Gateway unreachable", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Gateway request interrupted", ex);
        }
    }

    /**
     * Opens the gateway's glass-box trace SSE stream ({@code GET /trace/stream}), scoped to a
     * single conversation so the rail only sees this conversation's authorization/pipeline frames.
     *
     * <p>The gateway's {@code /trace/**} is public (a browser {@code EventSource} cannot carry a
     * Bearer header), so no token is forwarded — the BFF gates access to this proxy via its own
     * session ({@code /api/**} is authenticated) instead.
     *
     * @throws GatewayException if the gateway is unreachable.
     */
    public GatewayStream openTraceStream(String conversationId) {
        String uri = config.baseUrl() + "/trace/stream"
                + "?conversationId=" + java.net.URLEncoder.encode(
                        conversationId == null ? "" : conversationId, java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest request = request(URI.create(uri))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try {
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new GatewayStream(response.statusCode(), response.body());
        } catch (java.io.IOException ex) {
            throw new GatewayException("Gateway trace stream unreachable", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Gateway trace stream interrupted", ex);
        }
    }

    private String serialize(List<ChatMessage> messages) {
        List<Map<String, String>> wire = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "stream", true,
                "messages", wire
        );
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new GatewayException("Failed to serialize gateway request", ex);
        }
    }
}
