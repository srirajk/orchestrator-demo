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
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(backgroundExecutor)
                .build();
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/chat/completions"))
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
