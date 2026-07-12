package ai.conduit.gateway.resolver.service;

import ai.conduit.gateway.config.PromptLoader;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LlmRoutingRerankerClient implements RoutingRerankerClient {

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int requestTimeoutSeconds;

    /**
     * The reranker system prompt: a fully static skeleton ({@code routing-reranker.system.md}, no
     * domain placeholders) with the shared instruction-hierarchy fragment appended. Built once at
     * construction so a missing/typo'd resource fails Spring startup.
     */
    private final String systemPrompt;

    public LlmRoutingRerankerClient(
            ObjectMapper mapper,
            PromptLoader promptLoader,
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
        this.systemPrompt = promptLoader.prompt("routing-reranker.system").stripTrailing()
                + "\n\n"
                + promptLoader.render("fragments/instruction-hierarchy",
                        Map.of("surface", "the query and candidate descriptions")).strip();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Decision rerank(String queryText, List<RoutingCandidate> candidates) throws Exception {
        // Three outcomes, and the distinction between the last two matters enormously:
        // "abstain" means the request is ambiguous (clarify); "multiple" means the request is clear
        // but broader than any one capability (fan out). Conflating them made a well-specified
        // multi-part question come back as "I wasn't sure which services to consult." The prompt
        // text lives in prompts/routing-reranker.system.md (World-B: domain-agnostic skeleton).
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

        ObjectNode payload = mapper.createObjectNode()
                .put("model", model)
                .put("stream", false);
        // Reasoning models (gpt-5*, o-series) reject a non-default temperature — they support only the
        // default (1). Omit the sampling parameter for those; send the configured temperature otherwise.
        if (supportsCustomTemperature(model)) {
            payload.put("temperature", temperature);
        }
        payload.<ObjectNode>set("response_format", mapper.createObjectNode().put("type", "json_object"))
                .set("messages", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("role", "system")
                                .put("content", systemPrompt))
                        .add(mapper.createObjectNode()
                                .put("role", "user")
                                .put("content", mapper.writeValueAsString(userPayload))));
        String requestBody = mapper.writeValueAsString(payload);

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
        if ("multiple".equalsIgnoreCase(id)) {
            // The multi-facet answer names the explicit shortlist subset (one id per distinct facet).
            // We carry the LLM's SELECTION here; the embedding scores it was shown stay separate and are
            // never merged into the selection. Deduplication/in-shortlist/cap checks are the resolver's
            // job (RoutingRerankerClient contract), not the transport's.
            List<String> selected = new ArrayList<>();
            JsonNode ids = parsed.path("candidate_ids");
            if (ids.isArray()) {
                for (JsonNode node : ids) {
                    String candidateId = node.asText("").strip();
                    if (!candidateId.isEmpty()) {
                        selected.add(candidateId);
                    }
                }
            }
            return Decision.multiple(selected, reason);
        }
        return Decision.pick(id, reason);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * OpenAI reasoning models (the {@code gpt-5*} and {@code o1/o3/o4} families) reject any temperature
     * other than the default and 400 on {@code temperature != 1}. This is a transport quirk of the model
     * family, not domain knowledge — it gates only whether we emit the sampling parameter. Provider-neutral
     * models (GLM, {@code gpt-4.1*}, {@code gpt-4o*}) accept a custom temperature, so we keep sending it.
     */
    private static boolean supportsCustomTemperature(String model) {
        if (model == null) {
            return true;
        }
        String m = model.toLowerCase(java.util.Locale.ROOT).trim();
        return !(m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4"));
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
