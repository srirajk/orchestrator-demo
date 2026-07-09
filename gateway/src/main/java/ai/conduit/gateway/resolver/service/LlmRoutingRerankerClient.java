package ai.conduit.gateway.resolver.service;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Service
public class LlmRoutingRerankerClient implements RoutingRerankerClient {

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int requestTimeoutSeconds;

    public LlmRoutingRerankerClient(
            ObjectMapper mapper,
            @Value("${conduit.llm.routing-reranker.base-url:${conduit.llm.intent-classifier.base-url}}") String baseUrl,
            @Value("${conduit.llm.routing-reranker.api-key:${conduit.llm.intent-classifier.api-key:}}") String apiKey,
            @Value("${conduit.llm.routing-reranker.model:${conduit.llm.intent-classifier.model}}") String model,
            @Value("${conduit.llm.routing-reranker.temperature:0.0}") double temperature,
            @Value("${conduit.llm.routing-reranker.request-timeout-seconds:8}") int requestTimeoutSeconds) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Decision rerank(String queryText, List<RoutingCandidate> candidates) throws Exception {
        String systemPrompt = """
                You choose the single best capability for a user request from a bounded candidate set.
                Use only the candidate ids provided. If none genuinely match, choose "abstain".
                Pay close attention to exclusions, negation, and what the user asks to avoid.
                Return only JSON: {"candidate_id":"<one provided id or abstain>","reason":"one short reason"}.
                """;

        ObjectNode userPayload = mapper.createObjectNode();
        userPayload.put("query", queryText);
        ArrayNode candidatePayload = userPayload.putArray("candidates");
        for (RoutingCandidate candidate : candidates) {
            AgentManifest manifest = candidate.manifest();
            ObjectNode c = candidatePayload.addObject();
            c.put("id", manifest.agentId());
            c.put("name", nullToEmpty(manifest.name()));
            c.put("description", nullToEmpty(manifest.description()));
            c.put("score", candidate.score());
            ArrayNode skills = c.putArray("skills");
            if (manifest.skills() != null) {
                for (AgentManifest.Skill skill : manifest.skills()) {
                    ObjectNode s = skills.addObject();
                    s.put("name", nullToEmpty(skill.name()));
                    s.put("description", nullToEmpty(skill.description()));
                    ArrayNode examples = s.putArray("examples");
                    if (skill.examples() != null) {
                        skill.examples().stream()
                                .filter(e -> e != null && !e.isBlank())
                                .limit(2)
                                .forEach(examples::add);
                    }
                }
            }
        }

        String requestBody = mapper.writeValueAsString(mapper.createObjectNode()
                .put("model", model)
                .put("stream", false)
                .put("temperature", temperature)
                .<ObjectNode>set("response_format", mapper.createObjectNode().put("type", "json_object"))
                .set("messages", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("role", "system")
                                .put("content", systemPrompt))
                        .add(mapper.createObjectNode()
                                .put("role", "user")
                                .put("content", mapper.writeValueAsString(userPayload)))));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("routing reranker LLM returned HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("routing reranker LLM returned no choices");
        }

        String content = stripFences(choices.path(0).path("message").path("content").asText());
        JsonNode parsed = mapper.readTree(content);
        String id = parsed.path("candidate_id").asText("").strip();
        String reason = parsed.path("reason").asText("");
        if ("abstain".equalsIgnoreCase(id)) {
            return Decision.abstain(reason);
        }
        return Decision.pick(id, reason);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String stripFences(String content) {
        String stripped = content == null ? "" : content.strip();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceAll("```[a-zA-Z]*\\n?", "").replace("```", "").strip();
        }
        return stripped;
    }
}
