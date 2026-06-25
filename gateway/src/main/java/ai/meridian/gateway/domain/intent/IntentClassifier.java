package ai.meridian.gateway.domain.intent;

import ai.meridian.gateway.api.v1.chat.dto.Message;
import ai.meridian.gateway.synthesis.input.EntityBag;
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
import java.util.ArrayList;
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

    /**
     * Combined system prompt: classifies intent AND extracts entities in a single LLM call.
     * For FETCH_DATA, the entity fields are populated; for other intents they are null/empty.
     * This eliminates the separate EntityExtractor LLM round-trip (~2-5s saving per request).
     */
    private static final String SYSTEM_PROMPT = """
            You are a routing classifier and entity extractor for a banking AI assistant.
            Given the conversation history, classify the user's latest message AND extract \
            entity references in ONE JSON response.

            Intents:
            FETCH_DATA  - needs fresh data from banking systems (holdings, settlements, performance, etc.)
            FOLLOW_UP   - asks for clarification or explanation of data already shown in the conversation
            CLARIFY     - banking topic is clear but which client/fund is ambiguous
            CHITCHAT    - general conversation unrelated to banking data

            Reply with ONLY this JSON (no markdown, no prose):
            {
              "intent": "FETCH_DATA",
              "confidence": 0.95,
              "reasoning": "one-line explanation",
              "relationship_reference": "Whitman Family Office",
              "fund_reference": null,
              "ticker_references": [],
              "period": "QTD"
            }

            Entity extraction rules (for FETCH_DATA only — null/empty for other intents):
            - relationship_reference: the client/relationship name EXACTLY as stated, or null
            - fund_reference: the fund name or code EXACTLY as stated, or null
            - ticker_references: list of stock tickers mentioned, e.g. ["AAPL","MSFT"], or []
            - period: QTD/YTD/MTD/etc. — default "QTD" when not stated
            - NEVER invent identifiers; copy verbatim from the user's words

            Intent rules:
            - If the user mentions a client name (Whitman, Chen, Okafor, etc.) → FETCH_DATA
            - If prior assistant turn has banking data and user says "explain", "what does X mean", \
              "tell me more", "simplify" → FOLLOW_UP
            - If banking-related but no client mentioned and none in prior turns → CLARIFY
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

        HttpResponse<String> response = sendWithRetry(requestBody);

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM returned HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("LLM returned empty choices array");
        }
        String content = choices.path(0).path("message").path("content").asText();

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

        // Extract entity fields included in the same response (FETCH_DATA only)
        EntityBag entities = null;
        if (intent == Intent.FETCH_DATA) {
            String relRef = nullableText(parsed, "relationship_reference");
            String fundRef = nullableText(parsed, "fund_reference");
            String period = parsed.path("period").asText("QTD");
            if (period.isBlank()) period = "QTD";
            List<String> tickers = new ArrayList<>();
            JsonNode tickerNode = parsed.path("ticker_references");
            if (tickerNode.isArray()) {
                for (JsonNode t : tickerNode) {
                    String val = t.asText(null);
                    if (val != null && !val.isBlank()) tickers.add(val.toUpperCase());
                }
            }
            entities = EntityBag.extracted(relRef, fundRef, tickers, period);
        }

        return new IntentResult(intent, confidence, reasoning, entities);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        String v = n.asText(null);
        return (v == null || v.isBlank() || "null".equalsIgnoreCase(v)) ? null : v;
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

    private HttpResponse<String> sendWithRetry(String requestBody) throws Exception {
        String endpoint = baseUrl + "/chat/completions";
        int delayMs = 2_000;
        Exception lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            // Rebuild request each attempt — BodyPublishers.ofString() is one-shot.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            try {
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 429 || attempt == 3) return resp;
                log.warn("LLM rate limited (429), retry {}/3 in {}ms", attempt, delayMs);
            } catch (java.net.http.HttpTimeoutException e) {
                log.warn("LLM timeout (attempt {}/3), retrying in {}ms: {}", attempt, delayMs, e.getMessage());
                lastException = e;
            }
            Thread.sleep(delayMs);
            delayMs *= 2;
        }
        if (lastException != null) throw lastException;
        throw new IllegalStateException("unreachable");
    }
}
