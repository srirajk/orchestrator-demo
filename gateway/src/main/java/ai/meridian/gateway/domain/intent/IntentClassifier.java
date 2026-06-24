package ai.meridian.gateway.domain.intent;

import ai.meridian.gateway.api.v1.chat.dto.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stage A of the request pipeline: classifies the user's intent before routing.
 *
 * <p>Uses a small/fast LLM model (default: {@code glm-4.5-flash}) with structured JSON
 * output — one Z.AI call, target p99 &lt; 300 ms. The resulting {@link IntentResult}
 * drives which path {@code ChatService} takes.
 *
 * <p>Falls back to {@code FETCH_DATA} on any error so the pipeline degrades gracefully
 * rather than hard-failing.
 */
@Service
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final String SYSTEM_PROMPT = """
            You are a routing classifier for a banking AI assistant. \
            Given the conversation history, classify the user's latest message into exactly one intent:

            FETCH_DATA  - needs fresh data from banking systems (holdings, settlements, performance, etc.)
            FOLLOW_UP   - asks for clarification, explanation, or reformulation of data already in the conversation
            CLARIFY     - the topic is clear but which client/fund is ambiguous; a scoped question is needed
            CHITCHAT    - general conversation unrelated to banking data

            Reply with ONLY valid JSON:
            {"intent":"FETCH_DATA","confidence":0.95,"reasoning":"user asked for holdings data"}

            Rules:
            - If the user mentions a client name or relationship (Whitman, Chen, etc.) → lean FETCH_DATA
            - If prior assistant turn has banking data and user says "explain", "simplify", "what does X mean", \
              "tell me more" → FOLLOW_UP
            - If the intent is banking-related but no client is mentioned and none appeared in prior turns → CLARIFY
            - Greetings, "how are you", "thanks" → CHITCHAT
            """;

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final Timer classifyTimer;
    private final Tracer tracer;

    public IntentClassifier(
            ObjectMapper mapper,
            MeterRegistry meterRegistry,
            Tracer tracer,
            @Value("${meridian.llm.base-url:https://api.z.ai/api/paas/v4}") String baseUrl,
            @Value("${meridian.llm.api-key:${ZAI_API_KEY:}}") String apiKey,
            @Value("${meridian.llm.intent-model:glm-4.5-flash}") String model) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.tracer = tracer;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.classifyTimer = Timer.builder("intent.classify.duration")
                .description("Time spent classifying intent")
                .register(meterRegistry);
    }

    /**
     * Classify the intent of the latest user message given the full conversation history.
     * Never throws — falls back to {@link IntentResult#fetchDataFallback()}.
     */
    public IntentResult classify(List<Message> messages) {
        Span span = tracer.spanBuilder("intent.classify").startSpan();
        return classifyTimer.record(() -> {
            try {
                IntentResult result = callLlm(messages);
                span.setAttribute("intent", result.intent().name());
                span.setAttribute("confidence", result.confidence());
                span.setAttribute("reasoning", result.reasoning());
                log.info("IntentClassifier → {} (confidence={}) | {}",
                        result.intent(), String.format("%.2f", result.confidence()), result.reasoning());
                return result;
            } catch (Exception e) {
                log.warn("IntentClassifier failed ({}), falling back to FETCH_DATA: {}",
                        e.getClass().getSimpleName(), e.getMessage());
                span.setAttribute("intent", "FETCH_DATA");
                span.setAttribute("fallback", true);
                return IntentResult.fetchDataFallback();
            } finally {
                span.end();
            }
        });
    }

    private IntentResult callLlm(List<Message> messages) throws Exception {
        // Build conversation tail (last 6 messages) for context
        String conversationContext = buildContext(messages);

        String requestBody = mapper.writeValueAsString(mapper.createObjectNode()
                .put("model", model)
                .put("stream", false)
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("response_format",
                        mapper.createObjectNode().put("type", "json_object"))
                .set("messages", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("role", "system")
                                .put("content", SYSTEM_PROMPT))
                        .add(mapper.createObjectNode()
                                .put("role", "user")
                                .put("content", conversationContext))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM returned HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        String content = root.path("choices").get(0).path("message").path("content").asText();

        // The model may wrap its JSON inside markdown fences — strip them
        content = content.strip();
        if (content.startsWith("```")) {
            content = content.replaceAll("```[a-z]*\\n?", "").replace("```", "").strip();
        }

        JsonNode parsed = mapper.readTree(content);
        String intentStr = parsed.path("intent").asText("FETCH_DATA").toUpperCase();
        double confidence = parsed.path("confidence").asDouble(0.8);
        String reasoning = parsed.path("reasoning").asText("");

        Intent intent;
        try {
            intent = Intent.valueOf(intentStr);
        } catch (IllegalArgumentException e) {
            intent = Intent.FETCH_DATA;
        }

        return new IntentResult(intent, confidence, reasoning);
    }

    private String buildContext(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return "(empty conversation)";
        int start = Math.max(0, messages.size() - 6);
        return messages.subList(start, messages.size()).stream()
                .filter(m -> m.content() != null && !m.content().isBlank())
                .map(m -> m.role().toUpperCase() + ": " + truncate(m.content(), 400))
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
