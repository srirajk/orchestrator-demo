package ai.conduit.gateway.domain.intent;

import ai.conduit.gateway.api.v1.chat.dto.Message;
import ai.conduit.gateway.config.PromptLoader;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionAligner;
import ai.conduit.gateway.synthesis.input.MentionSet;
import ai.conduit.gateway.synthesis.input.MentionSource;
import ai.conduit.gateway.synthesis.input.MentionSpan;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * Combined intent + entity prompt, COMPILED PER REQUEST from the effective manifest's
     * {@code entity_types} and a configurable domain-context opener (WORLD-B §4.1/§6). The
     * 4-intent taxonomy and the instruction-hierarchy guardrail are generic and fixed; the
     * domain framing and the entity-extraction rules/JSON keys are generated from the manifest
     * so the gateway carries no entity-type literals, ID patterns, or domain copy.
     *
     * <p>For FETCH_DATA the entity fields are populated; for other intents they are null/empty.
     * One LLM call (no separate EntityExtractor round-trip).
     */
    String buildSystemPrompt(List<EntityType> entityTypes, String domainContext) {
        // Per-entity JSON envelope fields (each prefixed ",\n  " so it appends after the reasoning
        // field and before the mentions field), compiled from the manifest — never hardcoded.
        StringBuilder jsonFields = new StringBuilder();
        for (EntityType et : entityTypes) {
            if (et.extractAs() == null) continue;
            jsonFields.append(",\n  \"").append(et.extractAs()).append("\": ").append(jsonExample(et));
        }

        // Per-entity extraction-rule lines (each terminated with "\n"), compiled from the manifest.
        StringBuilder rules = new StringBuilder();
        for (EntityType et : entityTypes) {
            if (et.extractAs() == null) continue;
            rules.append(extractionRule(et)).append("\n");
        }

        // Multi-reference provenance field list.
        String fieldList = entityTypes.stream()
                .filter(et -> et.extractAs() != null)
                .map(EntityType::extractAs)
                .collect(Collectors.joining(", "));

        // Compute whether any registered entity types are both required AND resolvable.
        // When all entities are optional (knowledge agents, policy Q&A, market research)
        // entity-less queries must be FETCH_DATA, not CLARIFY. Always fetch the fragment (so a
        // missing/typo'd resource fails fast whenever this runs) but only include it when relevant.
        boolean hasRequiredResolvable = entityTypes.stream()
                .anyMatch(et -> et.required() && et.isResolvable());
        String clarifyFragment = promptLoader.prompt("intent-classifier.clarify-rule");
        String clarifyRule = hasRequiredResolvable ? clarifyFragment : "";

        String hierarchy = promptLoader.render("fragments/instruction-hierarchy",
                Map.of("surface", "the conversation")).strip();

        return promptLoader.render("intent-classifier.system", Map.of(
                "domain_context", domainContext,
                "entity_json_fields", jsonFields.toString(),
                "entity_extraction_rules", rules.toString(),
                "entity_field_list", fieldList,
                "instruction_hierarchy", hierarchy,
                "clarify_rule", clarifyRule));
    }

    /** The JSON example value for an entity field, derived from its kind/default. */
    private static String jsonExample(EntityType et) {
        if (et.isList()) return "[]";
        if (et.isLiteral() && et.defaultValue() != null && !et.defaultValue().isBlank()) {
            return "\"" + et.defaultValue() + "\"";
        }
        String label = et.display() != null ? et.display() : et.extractAs();
        if (et.isResolvable()) {
            return "\"<the " + label + " name or ID exactly as stated, else null>\"";
        }
        return "null";
    }

    /** The extraction-rule line for an entity field, generated from its declaration. */
    private static String extractionRule(EntityType et) {
        String label = et.display() != null ? et.display() : et.extractAs();
        if (et.isList()) {
            return "- " + et.extractAs() + ": list of " + label + " mentioned (array of strings), or []";
        }
        if (et.isLiteral()) {
            String dflt = (et.defaultValue() != null && !et.defaultValue().isBlank())
                    ? " — default \"" + et.defaultValue() + "\" when not stated"
                    : " — null when not stated";
            return "- " + et.extractAs() + ": " + label + dflt;
        }
        // resolvable
        String patternHint = (et.idPattern() != null && !et.idPattern().isBlank())
                ? " An ID matching " + et.idPattern() + " is always this entity's ID — put it here."
                : "";
        return "- " + et.extractAs() + ": the " + label + " name OR ID EXACTLY as stated by the user."
                + patternHint + " Copy verbatim — do NOT normalize, expand, or look up the name."
                + " If the user wrote a NAME, output that name text; do NOT output an identifier code"
                + " you recall from an earlier answer — outputting a remembered identifier for a"
                + " user-typed name is forbidden. Use null if none is mentioned.";
    }

    private final ObjectMapper mapper;
    private final PromptLoader promptLoader;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final Timer classifyTimer;
    private final Tracer tracer;
    private final int maxRetries;
    private final int retryInitialDelayMs;
    private final int retryBackoffMultiplier;
    private final int requestTimeoutSeconds;
    private final DomainManifestStore manifestStore;
    private final String domainContext;
    /**
     * Language-generic back-reference (anaphor) tokens used by the stateless focal-entity carry:
     * a latest message that only pronoun-refers to an entity ("that", "them", …) inherits the last
     * focal reference from the client-sent window. These are function words, not domain knowledge
     * (World-B clean); configurable so the set is tunable without a rebuild (CLAUDE.md §5).
     */
    private final List<String> anaphoraTokens;
    private final double temperature;

    public IntentClassifier(
            ObjectMapper mapper,
            MeterRegistry meterRegistry,
            Tracer tracer,
            DomainManifestStore manifestStore,
            PromptLoader promptLoader,
            @Value("${conduit.llm.intent-classifier.base-url:https://api.z.ai/api/paas/v4}") String baseUrl,
            @Value("${conduit.llm.intent-classifier.api-key:}") String apiKey,
            @Value("${conduit.llm.intent-classifier.model:glm-4.5-flash}") String model,
            @Value("${conduit.assistant.domain-context:}") String domainContext,
            @Value("${conduit.chat.anaphora-tokens:that,them,they,their,theirs,it,its,this,these,those,one}") String anaphoraTokens,
            @Value("${conduit.llm.intent-classifier.temperature:0.0}") double temperature,
            // Intent classification runs SYNCHRONOUSLY before any SSE byte, so it gets its OWN
            // budget, independent of the conduit.llm.* budget used by the (slower, streamed)
            // synthesizer. The shared 3-retry × 25s + backoff budget could burn ~81s here; this
            // bounds the worst case to ~20s (10s × 1 retry) while leaving enough headroom that a
            // healthy ~2-4s call never falls back to the entity-less FETCH_DATA default.
            @Value("${conduit.llm.intent-classifier.max-retries:2}") int maxRetries,
            @Value("${conduit.llm.intent-classifier.retry-initial-delay-ms:200}") int retryInitialDelayMs,
            @Value("${conduit.llm.intent-classifier.retry-backoff-multiplier:2}") int retryBackoffMultiplier,
            @Value("${conduit.llm.intent-classifier.request-timeout-seconds:10}") int requestTimeoutSeconds) {
        this.mapper = mapper;
        this.manifestStore = manifestStore;
        this.promptLoader = promptLoader;
        this.domainContext = domainContext;
        this.anaphoraTokens = Arrays.stream(anaphoraTokens.split(","))
                .map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isBlank()).distinct().toList();
        this.temperature = temperature;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.tracer = tracer;
        this.maxRetries = maxRetries;
        this.retryInitialDelayMs = retryInitialDelayMs;
        this.retryBackoffMultiplier = retryBackoffMultiplier;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.classifyTimer = Timer.builder("intent.classify.duration")
                .description("Time spent classifying intent")
                .register(meterRegistry);
        // Constructor-time smoke render so a missing/typo'd prompt resource fails Spring startup,
        // even though the prompt is otherwise compiled per request from the effective manifest.
        buildSystemPrompt(List.of(), "smoke");
    }

    /**
     * The domain framing for the classifier prompt: the explicit {@code conduit.assistant.domain-context}
     * override when set non-blank, else the framing COMPOSED from the loaded domain manifests'
     * {@code domain_context} (World-B: domain copy is manifest-declared, never a Java literal). Computed
     * per request — after {@code DomainManifestStore} has loaded — so it reflects the live manifest set.
     */
    private String effectiveDomainContext() {
        return (domainContext != null && !domainContext.isBlank())
                ? domainContext
                : manifestStore.composedDomainContext();
    }

    /**
     * Classify the intent of the latest user message given the full conversation history.
     *
     * @throws IntentClassificationException if the LLM call fails after its retry budget is spent.
     *         The gateway never substitutes canned data for an unreachable LLM (CLAUDE.md §5): a
     *         fabricated FETCH_DATA intent carries an empty entity bag, the deterministic CLARIFY
     *         rule then fires, and a provider outage is presented to the user as "which client did
     *         you mean?". The caller surfaces the error instead.
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
                log.error("IntentClassifier failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
                span.setAttribute("error", true);
                span.recordException(e);
                throw new IntentClassificationException(
                        "Intent classification failed: " + e.getMessage(), e);
            } finally {
                span.end();
            }
        });
    }

    private IntentResult callLlm(List<Message> messages) throws Exception {
        // Build context from exactly the messages the client sent. The client owns
        // transcript/window/summary policy; the gateway does not hard-truncate here.
        String conversationContext = buildContext(messages);

        // The latest user message is the classification target. Entity extraction follows the
        // stateless latest-mention-wins rule over user-authored messages in the sent context.
        String latestUser = latestUserMessage(messages);
        if (!latestUser.isBlank()) {
            conversationContext = conversationContext
                + "\n\n--- LATEST USER MESSAGE (classify THIS; if it names an entity, that mention wins) ---\n"
                + latestUser;
        }

        // Compile the prompt from the manifest's entity_types + the effective domain context.
        List<EntityType> entityTypes = manifestStore.entityTypes();
        String systemPrompt = buildSystemPrompt(entityTypes, effectiveDomainContext());

        String requestBody = mapper.writeValueAsString(mapper.createObjectNode()
                .put("model", model)
                .put("stream", false)
                // Deterministic decode: classification + entity extraction are structured tasks, not
                // creative generation. Pinning the temperature makes the intent AND the focal-entity
                // extraction consistent turn-to-turn (a keyword-less/name-only follow-up must not
                // flake between fetching and clarifying). Configurable (CLAUDE.md §5).
                .put("temperature", temperature)
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("response_format",
                        mapper.createObjectNode().put("type", "json_object"))
                .set("messages", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("role", "system")
                                .put("content", systemPrompt))
                        .add(mapper.createObjectNode()
                                .put("role", "user")
                                .put("content", conversationContext))));

        // Create a child LLM span with OpenInference attributes so Phoenix can display
        // this as a real AI call (model name, prompt, response, token counts).
        Span llmSpan = tracer.spanBuilder("llm.call")
                .setAttribute("openinference.span.kind", "LLM")
                .setAttribute("llm.model_name", model)
                .setAttribute("llm.system", "openai")
                .setAttribute("llm.input_messages.0.message.role", "system")
                .setAttribute("llm.input_messages.0.message.content", systemPrompt)
                .setAttribute("llm.input_messages.1.message.role", "user")
                .setAttribute("llm.input_messages.1.message.content", truncate(conversationContext, 2000))
                .startSpan();

        HttpResponse<String> response;
        try {
            response = sendWithRetry(requestBody);
        } catch (Exception e) {
            llmSpan.recordException(e);
            llmSpan.end();
            throw e;
        }

        if (response.statusCode() != 200) {
            llmSpan.end();
            throw new RuntimeException("LLM returned HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            llmSpan.end();
            throw new RuntimeException("LLM returned empty choices array");
        }
        String content = choices.path(0).path("message").path("content").asText();

        // The model may wrap its JSON inside markdown fences — strip them
        content = content.strip();
        if (content.startsWith("```")) {
            content = content.replaceAll("```[a-z]*\\n?", "").replace("```", "").strip();
        }

        // Capture token usage for Phoenix cost/perf view
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode()) {
            long promptTokens = usage.path("prompt_tokens").asLong(0);
            long completionTokens = usage.path("completion_tokens").asLong(0);
            llmSpan.setAttribute("llm.token_count.prompt", promptTokens);
            llmSpan.setAttribute("llm.token_count.completion", completionTokens);
            llmSpan.setAttribute("llm.token_count.total", promptTokens + completionTokens);
        }
        llmSpan.setAttribute("llm.output_messages.0.message.role", "assistant");
        llmSpan.setAttribute("llm.output_messages.0.message.content", truncate(content, 1000));
        llmSpan.end();

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

        // Extract entity fields included in the same response for FETCH_DATA and FOLLOW_UP (the
        // latter so a follow-up needing fresh data can carry its focal entity into the bias-to-fetch
        // fallthrough). Both the JSON keys (entity_type.extract_as) and the kinds/defaults come from
        // the manifest — no hardcoded field names or defaults here.
        EntityBag entities = null;
        if (intent == Intent.FETCH_DATA || intent == Intent.FOLLOW_UP) {
            java.util.Map<String, String> references = new java.util.LinkedHashMap<>();
            java.util.Map<String, List<String>> lists = new java.util.LinkedHashMap<>();
            for (EntityType et : entityTypes) {
                String field = et.extractAs();
                if (field == null) continue;
                if (et.isList()) {
                    List<String> vals = new ArrayList<>();
                    JsonNode arr = parsed.path(field);
                    if (arr.isArray()) {
                        for (JsonNode t : arr) {
                            String val = t.asText(null);
                            // Copy verbatim — do NOT uppercase. Scalars are already kept verbatim;
                            // force-uppercasing list values corrupts case-sensitive references
                            // (e.g. tickers/IDs) and contradicts the "copy verbatim" contract.
                            if (val != null && !val.isBlank()) vals.add(val);
                        }
                    }
                    lists.put(field, vals);
                } else {
                    String val = nullableText(parsed, field);
                    if (et.isResolvable()) {
                        // Deterministic focal-reference derivation (World-B: the LLM never produces
                        // an id; a downstream resolver maps names to ids). Grounds the reference in
                        // the client-sent window — an id the user literally typed in the LATEST
                        // message, else the LLM's focal reference that the user actually typed
                        // (latest or a back-referenced earlier turn) — never a recalled/fabricated id.
                        val = deriveFocalReference(et, messages, val);
                    } else if ((val == null || val.isBlank())
                            && et.defaultValue() != null && !et.defaultValue().isBlank()) {
                        val = et.defaultValue();
                    }
                    if (val != null && !val.isBlank()) references.put(field, val);
                }
            }
            // Rich, span-aware provenance model. The focal-derived `references` scalar remains the
            // compatibility view (unchanged for every existing caller); the mention set additionally
            // carries multi-reference + gateway-derived spans for the grounding/masking stages.
            MentionSet mentionSet = buildMentionSet(parsed, entityTypes, messages, references, anaphoraTokens);
            entities = EntityBag.of(references, lists, mentionSet);
        }

        return new IntentResult(intent, confidence, reasoning, entities);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNull() || n.isMissingNode()) return null;
        String v = n.asText(null);
        return (v == null || v.isBlank() || "null".equalsIgnoreCase(v)) ? null : v;
    }

    /** Collapses runs of non-alphanumerics to single spaces — used for anaphor detection only. */
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    /**
     * Builds the span-aware {@link MentionSet} from the LLM's {@code mentions} array plus the
     * deterministically-derived focal scalar. Package-private and static so it is unit-testable
     * without an LLM round-trip. World-B: every input is a manifest key ({@code extract_as} →
     * {@code key}), the user's verbatim words, a message index, or a generic source enum.
     *
     * <p>Contract preserved for downstream: the focal reference for each key is recorded FIRST, so
     * {@link MentionSet#compatScalar} reproduces the legacy focal scalar in {@code focalReferences}
     * (latest explicit, else carried anaphora); the LLM's extra references add multi-reference data
     * without displacing the focal. Unknown fields and blank text are dropped generically.
     *
     * @param parsed          the parsed LLM JSON (may or may not carry a {@code mentions} array)
     * @param entityTypes     the manifest entity types (source of {@code extract_as} → {@code key})
     * @param messages        the client-sent window (span/message-index derivation anchor)
     * @param focalReferences {@code extract_as} → focal scalar, as {@code deriveFocalReference} chose
     * @param anaphoraTokens  configured back-reference tokens (source disambiguation on a miss)
     */
    static MentionSet buildMentionSet(JsonNode parsed, List<EntityType> entityTypes,
                                      List<Message> messages, java.util.Map<String, String> focalReferences,
                                      List<String> anaphoraTokens) {
        java.util.Map<String, EntityType> byExtractAs = new java.util.LinkedHashMap<>();
        for (EntityType et : entityTypes) {
            if (et.extractAs() != null) byExtractAs.putIfAbsent(et.extractAs(), et);
        }
        int latestIdx = latestUserIndex(messages);
        List<Mention> out = new ArrayList<>();

        // 1. Record the deterministic focal reference for each key FIRST (wins same-turn compat ties).
        for (EntityType et : entityTypes) {
            if (et.key() == null || et.extractAs() == null) continue;
            String focal = focalReferences.get(et.extractAs());
            if (focal == null || focal.isBlank()) continue;
            out.add(makeMention(et.key(), et.extractAs(), focal, messages, latestIdx, anaphoraTokens, null));
        }

        // 2. Add the LLM-declared references (multi-name / multi-id), skipping exact focal duplicates.
        JsonNode arr = parsed.path("mentions");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                String entity = nullableText(node, "entity");
                String text = nullableText(node, "text");
                if (entity == null || text == null) continue;
                EntityType et = byExtractAs.get(entity);
                if (et == null || et.key() == null) continue;   // unknown field → drop (generic)
                if (isDuplicate(out, et.key(), text)) continue;
                String srcHint = nullableText(node, "source");
                out.add(makeMention(et.key(), et.extractAs(), text, messages, latestIdx, anaphoraTokens, srcHint));
            }
        }
        return new MentionSet(out);
    }

    /**
     * Resolves one verbatim reference to a {@link Mention}: aligns it in the latest user message
     * (→ EXPLICIT with a span), else in an earlier user turn newest-first (→ ANAPHORA with a span);
     * on an alignment miss it keeps the mention with no span, choosing the source from the LLM hint
     * or whether the latest message is anaphoric. The span is ALWAYS gateway-derived here.
     */
    private static Mention makeMention(String entityKey, String extractAs, String verbatim,
                                       List<Message> messages, int latestIdx,
                                       List<String> anaphoraTokens, String srcHint) {
        if (latestIdx >= 0) {
            MentionSpan span = MentionAligner.align(messages.get(latestIdx).content(), verbatim);
            if (span != null) {
                return new Mention(entityKey, extractAs, verbatim, latestIdx, span, MentionSource.EXPLICIT);
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (i == latestIdx) continue;
            Message m = messages.get(i);
            if (m == null || !"user".equalsIgnoreCase(m.role())) continue;
            MentionSpan span = MentionAligner.align(m.content(), verbatim);
            if (span != null) {
                return new Mention(entityKey, extractAs, verbatim, i, span, MentionSource.ANAPHORA);
            }
        }
        // Alignment miss — no span, never guessed. Explicit if the LLM said so or the latest message
        // is not a bare back-reference; anaphora otherwise (owning turn unknown → index -1).
        boolean explicitHint = "explicit".equalsIgnoreCase(srcHint);
        boolean latestAnaphoric = latestIdx >= 0 && isAnaphoric(messages.get(latestIdx).content(), anaphoraTokens);
        if (explicitHint || (srcHint == null && !latestAnaphoric)) {
            return new Mention(entityKey, extractAs, verbatim, latestIdx, null, MentionSource.EXPLICIT);
        }
        return new Mention(entityKey, extractAs, verbatim, -1, null, MentionSource.ANAPHORA);
    }

    private static boolean isDuplicate(List<Mention> out, String entityKey, String verbatim) {
        String nv = MentionAligner.normalizeNeedle(verbatim);
        for (Mention m : out) {
            if (java.util.Objects.equals(m.entityKey(), entityKey)
                    && MentionAligner.normalizeNeedle(m.verbatimText()).equals(nv)) {
                return true;
            }
        }
        return false;
    }

    /** Index of the latest non-blank user message in the window, or -1. */
    private static int latestUserIndex(List<Message> messages) {
        if (messages == null) return -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m != null && "user".equalsIgnoreCase(m.role())
                    && m.content() != null && !m.content().isBlank()) {
                return i;
            }
        }
        return -1;
    }

    /** True when the text back-references an entity via a configured anaphor token (static form). */
    private static boolean isAnaphoric(String text, List<String> anaphoraTokens) {
        if (text == null || text.isBlank() || anaphoraTokens == null || anaphoraTokens.isEmpty()) return false;
        String norm = " " + NON_ALNUM.matcher(text.toLowerCase()).replaceAll(" ").trim() + " ";
        for (String tok : anaphoraTokens) {
            if (norm.contains(" " + tok + " ")) return true;
        }
        return false;
    }

    private String buildContext(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return "(empty conversation)";
        return messages.stream()
                .filter(m -> m.content() != null && !m.content().isBlank())
                .map(m -> m.role().toUpperCase() + ": " + m.content())
                .collect(Collectors.joining("\n"));
    }

    private static boolean userMessagesContain(List<Message> messages, String value) {
        if (messages == null || value == null || value.isBlank()) return false;
        String needle = value.toLowerCase();
        return messages.stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .map(Message::content)
                .filter(c -> c != null && !c.isBlank())
                .anyMatch(c -> c.toLowerCase().contains(needle));
    }

    /**
     * Derives the focal reference for a resolvable entity deterministically from the client-sent
     * window (stateless — no server-side memory; re-computed every turn). Precedence:
     * <ol>
     *   <li>An identifier the user literally typed in the LATEST message (an {@code id_pattern}
     *       match) — the user stated it, so it is not a fabricated/recalled id. Deterministic
     *       backstop for a bare id-only follow-up the LLM drops (e.g. "and for REL-00099?").</li>
     *   <li>A reference the user NAMES in the LATEST message (the model's value shares a distinctive
     *       word with the current turn). Naming an entity NOW makes it the focal entity: it
     *       supersedes any older one and wins focus for the next anaphor.</li>
     *   <li>RECENCY: the latest message back-references an entity ("that", "them", "their", …)
     *       without naming a new one. The focal entity is then the MOST-RECENTLY-NAMED entity in the
     *       window — decided on conversation structure, NOT on the model's pick over the whole
     *       history (which can latch onto a SUPERSEDED entity). This runs BEFORE the grounded-value
     *       fallback so a pronoun can never bind to an older focus the model happened to re-mention.</li>
     *   <li>Else the model's reference when it appears verbatim in some USER message (a non-anaphoric
     *       follow-up whose named subject the model carried forward), dropping an id it merely
     *       recalled from assistant text (World-B invariant 6).</li>
     *   <li>Else null → the deterministic coverage clarification fires downstream.</li>
     * </ol>
     * All inputs are manifest-declared ({@code id_pattern}, {@code extract_as}) or language-generic
     * (anaphora tokens) — no domain knowledge in the gateway.
     */
    private String deriveFocalReference(EntityType et, List<Message> messages, String llmValue) {
        String latest = latestUserMessage(messages);
        Pattern idp = manifestStore.compiledIdPattern(et.idPattern());
        boolean grounded = llmValue != null && !llmValue.isBlank();

        // 1. Explicit identifier typed in the LATEST user message (user stated it — never fabricated).
        //    Deterministic backstop for a bare id-only follow-up the LLM drops ("and for REL-00099?").
        if (idp != null && !latest.isBlank()) {
            Matcher m = idp.matcher(latest);
            if (m.find()) return m.group();
        }
        // 2. A reference the user NAMES in THIS turn — the model's value shares a distinctive word
        //    with the latest message ("Calderon Trust's holdings", "settlement for Whitman", "the
        //    trust one"). Naming an entity now makes it the focal entity: it supersedes any older
        //    one and wins the focus for the next anaphor. This is the "explicit name wins the focus"
        //    half of the recency rule, evaluated at the naming turn itself.
        if (grounded && sharesWord(latest, llmValue)) {
            return llmValue;
        }
        // 3. RECENCY carry: the latest message back-references an entity but NAMES no new one this
        //    turn — either a bare anaphor ("and their goals?") or a pronoun the model resolved from
        //    history to a possibly SUPERSEDED entity. Bind to the most-recently-named focal entity,
        //    derived from conversation structure, and let it OVERRIDE the model's grounded pick. This
        //    is the "a subsequent anaphor inherits the most-recent focal entity" half of the recency
        //    rule; it must precede the grounded-value fallback (step 4) so a pronoun after an explicit
        //    name can never bind to an OLDER focus the model happened to re-mention. Deterministic,
        //    id_pattern-based, grounded entirely in the transcript; the coverage CHECK stays the gate:
        //    (a) NAMED subject — match the distinctive NAME tokens the transcript associates with each
        //        id (parsed from the "<Name> (<id>)" the system emitted earlier) against the latest
        //        message. (Defensive; a bare pronoun matches nothing.)
        //    (b) BARE back-reference — carry the id of the most recent single-entity turn (skips
        //        multi-id compare/clarification messages) = the entity most recently in focus.
        if (isAnaphoric(latest)) {
            String named = focalIdByNameMatch(idp, messages, latest);
            if (named != null) return named;
            String carried = lastFocalSingleId(idp, messages);
            if (carried != null) return carried;
        }
        // 4. Otherwise keep the model's reference when it appears verbatim in a USER message (World-B
        //    invariant 6): a non-anaphoric follow-up whose named subject the model carried forward. It
        //    drops an identifier the model RECALLED from assistant text (the model must never emit an
        //    id; a downstream resolver maps names to ids).
        if (grounded && userMessagesContain(messages, llmValue)) {
            return llmValue;
        }
        if (grounded) {
            log.debug("Dropping ungrounded reference '{}' for field '{}' — absent from user-authored messages",
                    llmValue, et.extractAs());
        }
        // 5. Last resort: name-match the latest message against the transcript's name→id associations.
        String named = focalIdByNameMatch(idp, messages, latest);
        if (named != null) return named;
        // 6. Nothing safely grounded → deterministic coverage clarification downstream.
        return null;
    }

    /**
     * True when the {@code reference} the model extracted shares a distinctive word (a token of
     * length ≥ 4, matched case-insensitively on word boundaries) with the {@code latest} user
     * message — i.e. the user NAMED that entity in the current turn, so the extraction is anchored in
     * this turn's text rather than inferred from history. Length-gated to skip function words so a
     * pronoun-only turn ("and their goals?") never counts as naming the model's picked entity.
     * Language-generic — no domain knowledge.
     */
    private static boolean sharesWord(String latest, String reference) {
        if (latest == null || latest.isBlank() || reference == null || reference.isBlank()) return false;
        String latestLower = " " + latest.toLowerCase().replaceAll("[^a-z0-9]+", " ") + " ";
        for (String tok : reference.toLowerCase().split("[^a-z0-9]+")) {
            if (tok.length() >= 4 && latestLower.contains(" " + tok + " ")) return true;
        }
        return false;
    }

    /**
     * Matches the LATEST message's tokens against the NAME tokens the transcript associates with
     * each id. For every id occurrence in the window, the capitalized words (length ≥ 4)
     * immediately preceding it — i.e. the entity name in the "&lt;Name&gt; (&lt;id&gt;)" the system
     * rendered earlier — are its name tokens. Returns the id whose name tokens the latest message
     * mentions (case-insensitive), or null. Distinctive-name matching only, so generic facet words
     * ("holdings", "cash") cannot cross-match a different entity.
     */
    private String focalIdByNameMatch(Pattern idp, List<Message> messages, String latest) {
        if (idp == null || messages == null || latest == null || latest.isBlank()) return null;
        String latestLower = " " + latest.toLowerCase().replaceAll("[^a-z0-9]+", " ") + " ";
        String bestId = null;
        int bestScore = 0;
        // Newest-first so a more recent association wins ties.
        for (int i = messages.size() - 1; i >= 0; i--) {
            String content = messages.get(i).content();
            if (content == null || content.isBlank()) continue;
            Matcher m = idp.matcher(content);
            while (m.find()) {
                String id = m.group();
                int start = Math.max(0, m.start() - 48);
                String before = content.substring(start, m.start());
                int score = 0;
                for (Matcher w = CAP_WORD.matcher(before); w.find(); ) {
                    String tok = w.group().toLowerCase();
                    if (latestLower.contains(" " + tok + " ")) score++;
                }
                if (score > bestScore) { bestScore = score; bestId = id; }
            }
        }
        return bestScore > 0 ? bestId : null;
    }

    /** Capitalized words of length ≥ 4 — a cheap proper-noun (entity name) heuristic. */
    private static final Pattern CAP_WORD = Pattern.compile("\\b[A-Z][A-Za-z]{3,}\\b");

    /**
     * The id of the most recent single-entity turn in the window: the newest message that contains
     * exactly ONE distinct id (skips multi-id compare answers and clarification lists), or null.
     * Used to carry focus across a bare back-reference.
     */
    private String lastFocalSingleId(Pattern idp, List<Message> messages) {
        if (idp == null || messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            String content = messages.get(i).content();
            if (content == null || content.isBlank()) continue;
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            for (Matcher m = idp.matcher(content); m.find(); ) ids.add(m.group());
            if (ids.size() == 1) return ids.iterator().next();
        }
        return null;
    }

    /** True when the text back-references an entity via a configured anaphor token. */
    private boolean isAnaphoric(String text) {
        if (text == null || text.isBlank() || anaphoraTokens.isEmpty()) return false;
        String norm = " " + text.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim() + " ";
        for (String tok : anaphoraTokens) {
            if (norm.contains(" " + tok + " ")) return true;
        }
        return false;
    }

    /** The most recent user message text (the extraction target), or "" if none. */
    private String latestUserMessage(List<Message> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.content() != null && !m.content().isBlank() && "user".equalsIgnoreCase(m.role())) {
                return m.content().strip();
            }
        }
        return "";
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private HttpResponse<String> sendWithRetry(String requestBody) throws Exception {
        String endpoint = baseUrl + "/chat/completions";
        int delayMs = retryInitialDelayMs;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // Rebuild request each attempt — BodyPublishers.ofString() is one-shot.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            try {
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 429 || attempt == maxRetries) return resp;
                log.warn("LLM rate limited (429), retry {}/{} in {}ms", attempt, maxRetries, delayMs);
            } catch (java.net.http.HttpTimeoutException e) {
                log.warn("LLM timeout (attempt {}/{}), retrying in {}ms: {}", attempt, maxRetries, delayMs, e.getMessage());
                lastException = e;
            }
            Thread.sleep(delayMs);
            delayMs *= retryBackoffMultiplier;
        }
        if (lastException != null) throw lastException;
        throw new IllegalStateException("unreachable");
    }
}
