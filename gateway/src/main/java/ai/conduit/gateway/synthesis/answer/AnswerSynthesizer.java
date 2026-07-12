package ai.conduit.gateway.synthesis.answer;

import ai.conduit.gateway.api.v1.chat.dto.Message;
import ai.conduit.gateway.config.PromptLoader;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.registry.service.AgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synthesizes a grounded, streamed answer from agent outputs using Z.AI GLM.
 *
 * <p>Design principles:
 * <ul>
 *   <li>Agent outputs are the only ground truth.  The LLM is given them as delimited
 *       DATA blocks — never as instructions.</li>
 *   <li>Missing agents are declared explicitly; data is never silently omitted.</li>
 *   <li>The numeric grounding check (post-synthesis) logs a warning if the answer
 *       references a number that does not appear in any agent output — it is a
 *       diagnostic, not a hard block.</li>
 * </ul>
 */
@Service
public class AnswerSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(AnswerSynthesizer.class);

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:[.,]\\d+)*\\b");

    /**
     * An uncovered client withheld from a multi-entity COMPARE answer. Attribution is the user's own
     * verbatim words plus the manifest coverage-denial copy — never the resolver's canonical name/id, so
     * the disclosure about a withheld client is identical to asking about that client alone (S4 parity).
     */
    public record WithheldEntity(String userVerbatim, String denialCopy) {}

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean showReasoning;
    private final HttpClient httpClient;
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final int maxRetries;
    private final int retryInitialDelayMs;
    private final int retryBackoffMultiplier;
    private final int requestTimeoutSeconds;
    private final int streamIdleTimeoutSeconds;
    private final AgentRegistry registry;
    private final GroundedFigureRenderer figureRenderer;
    private final GroundedFigureValidator figureValidator;

    /**
     * Grounding/system prompt, built from a configurable display name. The DATA-block grounding
     * rules and the instruction hierarchy are domain-invariant and kept verbatim; only the
     * assistant identity is parameterized so the gateway carries no domain copy (WORLD-B §6).
     */
    private final String systemPrompt;
    /** Explicit domain-context override (conduit.assistant.domain-context); blank → compose from manifests. */
    private final String domainContextOverride;
    /** Source of the composed domain framing when no explicit override is set (World-B: manifest copy). */
    private final DomainManifestStore manifestStore;
    /** Prompt renderer, retained so the history-only prompt is rendered PER REQUEST (see historySystemPrompt()). */
    private final PromptLoader promptLoader;
    /** Pre-rendered instruction-hierarchy fragment for the history-only prompt (domain-invariant, static). */
    private final String historyInstructionHierarchy;
    /** The GROUNDED FIGURES instruction paragraph (static), appended into the user-message data. */
    private final String figuresBlock;

    public AnswerSynthesizer(
            ObjectMapper mapper,
            AgentRegistry registry,
            GroundedFigureRenderer figureRenderer,
            GroundedFigureValidator figureValidator,
            Tracer tracer,
            MeterRegistry meterRegistry,
            PromptLoader promptLoader,
            DomainManifestStore manifestStore,
            @Value("${conduit.llm.synthesizer.base-url:https://api.z.ai/api/paas/v4}") String baseUrl,
            @Value("${conduit.llm.synthesizer.api-key:}") String apiKey,
            @Value("${conduit.llm.synthesizer.model:glm-4.6}") String model,
            @Value("${conduit.llm.show-reasoning:false}") boolean showReasoning,
            @Value("${conduit.assistant.display-name:Meridian}") String displayName,
            @Value("${conduit.assistant.domain-context:}") String domainContext,
            @Value("${conduit.llm.max-retries:3}") int maxRetries,
            @Value("${conduit.llm.retry-initial-delay-ms:2000}") int retryInitialDelayMs,
            @Value("${conduit.llm.retry-backoff-multiplier:2}") int retryBackoffMultiplier,
            // Per-request timeout (time to receive response headers) and inter-token idle
            // deadline — without these a stalled LLM pins the synthesis thread + SSE emitter
            // until the 120s emitter timeout. Defaults fall back to the shared LLM budget.
            @Value("${conduit.llm.synthesizer.request-timeout-seconds:${conduit.llm.request-timeout-seconds:30}}") int requestTimeoutSeconds,
            @Value("${conduit.llm.synthesizer.stream-idle-timeout-seconds:30}") int streamIdleTimeoutSeconds) {
        this.mapper = mapper;
        this.registry = registry;
        this.figureRenderer = figureRenderer;
        this.figureValidator = figureValidator;
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.showReasoning = showReasoning;
        this.domainContextOverride = domainContext;
        this.manifestStore = manifestStore;
        this.promptLoader = promptLoader;
        this.maxRetries = maxRetries;
        this.retryInitialDelayMs = retryInitialDelayMs;
        this.retryBackoffMultiplier = retryBackoffMultiplier;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds;
        // System prompts and the GROUNDED FIGURES instruction live under prompts/*.md; the domain-
        // bearing tokens (display name, domain context) arrive via placeholders (World-B). Rendered
        // once at construction so a missing/typo'd resource fails Spring startup.
        this.systemPrompt = promptLoader.render("answer-synthesizer.system", Map.of(
                "display_name", displayName,
                "instruction_hierarchy", promptLoader.render("fragments/instruction-hierarchy",
                        Map.of("surface", "the DATA sections")).strip())).strip();
        this.historyInstructionHierarchy = promptLoader.render("fragments/instruction-hierarchy",
                Map.of("surface", "the conversation")).strip();
        // Constructor-time smoke render so a missing/typo'd prompt resource fails Spring startup. The
        // REAL render happens per request in historySystemPrompt() with the effective domain context:
        // composedDomainContext() must not be read at construction (DomainManifestStore's @PostConstruct
        // load() may not have run yet), so a neutral placeholder is used here purely as a fail-fast probe.
        promptLoader.render("answer-synthesizer-history.system", Map.of(
                "domain_context", "smoke",
                "instruction_hierarchy", historyInstructionHierarchy));
        this.figuresBlock = promptLoader.prompt("answer-synthesizer.figures-block");
        // Pin HTTP/1.1, as every sibling client does (IntentClassifier, ClarificationComposer,
        // LlmRoutingRerankerClient, McpAdapter). java.net.http.HttpClient defaults to HTTP/2, so on a
        // cleartext http:// endpoint it sends `Upgrade: h2c`. Providers that mishandle that header
        // answer 404 (or 421, as uvicorn does — see McpAdapter), and the streaming answer path — the
        // most user-visible path in the product — dies. Invisible against OpenAI, which negotiates
        // HTTP/2 cleanly over TLS+ALPN; fatal against a local or self-hosted HTTP/1.1 provider. The
        // synthesizer is meant to be provider-swappable per call site, so it must not depend on that.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Build a synthesis prompt from the agent results, stream the LLM response
     * as OpenAI SSE chunks through {@code emitter}, then run the numeric grounding check.
     *
     * <p>Never throws — errors are logged and the emitter is completed with a fallback.
     */
    public String synthesize(List<NodeResult> results, String originalPrompt,
                           List<Message> history, SseEmitter emitter) {
        return synthesize(results, originalPrompt, history, emitter, null, List.of());
    }

    public String synthesize(List<NodeResult> results, String originalPrompt,
                           List<Message> history, SseEmitter emitter, String completionIdOverride) {
        return synthesize(results, originalPrompt, history, emitter, completionIdOverride, List.of());
    }

    /**
     * @param withheldDomains domains referenced by the request but pruned by the structural
     *                        entitlement gate (outside the caller's access). Rendered as WITHHELD
     *                        sections so a mixed in/out-of-access ask fulfills the accessible part
     *                        and honestly states what was withheld. Domain labels come from the
     *                        agent manifests — the gateway holds no domain literal (World B).
     */
    public String synthesize(List<NodeResult> results, String originalPrompt,
                           List<Message> history, SseEmitter emitter, String completionIdOverride,
                           List<String> withheldDomains) {
        return synthesize(results, originalPrompt, history, emitter, completionIdOverride, withheldDomains, -1L);
    }

    /**
     * @param requestStartMillis epoch-millis when the chat request entered the gateway, used to
     *                           record request-level TTFT (conduit_time_to_first_token_seconds) at
     *                           the first content delta. Pass {@code -1} to skip the recording.
     */
    public String synthesize(List<NodeResult> results, String originalPrompt,
                           List<Message> history, SseEmitter emitter, String completionIdOverride,
                           List<String> withheldDomains, long requestStartMillis) {
        return synthesize(results, originalPrompt, history, emitter, completionIdOverride,
                withheldDomains, requestStartMillis, Map.of(), List.of(), null);
    }

    /**
     * Multi-entity COMPARE overload. Two entity groups can invoke the SAME agent, so DATA blocks and
     * figures must be attributed per client and a withheld (uncovered) client must be stated with the
     * user's own words plus the manifest denial copy — never the resolver's canonical name.
     *
     * @param entityLabels    {@code NodeResult.nodeId() -> "<canonicalId> \"<canonicalName>\""} for each
     *                        entity-qualified served node; empty on every non-COMPARE request.
     * @param withheldEntities the uncovered clients: each carries the user's verbatim mention + the exact
     *                        same manifest coverage-denial copy a standalone ask for that client streams.
     * @param cappedNote      a domain-neutral note (or {@code null}) telling the model the fan-out was
     *                        capped so it states which entities it compared and that the rest can be re-asked.
     */
    public String synthesize(List<NodeResult> results, String originalPrompt,
                           List<Message> history, SseEmitter emitter, String completionIdOverride,
                           List<String> withheldDomains, long requestStartMillis,
                           Map<String, String> entityLabels, List<WithheldEntity> withheldEntities,
                           String cappedNote) {
        String completionId = completionIdOverride != null
                ? completionIdOverride
                : "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = System.currentTimeMillis() / 1_000L;

        // OpenInference LLM span so Phoenix shows this as an AI synthesis call.
        Span llmSpan = tracer.spanBuilder("llm.synthesize")
                .setAttribute("openinference.span.kind", "LLM")
                .setAttribute("llm.model_name", model)
                .setAttribute("llm.system", "openai")
                .setAttribute("llm.input_messages.0.message.role", "system")
                .setAttribute("llm.input_messages.0.message.content", systemPrompt)
                .startSpan();

        boolean emitterDone = false;
        try {
            // Emit the role delta immediately so the browser stream appears alive while
            // the LLM call is in flight. This replaces the former up-front emit in
            // ChatService.handleChat which caused a duplicate-ID issue when
            // streamTextAndComplete also sent its own role delta.
            try {
                emitter.send(SseEmitter.event().data(roleDelta(completionId, created, mapper)));
            } catch (Exception ignored) { /* client disconnected before synthesis */ }

            List<GroundedFigure> groundedFigures = figureRenderer.render(results, registry::find);
            llmSpan.setAttribute("conduit.grounding.figure_count", groundedFigures.size());
            String userContent = buildUserContent(results, withheldDomains, groundedFigures,
                    entityLabels, withheldEntities, cappedNote);
            String requestBody = buildRequestBody(userContent, originalPrompt, history);

            log.debug("AnswerSynthesizer: calling {} model={} agents={}",
                    baseUrl, model, results.size());

            // Stream the LLM response; accumulate text for grounding check and span output.
            StringBuilder synthesizedText = new StringBuilder();
            long[] tokenCounts = new long[3]; // [prompt, completion, total]
            if (groundedFigures.isEmpty()) {
                llmSpan.setAttribute("conduit.grounding.verdict", "no-figures");
                llmSpan.setAttribute("conduit.grounding.error_count", 0);
                streamFromLlm(requestBody, emitter, completionId, created, synthesizedText,
                        showReasoning, tokenCounts, requestStartMillis);
            } else {
                String draft = collectFromLlm(requestBody, tokenCounts);
                String finalText = ensureFiguresMentioned(substituteFigures(draft, groundedFigures), groundedFigures);
                GroundedFigureValidator.ValidationResult validation =
                        figureValidator.validate(finalText, groundedFigures);
                llmSpan.setAttribute("conduit.grounding.verdict",
                        validation.ok() ? "all-grounded" : "failed");
                llmSpan.setAttribute("conduit.grounding.error_count", validation.errors().size());
                if (!validation.ok()) {
                    log.warn("Grounded figure validation failed; serving deterministic fallback: {}",
                            validation.errors());
                    llmSpan.setAttribute("conduit.grounding.draft_verdict", "failed");
                    llmSpan.setAttribute("conduit.grounding.fallback", true);
                    finalText = deterministicFigureFallback(originalPrompt, groundedFigures);
                    GroundedFigureValidator.ValidationResult fallbackValidation =
                            figureValidator.validate(finalText, groundedFigures);
                    llmSpan.setAttribute("conduit.grounding.verdict",
                            fallbackValidation.ok() ? "all-grounded" : "failed");
                    llmSpan.setAttribute("conduit.grounding.error_count", fallbackValidation.errors().size());
                }
                synthesizedText.append(finalText);
                emitter.send(SseEmitter.event().data(contentDelta(completionId, created, finalText, mapper)));
            }

            // Set output and token count on span after streaming completes.
            String output = synthesizedText.toString();
            if (output.length() > 2000) output = output.substring(0, 2000) + "…";
            llmSpan.setAttribute("llm.output_messages.0.message.role", "assistant");
            llmSpan.setAttribute("llm.output_messages.0.message.content", output);
            if (tokenCounts[2] > 0) {
                llmSpan.setAttribute("llm.token_count.prompt", tokenCounts[0]);
                llmSpan.setAttribute("llm.token_count.completion", tokenCounts[1]);
                llmSpan.setAttribute("llm.token_count.total", tokenCounts[2]);
            }
            llmSpan.end();

            // Send stop chunk and [DONE].
            emitter.send(SseEmitter.event().data(stopDelta(completionId, created, mapper)));
            emitter.send(SseEmitter.event().data(" [DONE]"));
            emitter.complete();
            emitterDone = true;

            // Post-synthesis numeric grounding check (diagnostic only).
            checkNumericGrounding(synthesizedText.toString(), results);

            // Return the synthesized answer so the caller (ChatService) can mirror it onto the
            // ROOT chat.handle span as langfuse.trace.output. Setting it here on Span.current()
            // was unreliable — by synthesis time the active span is not guaranteed to be the root
            // (the fan-out can detach the context), so the trace-level output never landed and the
            // continuous eval scored an empty answer. The root span carries it deterministically now.
            return output;

        } catch (Exception e) {
            log.error("AnswerSynthesizer failed: {}", e.getMessage(), e);
            llmSpan.setStatus(StatusCode.ERROR, e.getMessage());
            llmSpan.end();
            if (!emitterDone) {
                try {
                    String errorMsg = "I encountered an error while synthesizing the response. " +
                            "Please try again or contact support.";
                    // Role delta was sent at start of synthesize(); go straight to content + stop.
                    emitter.send(SseEmitter.event().data(
                            contentDelta(completionId, created, errorMsg, mapper)));
                    emitter.send(SseEmitter.event().data(stopDelta(completionId, created, mapper)));
                    emitter.send(SseEmitter.event().data(" [DONE]"));
                    emitter.complete();
                } catch (Exception inner) {
                    log.warn("Could not send error response to emitter", inner);
                    try { emitter.completeWithError(e); } catch (Exception ignored) {}
                }
            }
            return "";
        }
    }

    /**
     * The history-only (chitchat/follow-up) system prompt, rendered PER REQUEST with the effective
     * domain framing: the explicit {@code conduit.assistant.domain-context} override when set non-blank,
     * else the framing composed from the loaded domain manifests (World-B: domain copy is manifest-
     * declared, never a Java literal). Rendered at call time — not construction — so
     * {@code composedDomainContext()} is read only after {@code DomainManifestStore} has loaded.
     */
    private String historySystemPrompt() {
        String domainContext = (domainContextOverride != null && !domainContextOverride.isBlank())
                ? domainContextOverride
                : manifestStore.composedDomainContext();
        return promptLoader.render("answer-synthesizer-history.system", Map.of(
                "domain_context", domainContext,
                "instruction_hierarchy", historyInstructionHierarchy)).strip();
    }

    // ── Prompt builders ──────────────────────────────────────────────────────

    private String buildUserContent(List<NodeResult> results, List<String> withheldDomains,
                                    List<GroundedFigure> groundedFigures,
                                    Map<String, String> entityLabels,
                                    List<WithheldEntity> withheldEntities,
                                    String cappedNote) throws Exception {
        return renderUserContent(mapper, figuresBlock, results, withheldDomains, groundedFigures,
                entityLabels, withheldEntities, cappedNote);
    }

    /** Pure user-message renderer (testable): DATA blocks, WITHHELD (domain + entity), cap note, figures. */
    static String renderUserContent(ObjectMapper mapper, String figuresBlock, List<NodeResult> results,
                                    List<String> withheldDomains, List<GroundedFigure> groundedFigures,
                                    Map<String, String> entityLabels, List<WithheldEntity> withheldEntities,
                                    String cappedNote) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (NodeResult r : results) {
            if (r.isOk()) {
                // For a multi-entity COMPARE, two DATA blocks can share an agentId; the entity label
                // (resolver-produced DATA — no domain literal in Java) disambiguates which client each
                // block belongs to. Empty on every single-entity request → the header is byte-identical.
                String entity = entityLabels == null ? null : entityLabels.get(r.nodeId());
                sb.append("--- DATA: ").append(r.agentId())
                  .append(" (").append(r.protocol()).append(")");
                if (entity != null && !entity.isBlank()) {
                    sb.append(" [entity: ").append(entity).append("]");
                }
                sb.append(" ---\n");
                sb.append(mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(r.data()));
                sb.append("\n--- END DATA ---\n\n");
            } else if (r.isCleanSkip()) {
                sb.append("--- NOT APPLICABLE: ").append(r.agentId())
                  .append(" (").append(r.protocol()).append(")")
                  .append(" --- condition evaluated false; do not report this as missing data ---\n\n");
            } else {
                sb.append("--- MISSING: ").append(r.agentId())
                  .append(" (").append(r.protocol()).append(")")
                  .append(" --- data unavailable (status: ").append(r.status()).append(")")
                  .append(" ---\n\n");
            }
        }
        // Domains the request referenced but the entitlement gate pruned (outside the caller's
        // access). Declared here so the model fulfills the accessible part AND states the withheld
        // part honestly, never dropping it silently. The domain label is data, not an instruction.
        if (withheldDomains != null) {
            for (String d : withheldDomains) {
                if (d == null || d.isBlank()) continue;
                sb.append("--- WITHHELD: ").append(d)
                  .append(" --- The request also referenced this domain, which is outside the user's ")
                  .append("access and was not retrieved. State plainly that ").append(d)
                  .append(" data was not included because it is outside the user's access. ---\n\n");
            }
        }
        // Uncovered clients in a multi-entity COMPARE. Attribution is the user's OWN verbatim words +
        // the exact manifest coverage-denial copy a standalone ask for that client streams (S4 parity) —
        // the resolver's canonical name/id is NEVER disclosed here, and the withheld client's data never
        // enters the prompt at all (its group never executed). Empty on every non-COMPARE request.
        if (withheldEntities != null) {
            for (WithheldEntity we : withheldEntities) {
                if (we == null || we.userVerbatim() == null) continue;
                sb.append("--- WITHHELD ENTITY: \"").append(we.userVerbatim()).append("\" --- ")
                  .append(we.denialCopy() == null ? "" : we.denialCopy())
                  .append(" State this plainly and do not report any data for this entity. ---\n\n");
            }
        }
        // Fan-out was capped: tell the model to say which entities it compared and that the rest can be
        // re-asked. Domain-neutral instruction; the note itself carries no domain literal.
        if (cappedNote != null && !cappedNote.isBlank()) {
            sb.append("--- NOTE --- ").append(cappedNote).append(" ---\n\n");
        }
        if (groundedFigures != null && !groundedFigures.isEmpty()) {
            sb.append("--- GROUNDED FIGURES ---\n")
              .append(figuresBlock);
            for (GroundedFigure figure : groundedFigures) {
                ObjectNode row = mapper.createObjectNode();
                row.put("label", figure.label());
                row.put("placeholder", figure.placeholder());
                row.put("source_agent", figure.sourceAgent());
                row.put("format", figure.format());
                sb.append(mapper.writeValueAsString(row)).append('\n');
            }
            sb.append("--- END GROUNDED FIGURES ---\n\n");
        }
        return sb.toString();
    }

    private String buildRequestBody(String dataContent, String originalPrompt,
                                    List<Message> history) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("stream", true);

        ArrayNode messages = root.putArray("messages");

        // System: grounding rules
        messages.addObject().put("role", "system").put("content", systemPrompt);

        // Include the exact prior conversation turns the client sent. The client owns
        // transcript/window/summary policy; the gateway does not hard-truncate here.
        // We exclude the last user message (it becomes the final data+question message below).
        if (history != null && history.size() > 1) {
            for (int i = 0; i < history.size() - 1; i++) {
                Message m = history.get(i);
                if (m.content() != null && !m.content().isBlank()) {
                    messages.addObject()
                            .put("role", m.role())
                            .put("content", m.content());
                }
            }
        }

        // Final user message: agent data blocks + the current question
        messages.addObject()
                .put("role", "user")
                .put("content", dataContent + "\n\nQuestion: " + originalPrompt);

        return mapper.writeValueAsString(root);
    }

    /**
     * History-only path: answer from conversation history without calling any agents.
     *
     * <p>Used for:
     * <ul>
     *   <li>{@code FOLLOW_UP} questions answerable from the client-sent conversation</li>
     *   <li>{@code CHITCHAT} direct answers</li>
     * </ul>
     */
    public String synthesizeFromHistory(List<Message> history, String latestPrompt,
                                      SseEmitter emitter) {
        return synthesizeFromHistory(history, latestPrompt, emitter, null);
    }

    public String synthesizeFromHistory(List<Message> history, String latestPrompt,
                                      SseEmitter emitter, String completionIdOverride) {
        return synthesizeFromHistory(history, latestPrompt, emitter, completionIdOverride, -1L);
    }

    /** @param requestStartMillis see {@link #synthesize}; records request-level TTFT, or -1 to skip. */
    public String synthesizeFromHistory(List<Message> history, String latestPrompt,
                                      SseEmitter emitter, String completionIdOverride,
                                      long requestStartMillis) {
        String completionId = completionIdOverride != null
                ? completionIdOverride
                : "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = System.currentTimeMillis() / 1_000L;

        try {
            // Role delta first — same pattern as synthesize() to avoid duplicate-ID issues.
            try {
                emitter.send(SseEmitter.event().data(roleDelta(completionId, created, mapper)));
            } catch (Exception ignored) { /* client disconnected */ }

            ObjectNode root = mapper.createObjectNode();
            root.put("model", model);
            root.put("stream", true);

            ArrayNode messages = root.putArray("messages");
            messages.addObject().put("role", "system").put("content", historySystemPrompt());

            if (history != null) {
                for (int i = 0; i < history.size(); i++) {
                    Message m = history.get(i);
                    if (m.content() != null && !m.content().isBlank()) {
                        messages.addObject().put("role", m.role()).put("content", m.content());
                    }
                }
            }

            String requestBody = mapper.writeValueAsString(root);
            log.debug("synthesizeFromHistory: model={}", model);

            StringBuilder synthesizedText = new StringBuilder();
            streamFromLlm(requestBody, emitter, completionId, created, synthesizedText,
                    showReasoning, new long[3], requestStartMillis);
            // Returned to the caller (ChatService) to mirror onto the ROOT chat.handle span as
            // langfuse.trace.output — see synthesize() for why Span.current() here is unreliable.
            String histOutput = synthesizedText.toString();
            if (histOutput.length() > 2000) histOutput = histOutput.substring(0, 2000) + "…";
            emitter.send(SseEmitter.event().data(stopDelta(completionId, created, mapper)));
            emitter.send(SseEmitter.event().data(" [DONE]"));
            emitter.complete();
            return histOutput;

        } catch (Exception e) {
            log.error("synthesizeFromHistory failed: {}", e.getMessage(), e);
            try {
                // Role delta was sent at start of synthesizeFromHistory(); go straight to content + stop.
                emitter.send(SseEmitter.event().data(
                        contentDelta(completionId, created,
                                "I couldn't process that. Please rephrase.", mapper)));
                emitter.send(SseEmitter.event().data(stopDelta(completionId, created, mapper)));
                emitter.send(SseEmitter.event().data(" [DONE]"));
                emitter.complete();
            } catch (Exception inner) {
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
            return "";
        }
    }

    String substituteFigures(String text, List<GroundedFigure> figures) {
        String out = text == null ? "" : text;
        if (figures == null) return out;
        for (GroundedFigure figure : figures) {
            out = out.replace(figure.placeholder(), figure.renderedValue());
            if (figure.placeholder().startsWith("{{") && figure.placeholder().endsWith("}}")) {
                String token = figure.placeholder().substring(2, figure.placeholder().length() - 2);
                out = out.replaceAll("\\{\\{\\s*" + Pattern.quote(token) + "\\s*}}",
                        Matcher.quoteReplacement(figure.renderedValue()));
            }
        }
        return out;
    }

    String ensureFiguresMentioned(String text, List<GroundedFigure> figures) {
        String out = text == null ? "" : text;
        if (figures == null || figures.isEmpty()) return out;
        List<GroundedFigure> missing = figures.stream()
                .filter(f -> f.renderedValue() != null && !f.renderedValue().isBlank())
                .filter(f -> !mentionsFigure(out, f))
                .toList();
        if (missing.isEmpty()) return out;
        StringBuilder sb = new StringBuilder(out.stripTrailing());
        if (!sb.isEmpty()) sb.append("\n\n");
        sb.append("Grounded figures from the source data: ");
        for (int i = 0; i < missing.size(); i++) {
            GroundedFigure figure = missing.get(i);
            if (i > 0) sb.append("; ");
            sb.append(figure.label()).append(" is ").append(figure.renderedValue());
        }
        sb.append('.');
        return sb.toString();
    }

    private boolean mentionsFigure(String text, GroundedFigure figure) {
        if (text == null || figure == null || figure.renderedValue() == null) return false;
        if (!text.contains(figure.renderedValue())) return false;
        String lower = text.toLowerCase();
        if (figure.label() == null || figure.label().isBlank()) return true;
        for (String token : figure.label().toLowerCase().split("[^a-z0-9]+")) {
            if (token.isBlank()) continue;
            if (!lower.contains(token)) return false;
        }
        return true;
    }

    String deterministicFigureFallback(String originalPrompt, List<GroundedFigure> figures) {
        StringBuilder sb = new StringBuilder();
        if (originalPrompt != null && !originalPrompt.isBlank()) {
            sb.append("For the request \"").append(originalPrompt).append("\", ");
        }
        sb.append("the grounded figures from the source data are: ");
        for (int i = 0; i < figures.size(); i++) {
            GroundedFigure figure = figures.get(i);
            if (i > 0) sb.append("; ");
            sb.append(figure.label()).append(" is ").append(figure.renderedValue());
        }
        sb.append('.');
        return sb.toString();
    }

    // ── LLM streaming ────────────────────────────────────────────────────────

    private String collectFromLlm(String requestBody, long[] tokenCounts) throws Exception {
        String bodyWithUsage = requestBody;
        try {
            JsonNode req = mapper.readTree(requestBody);
            ((ObjectNode) req).putObject("stream_options").put("include_usage", true);
            bodyWithUsage = mapper.writeValueAsString(req);
        } catch (Exception ignored) { /* leave body unchanged if parse fails */ }

        HttpResponse<java.io.InputStream> response = sendWithRetry(baseUrl + "/chat/completions", bodyWithUsage);
        if (response.statusCode() != 200) {
            String body = new String(response.body().readAllBytes());
            throw new RuntimeException("LLM returned HTTP " + response.statusCode() + ": " + body);
        }

        StringBuilder out = new StringBuilder();
        final InputStream bodyStream = response.body();
        final long idleDeadlineMs = streamIdleTimeoutSeconds * 1000L;
        final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        final AtomicBoolean streaming = new AtomicBoolean(true);
        Thread watchdog = Thread.ofVirtual().name("synth-buffer-idle-watchdog").start(() -> {
            try {
                while (streaming.get()) {
                    long idle = System.currentTimeMillis() - lastActivity.get();
                    if (idle >= idleDeadlineMs) {
                        log.warn("Buffered synthesis stream idle {}ms (>{}s) — closing stalled LLM stream",
                                idle, streamIdleTimeoutSeconds);
                        try { bodyStream.close(); } catch (Exception ignored) {}
                        return;
                    }
                    Thread.sleep(Math.min(500L, Math.max(50L, idleDeadlineMs - idle)));
                }
            } catch (InterruptedException ignored) { /* normal shutdown */ }
        });

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.currentTimeMillis());
                if (!line.startsWith("data:")) continue;
                String data = line.substring("data:".length()).strip();
                if (data.equals("[DONE]")) break;
                if (data.isBlank()) continue;
                try {
                    JsonNode raw = mapper.readTree(data);
                    JsonNode usageNode = raw.path("usage");
                    if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                        tokenCounts[0] = usageNode.path("prompt_tokens").asLong(0);
                        tokenCounts[1] = usageNode.path("completion_tokens").asLong(0);
                        tokenCounts[2] = usageNode.path("total_tokens").asLong(tokenCounts[0] + tokenCounts[1]);
                    }
                } catch (Exception ignored) {}
                DeltaChunk chunk = parseChunk(data);
                if (chunk != null && chunk.content() != null) out.append(chunk.content());
            }
        } finally {
            streaming.set(false);
            watchdog.interrupt();
        }
        return out.toString();
    }

    /**
     * POST to the LLM with streaming=true and forward each content delta to the emitter.
     *
     * <p>When {@code emitReasoning} is true, {@code reasoning_content} deltas from thinking
     * models (glm-4.5-flash, etc.) are streamed as a faint markdown blockquote before the
     * main answer — visible in LibreChat without any frontend changes.
     *
     * <p>Accumulates the final answer text in {@code synthesizedText} for the grounding check.
     */
    private void streamFromLlm(
            String requestBody,
            SseEmitter emitter,
            String completionId,
            long created,
            StringBuilder synthesizedText,
            boolean emitReasoning,
            long[] tokenCounts,
            long requestStartMillis) throws Exception {

        // TTFT clock: wall time from issuing the generation request to the first answer token.
        final long ttftStart = System.currentTimeMillis();
        boolean ttftRecorded = false;

        // Inject stream_options to get a final usage chunk — needed for token count telemetry.
        String bodyWithUsage = requestBody;
        try {
            JsonNode req = mapper.readTree(requestBody);
            ((ObjectNode) req).putObject("stream_options").put("include_usage", true);
            bodyWithUsage = mapper.writeValueAsString(req);
        } catch (Exception ignored) { /* leave body unchanged if parse fails */ }

        String endpoint = baseUrl + "/chat/completions";

        HttpResponse<java.io.InputStream> response = sendWithRetry(endpoint, bodyWithUsage);

        if (response.statusCode() != 200) {
            String body = new String(response.body().readAllBytes());
            throw new RuntimeException("LLM returned HTTP " + response.statusCode() + ": " + body);
        }

        // Role delta was already sent by the calling synthesize/synthesizeFromHistory method
        // before streamFromLlm was invoked, so any role chunk from the LLM response is skipped
        // (parseChunk only extracts content/reasoning_content, not role).

        StringBuilder reasoningBuffer = new StringBuilder();
        boolean reasoningEmitted = false;  // have we already opened the blockquote?
        boolean firstContent = true;        // have we seen the first content token?

        // Idle watchdog: BufferedReader.readLine() has no read deadline, so a stall AFTER the
        // response headers (mid-generation) would block until the 120s SSE emitter timeout.
        // A cheap virtual-thread watchdog closes the body stream once no line has arrived within
        // the idle window, which unblocks readLine() with an IOException and aborts the stall.
        final InputStream bodyStream = response.body();
        final long idleDeadlineMs = streamIdleTimeoutSeconds * 1000L;
        final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        final AtomicBoolean streaming = new AtomicBoolean(true);
        Thread watchdog = Thread.ofVirtual().name("synth-idle-watchdog").start(() -> {
            try {
                while (streaming.get()) {
                    long idle = System.currentTimeMillis() - lastActivity.get();
                    if (idle >= idleDeadlineMs) {
                        log.warn("Synthesis stream idle {}ms (>{}s) — closing stalled LLM stream",
                                idle, streamIdleTimeoutSeconds);
                        try { bodyStream.close(); } catch (Exception ignored) {}
                        return;
                    }
                    Thread.sleep(Math.min(500L, Math.max(50L, idleDeadlineMs - idle)));
                }
            } catch (InterruptedException ignored) { /* normal shutdown on stream completion */ }
        });

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.currentTimeMillis());
                if (!line.startsWith("data:")) continue;
                String data = line.substring("data:".length()).strip();
                if (data.equals("[DONE]")) break;
                if (data.isBlank()) continue;

                // The final usage chunk (from stream_options.include_usage) has no choices —
                // parse it directly and capture token counts for the OpenInference span.
                try {
                    JsonNode raw = mapper.readTree(data);
                    JsonNode usageNode = raw.path("usage");
                    if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                        tokenCounts[0] = usageNode.path("prompt_tokens").asLong(0);
                        tokenCounts[1] = usageNode.path("completion_tokens").asLong(0);
                        tokenCounts[2] = usageNode.path("total_tokens").asLong(tokenCounts[0] + tokenCounts[1]);
                    }
                } catch (Exception ignored) {}

                DeltaChunk chunk = parseChunk(data);
                if (chunk == null) continue;

                // ── Reasoning content (thinking trace) ────────────────────────
                if (chunk.reasoning != null && !chunk.reasoning.isEmpty()) {
                    reasoningBuffer.append(chunk.reasoning);
                    if (emitReasoning) {
                        if (!reasoningEmitted) {
                            // Open the thinking blockquote once
                            emitter.send(SseEmitter.event().data(
                                    contentDelta(completionId, created,
                                            "> 🤔 **Thinking...**\n> ", mapper)));
                            reasoningEmitted = true;
                        }
                        // Stream reasoning deltas as blockquote lines
                        String reasoningFmt = chunk.reasoning.replace("\n", "\n> ");
                        emitter.send(SseEmitter.event().data(
                                contentDelta(completionId, created, reasoningFmt, mapper)));
                    }
                }

                // ── Answer content ─────────────────────────────────────────────
                if (chunk.content != null && !chunk.content.isEmpty()) {
                    if (!ttftRecorded) {
                        long now = System.currentTimeMillis();
                        // Record TTFT once, on the first answer token (Insights "TTFT p95" panel).
                        Timer.builder("conduit.ttft")
                                .description("Time to first answer token from the synthesizer")
                                .publishPercentileHistogram()
                                .register(meterRegistry)
                                .record(now - ttftStart, java.util.concurrent.TimeUnit.MILLISECONDS);
                        // Request-level TTFT: wall time from the chat request entering the gateway to
                        // the first streamed content delta. Prometheus renders this Timer as
                        // conduit_time_to_first_token_seconds{_count,_sum,_bucket}. Percentile
                        // histogram enabled so p50/p95 are computable via histogram_quantile.
                        // Domain-agnostic: no domain/client/entity data on the metric (World B).
                        if (requestStartMillis > 0) {
                            Timer.builder("conduit.time.to.first.token")
                                    .description("Time from chat request start to first streamed content delta")
                                    .publishPercentileHistogram()
                                    .register(meterRegistry)
                                    .record(now - requestStartMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                        }
                        ttftRecorded = true;
                    }
                    if (firstContent && reasoningEmitted) {
                        // Close the thinking block and add a separator before the answer
                        emitter.send(SseEmitter.event().data(
                                contentDelta(completionId, created, "\n\n---\n\n", mapper)));
                        firstContent = false;
                    } else {
                        firstContent = false;
                    }
                    synthesizedText.append(chunk.content);
                    emitter.send(SseEmitter.event().data(
                            contentDelta(completionId, created, chunk.content, mapper)));
                }
            }
        } finally {
            // Stop the watchdog so it never closes a fresh stream on a later call.
            streaming.set(false);
            watchdog.interrupt();
        }

        if (reasoningBuffer.length() > 0) {
            log.debug("Reasoning content captured ({} chars, emitted={})",
                    reasoningBuffer.length(), emitReasoning);
        }
    }

    /** Parsed delta from a single SSE chunk. */
    private record DeltaChunk(String content, String reasoning) {}

    /** Extract both {@code delta.content} and {@code delta.reasoning_content} from a chunk. */
    private DeltaChunk parseChunk(String chunkJson) {
        try {
            JsonNode root = mapper.readTree(chunkJson);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return null;
            JsonNode delta = choices.get(0).path("delta");
            String content = delta.has("content") && !delta.path("content").isNull()
                    ? delta.path("content").asText(null) : null;
            String reasoning = delta.has("reasoning_content") && !delta.path("reasoning_content").isNull()
                    ? delta.path("reasoning_content").asText(null) : null;
            return new DeltaChunk(content, reasoning);
        } catch (Exception e) {
            log.debug("Could not parse SSE chunk: {}", chunkJson);
            return null;
        }
    }

    // ── Numeric grounding check ───────────────────────────────────────────────

    /**
     * Warn if any number in the synthesized answer cannot be found in any agent output.
     * This is a best-effort diagnostic log — it does not block or alter the response.
     */
    private void checkNumericGrounding(String answer, List<NodeResult> results) {
        // Build a single string of all agent data for substring search.
        StringBuilder allData = new StringBuilder();
        for (NodeResult r : results) {
            if (r.isOk() && r.data() != null) {
                allData.append(r.data().toString()).append(' ');
            }
        }
        String dataStr = allData.toString();

        Matcher m = NUMBER_PATTERN.matcher(answer);
        while (m.find()) {
            String num = m.group();
            // Normalise by stripping formatting differences (e.g. "1,234" → "1234").
            String normalised = num.replace(",", "").replace(".", "");
            if (!dataStr.contains(num) && !dataStr.contains(normalised)) {
                log.warn("Grounding check: number '{}' in synthesized answer not found in agent data",
                        num);
            }
        }
    }

    // ── SSE chunk builders (match the ChatService format) ────────────────────

    private static String roleDelta(String id, long ts, ObjectMapper m) throws Exception {
        ObjectNode root = chunkRoot(id, ts, m);
        ObjectNode choice = (ObjectNode) root.path("choices").get(0);
        choice.putObject("delta").put("role", "assistant");
        choice.putNull("finish_reason");
        return " " + m.writeValueAsString(root);  // leading space → OpenAI-exact "data: {json}" (Spring omits it)
    }

    private static String contentDelta(String id, long ts, String content, ObjectMapper m)
            throws Exception {
        ObjectNode root = chunkRoot(id, ts, m);
        ObjectNode choice = (ObjectNode) root.path("choices").get(0);
        choice.putObject("delta").put("content", content);
        choice.putNull("finish_reason");
        return " " + m.writeValueAsString(root);  // leading space → OpenAI-exact "data: {json}" (Spring omits it)
    }

    private static String stopDelta(String id, long ts, ObjectMapper m) throws Exception {
        ObjectNode root = chunkRoot(id, ts, m);
        ObjectNode choice = (ObjectNode) root.path("choices").get(0);
        choice.putObject("delta");
        choice.put("finish_reason", "stop");
        return " " + m.writeValueAsString(root);  // leading space → OpenAI-exact "data: {json}" (Spring omits it)
    }

    private HttpResponse<java.io.InputStream> sendWithRetry(String endpoint, String requestBody) throws Exception {
        int delayMs = retryInitialDelayMs;
        Exception lastTimeout = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // Rebuild the request on each attempt — BodyPublishers.ofString() is one-shot
            // and cannot be replayed after the first send.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    // Bound the wait for response headers — a hung endpoint no longer parks the
                    // synthesis thread indefinitely. Retried like a 429 (nothing streamed yet).
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            try {
                HttpResponse<java.io.InputStream> resp =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 429 || attempt == maxRetries) return resp;
                log.warn("LLM rate limited (429), retry {}/{} in {}ms", attempt, maxRetries, delayMs);
            } catch (HttpTimeoutException e) {
                lastTimeout = e;
                if (attempt == maxRetries) throw e;
                log.warn("LLM request timed out (attempt {}/{}), retry in {}ms", attempt, maxRetries, delayMs);
            }
            Thread.sleep(delayMs);
            delayMs *= retryBackoffMultiplier;
        }
        if (lastTimeout != null) throw lastTimeout;
        throw new IllegalStateException("unreachable");
    }

    private static ObjectNode chunkRoot(String id, long ts, ObjectMapper m) {
        ObjectNode root = m.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", ts);
        root.put("model", "conduit-assistant");
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        return root;
    }
}
