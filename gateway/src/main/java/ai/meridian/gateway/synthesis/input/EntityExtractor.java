package ai.meridian.gateway.synthesis.input;

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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 1 — Extract.
 *
 * Calls Z.AI GLM with tool/function-calling to extract entity references verbatim
 * from the user's plain-English prompt.  The LLM produces ONLY what it sees — it
 * never invents relationship IDs or fund codes.
 *
 * Falls back to keyword parsing when the LLM call fails so the pipeline never
 * hard-crashes on a network or API error.
 */
@Service
public class EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);

    private static final String TOOL_NAME = "extract_entities";

    private static final String SYSTEM_PROMPT =
            "You extract entity references verbatim from banking queries. " +
            "Never invent identifiers. Extract ONLY what is mentioned. " +
            "If a field is not mentioned leave it null (or empty list for tickers). " +
            "Use 'QTD' as the default period when no time period is specified.";

    @Value("${meridian.llm.entity-extractor.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${meridian.llm.entity-extractor.api-key:}")
    private String apiKey;

    @Value("${meridian.llm.entity-extractor.model:gpt-4o-mini}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public EntityExtractor(RestTemplate restTemplate, ObjectMapper mapper) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
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

        // messages
        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", prompt);

        // tools definition
        ArrayNode tools = requestBody.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", TOOL_NAME);
        function.put("description", "Extract entity references verbatim from a banking query.");
        function.set("parameters", buildToolSchema());

        // force the model to call our tool
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

    /** JSON Schema for the extract_entities tool parameters. */
    private ObjectNode buildToolSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode relRef = properties.putObject("relationship_reference");
        relRef.put("type", "string");
        relRef.put("description", "The relationship or client name exactly as stated by the user, or null.");

        ObjectNode fundRef = properties.putObject("fund_reference");
        fundRef.put("type", "string");
        fundRef.put("description", "The fund name or code exactly as stated by the user, or null.");

        ObjectNode tickers = properties.putObject("ticker_references");
        tickers.put("type", "array");
        tickers.putObject("items").put("type", "string");
        tickers.put("description", "List of stock tickers mentioned, e.g. [\"AAPL\",\"MSFT\"].");

        ObjectNode period = properties.putObject("period");
        period.put("type", "string");
        period.put("description", "Reporting period mentioned (QTD, YTD, MTD, etc.). Default: QTD.");

        ArrayNode required = schema.putArray("required");
        required.add("ticker_references");
        required.add("period");

        return schema;
    }

    /** Parses a GLM response that contains a tool_calls array. */
    private EntityBag parseToolCallResponse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode message = root.path("choices").path(0).path("message");

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
            // Model responded with text instead of a tool call — parse content if available
            String content = message.path("content").asText(null);
            if (content != null && !content.isBlank()) {
                log.debug("Model returned text instead of tool call; attempting JSON parse of content.");
                return parseEntityJson(mapper.readTree(content));
            }
            log.warn("No tool_calls and no parseable content in LLM response; using keyword fallback.");
            throw new RuntimeException("No tool_calls in LLM response");
        }

        // Pick the first tool call whose name matches
        for (JsonNode tc : toolCalls) {
            String name = tc.path("function").path("name").asText("");
            if (TOOL_NAME.equals(name)) {
                String argsJson = tc.path("function").path("arguments").asText("{}");
                return parseEntityJson(mapper.readTree(argsJson));
            }
        }

        throw new RuntimeException("Expected tool '" + TOOL_NAME + "' not found in tool_calls");
    }

    private EntityBag parseEntityJson(JsonNode args) {
        String relRef  = nullableText(args, "relationship_reference");
        String fundRef = nullableText(args, "fund_reference");
        String period  = args.path("period").asText("QTD");
        if (period == null || period.isBlank()) period = "QTD";

        List<String> tickers = new ArrayList<>();
        JsonNode tickerNode = args.path("ticker_references");
        if (tickerNode.isArray()) {
            for (JsonNode t : tickerNode) {
                String ticker = t.asText(null);
                if (ticker != null && !ticker.isBlank()) tickers.add(ticker.toUpperCase());
            }
        }

        return EntityBag.extracted(relRef, fundRef, tickers, period);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        String v = n.asText(null);
        return (v == null || v.isBlank() || "null".equalsIgnoreCase(v)) ? null : v;
    }

    // ── Keyword fallback path ─────────────────────────────────────────────────

    /**
     * Minimal fallback extraction used when the LLM call fails.
     * Only extracts REL-/FND- patterns and tickers verbatim from the prompt.
     * Never maps names to strings — that is the entity registry's job.
     */
    private EntityBag extractViaKeywords(String prompt) {
        String lower = prompt.toLowerCase();

        String relRef = null;
        Pattern relPat = Pattern.compile("\\bREL-\\d+\\b");
        Matcher m = relPat.matcher(prompt);
        if (m.find()) relRef = m.group();

        String fundRef = null;
        Pattern fndPat = Pattern.compile("\\bFND-\\w+\\b");
        Matcher fm = fndPat.matcher(prompt);
        if (fm.find()) fundRef = fm.group();

        // rudimentary ticker extraction: uppercase 2–5 letter words that look like tickers
        List<String> tickers = new ArrayList<>();
        Pattern tickerPat = Pattern.compile("\\b([A-Z]{2,5})\\b");
        Matcher tm = tickerPat.matcher(prompt);
        while (tm.find()) {
            String candidate = tm.group(1);
            // exclude common English words used as tickers accidentally
            if (!List.of("QTD", "YTD", "MTD", "THE", "AND", "FOR", "WITH").contains(candidate)) {
                tickers.add(candidate);
            }
        }

        String period = "QTD";
        if (lower.contains("ytd")) period = "YTD";
        else if (lower.contains("mtd")) period = "MTD";
        else if (lower.contains("qtd")) period = "QTD";

        log.debug("Keyword fallback extracted: relRef={}, fundRef={}, tickers={}, period={}",
                relRef, fundRef, tickers, period);
        return EntityBag.extracted(relRef, fundRef, tickers, period);
    }
}
