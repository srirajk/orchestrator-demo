package ai.conduit.gateway.synthesis.input;

import ai.conduit.gateway.config.PromptLoader;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 1 — Extract.
 *
 * <p>Calls Z.AI GLM with tool/function-calling to extract entity references verbatim from the
 * user's plain-English prompt. The LLM produces ONLY what it sees — it never invents identifiers.
 *
 * <p>Fully manifest-driven: the tool schema, the per-field prompt rules and the keyword-fallback
 * patterns are all derived from the loaded {@code entity_types}. There is zero hardcoded entity
 * knowledge here. Falls back to keyword parsing when the LLM call fails so the pipeline never
 * hard-crashes on a network or API error.
 */
@Service
public class EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);

    private static final String TOOL_NAME = "extract_entities";

    @Value("${conduit.llm.entity-extractor.base-url:https://api.z.ai/api/paas/v4}")
    private String baseUrl;

    @Value("${conduit.llm.entity-extractor.api-key:}")
    private String apiKey;

    @Value("${conduit.llm.entity-extractor.model:glm-4.5-flash}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final DomainManifestStore manifestStore;
    private final PromptLoader promptLoader;
    /** Shared instruction-hierarchy fragment, rendered once for this call site's untrusted surface. */
    private final String instructionHierarchy;

    public EntityExtractor(RestTemplate restTemplate, ObjectMapper mapper,
                           DomainManifestStore manifestStore, PromptLoader promptLoader) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.manifestStore = manifestStore;
        this.promptLoader = promptLoader;
        this.instructionHierarchy = promptLoader.render("fragments/instruction-hierarchy",
                Map.of("surface", "the user message")).strip();
        // Constructor-time smoke render so a missing/typo'd resource fails Spring startup, even
        // though the field list is compiled per request from the (later-loaded) manifest.
        promptLoader.render("entity-extractor.system",
                Map.of("entity_field_rules", "smoke", "instruction_hierarchy", "smoke"));
    }

    /**
     * Extracts entity references from {@code prompt}.
     * Returns a best-effort {@link EntityBag} — never throws.
     */
    public EntityBag extract(String prompt) {
        try {
            return extractViaLlm(prompt);
        } catch (Exception e) {
            log.warn("LLM extraction failed ({}), falling back to keyword parser: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return extractViaKeywords(prompt);
        }
    }

    // ── LLM path ─────────────────────────────────────────────────────────────

    private EntityBag extractViaLlm(String prompt) throws Exception {
        String endpoint = baseUrl + "/chat/completions";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0);

        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content", buildSystemPrompt());
        messages.addObject().put("role", "user").put("content", prompt);

        ArrayNode tools = requestBody.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", TOOL_NAME);
        function.put("description", "Extract entity references verbatim from a user query.");
        function.set("parameters", buildToolSchema());

        ObjectNode toolChoice = requestBody.putObject("tool_choice");
        toolChoice.put("type", "function");
        toolChoice.putObject("function").put("name", TOOL_NAME);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        ResponseEntity<String> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(requestBody), headers),
                String.class);

        return parseToolCallResponse(response.getBody());
    }

    /**
     * Compiles the extraction instructions from the manifest's entity_types. The static prose
     * skeleton lives in {@code prompts/entity-extractor.system.md}; only the per-field rule lines
     * (manifest-compiled) and the shared instruction-hierarchy fragment are fed in as placeholders.
     */
    private String buildSystemPrompt() {
        StringBuilder rules = new StringBuilder();
        for (EntityType et : manifestStore.entityTypes()) {
            rules.append("- ").append(et.extractAs()).append(": ");
            if (et.isList()) {
                rules.append("list of ").append(et.display()).append(" mentioned, or [].");
            } else if (et.isLiteral()) {
                rules.append("the ").append(et.display()).append(" exactly as stated");
                if (et.defaultValue() != null && !et.defaultValue().isBlank()) {
                    rules.append("; default \"").append(et.defaultValue()).append("\" when not stated");
                }
                rules.append('.');
            } else {
                rules.append("the ").append(et.display())
                     .append(" name OR ID exactly as stated by the user, or null. Copy verbatim — do NOT normalize or look up.");
            }
            rules.append('\n');
        }
        return promptLoader.render("entity-extractor.system", Map.of(
                "entity_field_rules", rules.toString(),
                "instruction_hierarchy", instructionHierarchy)).strip();
    }

    /** JSON Schema for the extract_entities tool parameters, derived from entity_types. */
    private ObjectNode buildToolSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = mapper.createArrayNode();

        for (EntityType et : manifestStore.entityTypes()) {
            ObjectNode prop = properties.putObject(et.extractAs());
            if (et.isList()) {
                prop.put("type", "array");
                prop.putObject("items").put("type", "string");
                prop.put("description", "List of " + et.display() + " mentioned, e.g. as JSON strings.");
                required.add(et.extractAs());
            } else {
                prop.put("type", "string");
                if (et.isLiteral()) {
                    String desc = et.display();
                    if (et.defaultValue() != null && !et.defaultValue().isBlank()) {
                        desc += " (default: " + et.defaultValue() + ")";
                    }
                    prop.put("description", desc + ".");
                    required.add(et.extractAs());
                } else {
                    prop.put("description", "The " + et.display() + " exactly as stated by the user, or null.");
                }
            }
        }
        schema.set("required", required);
        return schema;
    }

    /** Parses a GLM response that contains a tool_calls array. */
    private EntityBag parseToolCallResponse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode message = root.path("choices").path(0).path("message");

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
            String content = message.path("content").asText(null);
            if (content != null && !content.isBlank()) {
                log.debug("Model returned text instead of tool call; attempting JSON parse of content.");
                return parseEntityJson(mapper.readTree(content));
            }
            log.warn("No tool_calls and no parseable content in LLM response; using keyword fallback.");
            throw new RuntimeException("No tool_calls in LLM response");
        }

        for (JsonNode tc : toolCalls) {
            String name = tc.path("function").path("name").asText("");
            if (TOOL_NAME.equals(name)) {
                String argsJson = tc.path("function").path("arguments").asText("{}");
                return parseEntityJson(mapper.readTree(argsJson));
            }
        }

        throw new RuntimeException("Expected tool '" + TOOL_NAME + "' not found in tool_calls");
    }

    /** Populates a generic EntityBag from the LLM args using the manifest entity_types. */
    private EntityBag parseEntityJson(JsonNode args) {
        Map<String, String> references = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();

        for (EntityType et : manifestStore.entityTypes()) {
            if (et.isList()) {
                List<String> values = new ArrayList<>();
                JsonNode node = args.path(et.extractAs());
                if (node.isArray()) {
                    for (JsonNode v : node) {
                        String s = v.asText(null);
                        // Copy verbatim — do NOT uppercase. Force-uppercasing list values corrupts
                        // case-sensitive references and contradicts the "copy verbatim" contract
                        // (matches IntentClassifier's deliberate fix for scalar/list references).
                        if (s != null && !s.isBlank()) values.add(s);
                    }
                }
                lists.put(et.extractAs(), List.copyOf(values));
            } else if (et.isLiteral()) {
                String value = nullableText(args, et.extractAs());
                if ((value == null || value.isBlank())
                        && et.defaultValue() != null && !et.defaultValue().isBlank()) {
                    value = et.defaultValue();
                }
                if (value != null && !value.isBlank()) references.put(et.extractAs(), value);
            } else { // resolvable
                String value = nullableText(args, et.extractAs());
                if (value != null) references.put(et.extractAs(), value);
            }
        }
        return EntityBag.of(references, lists);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        String v = n.asText(null);
        return (v == null || v.isBlank() || "null".equalsIgnoreCase(v)) ? null : v;
    }

    // ── Keyword fallback path ─────────────────────────────────────────────────

    /**
     * Minimal fallback extraction used when the LLM call fails. Uses each entity type's
     * {@code id_pattern} (from the manifest) to recognise literal IDs verbatim. Never maps
     * names to IDs — that is the resolver's job.
     */
    private EntityBag extractViaKeywords(String prompt) {
        Map<String, String> references = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();

        for (EntityType et : manifestStore.entityTypes()) {
            String pattern = et.idPattern();
            if (et.isList()) {
                List<String> values = new ArrayList<>();
                if (pattern != null && !pattern.isBlank()) {
                    Matcher m = Pattern.compile(pattern).matcher(prompt);
                    while (m.find()) values.add(m.group().toUpperCase());
                }
                lists.put(et.extractAs(), List.copyOf(values));
            } else {
                String found = null;
                if (pattern != null && !pattern.isBlank()) {
                    Matcher m = Pattern.compile(pattern).matcher(prompt);
                    if (m.find()) found = m.group();
                }
                if (et.isLiteral() && (found == null || found.isBlank())
                        && et.defaultValue() != null && !et.defaultValue().isBlank()) {
                    found = et.defaultValue();
                }
                if (found != null && !found.isBlank()) references.put(et.extractAs(), found);
            }
        }
        log.debug("Keyword fallback extracted: references={}, lists={}", references, lists);
        return EntityBag.of(references, lists);
    }
}
