package ai.meridian.gateway.domain.chat;

import ai.meridian.gateway.api.v1.chat.dto.ChatRequest;
import ai.meridian.gateway.api.v1.chat.dto.Message;
import ai.meridian.gateway.domain.auth.EntitlementService;
import ai.meridian.gateway.domain.auth.EntitlementService.EntitlementResult;
import ai.meridian.gateway.domain.auth.Principal;
import ai.meridian.gateway.domain.auth.PrincipalStore;
import ai.meridian.gateway.domain.intent.Intent;
import ai.meridian.gateway.domain.intent.IntentClassifier;
import ai.meridian.gateway.domain.intent.IntentResult;
import ai.meridian.gateway.domain.session.ConversationSession;
import ai.meridian.gateway.domain.session.ConversationSessionStore;
import ai.meridian.gateway.infrastructure.telemetry.TraceEvent;
import ai.meridian.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.meridian.gateway.infrastructure.telemetry.event.AgentCompleteData;
import ai.meridian.gateway.infrastructure.telemetry.event.AgentStartData;
import ai.meridian.gateway.infrastructure.telemetry.event.AgentsResolvedData;
import ai.meridian.gateway.infrastructure.telemetry.event.EntitlementCheckData;
import ai.meridian.gateway.infrastructure.telemetry.event.IntentClassifiedData;
import ai.meridian.gateway.infrastructure.telemetry.event.RequestCompleteData;
import ai.meridian.gateway.infrastructure.telemetry.event.RequestStartData;
import ai.meridian.gateway.infrastructure.telemetry.event.SynthesisStartData;
import ai.meridian.gateway.orchestration.executor.FlatPlanExecutor;
import ai.meridian.gateway.orchestration.model.NodeResult;
import ai.meridian.gateway.orchestration.model.Plan;
import ai.meridian.gateway.orchestration.model.PlanNode;
import ai.meridian.gateway.registry.model.AgentManifest;
import ai.meridian.gateway.resolver.model.ResolverResult;
import ai.meridian.gateway.resolver.service.AgentResolver;
import ai.meridian.gateway.synthesis.answer.AnswerSynthesizer;
import ai.meridian.gateway.synthesis.input.EntityBag;
import ai.meridian.gateway.synthesis.input.InputSynthesizer;
import ai.meridian.gateway.synthesis.input.InputSynthesizer.SynthesisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    static final String MODEL_ID = "meridian-assistant";
    private static final long RESULT_CACHE_TTL_MS = 5 * 60 * 1_000L;

    private static final List<String> TITLE_TRIGGERS = List.of(
            "generate a concise", "generate a short", "provide a title",
            "name this conversation", "title for this"
    );

    private final ObjectMapper             mapper;
    private final IntentClassifier         intentClassifier;
    private final ConversationSessionStore sessionStore;
    private final AgentResolver            resolver;
    private final InputSynthesizer         inputSynthesizer;
    private final FlatPlanExecutor         executor;
    private final AnswerSynthesizer        answerSynthesizer;
    private final PrincipalStore           principalStore;
    private final EntitlementService       entitlementService;
    private final TraceEventPublisher      tracePublisher;
    private final Tracer                   tracer;
    private final Counter intentFetchCounter;
    private final Counter intentFollowUpCounter;
    private final Counter intentClarifyCounter;
    private final Counter intentChitchatCounter;

    public ChatService(ObjectMapper mapper,
                       IntentClassifier intentClassifier,
                       ConversationSessionStore sessionStore,
                       AgentResolver resolver,
                       InputSynthesizer inputSynthesizer,
                       FlatPlanExecutor executor,
                       AnswerSynthesizer answerSynthesizer,
                       PrincipalStore principalStore,
                       EntitlementService entitlementService,
                       TraceEventPublisher tracePublisher,
                       Tracer tracer,
                       MeterRegistry meterRegistry) {
        this.mapper              = mapper;
        this.intentClassifier    = intentClassifier;
        this.sessionStore        = sessionStore;
        this.resolver            = resolver;
        this.inputSynthesizer    = inputSynthesizer;
        this.executor            = executor;
        this.answerSynthesizer   = answerSynthesizer;
        this.principalStore      = principalStore;
        this.entitlementService  = entitlementService;
        this.tracePublisher      = tracePublisher;
        this.tracer              = tracer;
        this.intentFetchCounter    = Counter.builder("chat.intent").tag("type","FETCH_DATA").register(meterRegistry);
        this.intentFollowUpCounter = Counter.builder("chat.intent").tag("type","FOLLOW_UP").register(meterRegistry);
        this.intentClarifyCounter  = Counter.builder("chat.intent").tag("type","CLARIFY").register(meterRegistry);
        this.intentChitchatCounter = Counter.builder("chat.intent").tag("type","CHITCHAT").register(meterRegistry);
    }

    public boolean isTitleRequest(ChatRequest request) {
        if (request.messages() == null) return false;
        return request.messages().stream()
                .filter(m -> m.content() != null)
                .map(m -> m.content().toLowerCase())
                .anyMatch(c -> TITLE_TRIGGERS.stream().anyMatch(c::contains));
    }

    public void streamTitle(SseEmitter emitter) {
        String id = newId(); long ts = epochSeconds();
        try {
            emitter.send(SseEmitter.event().data(roleDelta(id, ts)));
            emitter.send(SseEmitter.event().data(contentDelta(id, ts, "Meridian AI")));
            emitter.send(SseEmitter.event().data(stopDelta(id, ts)));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (Exception e) { emitter.completeWithError(e); }
    }

    /**
     * Entry point from the controller — called on a virtual thread after the async boundary.
     *
     * @param jwtPrincipal      the JWT-verified principal captured on the servlet thread
     *                          before {@code CompletableFuture.runAsync()}; null if no JWT
     * @param headerConvId      value of the {@code X-Conversation-Id} header, or null
     */
    public void handleChat(ChatRequest request, SseEmitter emitter, String userId,
                           Principal jwtPrincipal, String headerConvId) {
        long   requestStart = System.currentTimeMillis();
        Span   rootSpan     = tracer.spanBuilder("chat.handle").startSpan();
        // Use the OTel trace_id as the single correlation ID across logs, glass-box, and traces.
        String requestId    = rootSpan.getSpanContext().isValid()
                ? rootSpan.getSpanContext().getTraceId()
                : TraceEventPublisher.newRequestId();
        String latestPrompt = extractLatestUserMessage(request);
        String conversationId = (headerConvId != null && !headerConvId.isBlank())
                ? headerConvId
                : ("conv-" + UUID.randomUUID());

        // ── Span attributes: visible on this span in Phoenix / Tempo ────────────
        rootSpan.setAttribute("session.id", conversationId);
        rootSpan.setAttribute("user.id", userId != null ? userId : "anonymous");
        rootSpan.setAttribute("meridian.request.id", requestId);

        // ── W3C Baggage: propagates outward on every downstream HTTP call ─────
        // Any service the gateway calls (wealth-http, servicing-mcp, embeddings)
        // receives these as a `baggage:` header automatically — no extra wiring.
        // When those services are instrumented, their spans also carry session.id
        // and user.id, so Tempo's service map shows the conversation context end-to-end.
        String effectiveUserId = userId != null ? userId : "anonymous";
        Baggage baggage = Baggage.current().toBuilder()
                .put("session.id", conversationId)
                .put("user.id", effectiveUserId)
                .put("meridian.request.id", requestId)
                .build();
        // makeCurrent() attaches baggage to the active OTel context for this thread.
        // The scope MUST be closed — we do so in the finally block below.
        Scope baggageScope = baggage.storeInContext(Context.current()).makeCurrent();

        log.info("handleChat requestId={} userId={} conversationId={} prompt='{}'",
                requestId, userId, conversationId,
                latestPrompt.length() > 120 ? latestPrompt.substring(0, 120) + "..." : latestPrompt);

        tracePublisher.publish(TraceEvent.of("request_start", requestId, conversationId,
                new RequestStartData(userId, latestPrompt)));

        // ── OpenAI spec: emit the role delta immediately so the client gets
        // first bytes within ~100ms and the stream appears alive while the
        // pipeline (intent classification + agent fan-out) runs.
        String streamId = "chatcmpl-" + requestId.replace("-", "").substring(0, 32);
        long   streamTs = System.currentTimeMillis() / 1000;
        try {
            emitter.send(SseEmitter.event().data(roleDelta(streamId, streamTs)));
        } catch (Exception ignored) { /* client disconnected early */ }

        try {
            IntentResult intentResult = intentClassifier.classify(request.messages());
            rootSpan.setAttribute("intent", intentResult.intent().name());
            incrementIntentCounter(intentResult.intent());
            tracePublisher.publish(TraceEvent.of("intent_classified", requestId,
                    new IntentClassifiedData(intentResult.intent().name(),
                            intentResult.confidence(), intentResult.reasoning())));

            ConversationSession session = sessionStore.load(conversationId);
            log.debug("Session: conversationId={} relId={} turns={}", conversationId,
                    session.relationshipId(), session.turnCount());

            switch (intentResult.intent()) {
                case FETCH_DATA -> handleFetchData(request, emitter, latestPrompt,
                        conversationId, session, userId, jwtPrincipal, requestId, requestStart, rootSpan,
                        intentResult.extractedEntities(), streamId);
                case FOLLOW_UP  -> handleFollowUp(request, emitter, latestPrompt,
                        conversationId, session, userId, jwtPrincipal, requestId, requestStart, rootSpan, streamId);
                case CLARIFY    -> handleClarify(emitter, session, userId, jwtPrincipal, requestId, requestStart);
                case CHITCHAT   -> handleChitchat(request, emitter, requestId, requestStart, streamId);
            }

        } catch (Exception e) {
            log.error("Chat pipeline failed requestId={}", requestId, e);
            rootSpan.setStatus(StatusCode.ERROR, e.getMessage());
            tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
            try { streamTextAndComplete(emitter, "An internal error occurred. Please try again."); }
            catch (Exception ex) { try { emitter.completeWithError(ex); } catch (Exception ignored) {} }
        } finally {
            baggageScope.close();
            rootSpan.end();
        }
    }

    private void handleFetchData(ChatRequest request, SseEmitter emitter,
                                  String latestPrompt, String conversationId,
                                  ConversationSession session, String userId,
                                  Principal jwtPrincipal,
                                  String requestId, long requestStart, Span rootSpan,
                                  EntityBag preExtracted, String streamId) throws Exception {
        Span span = tracer.spanBuilder("chat.fetch_data").startSpan();
        try {
            ResolverResult resolved = resolver.resolve(latestPrompt);

            List<AgentsResolvedData.AgentRef> selectedRefs = resolved.selected().stream()
                    .map(c -> new AgentsResolvedData.AgentRef(
                            c.manifest().agentId(), c.manifest().protocol(), c.score()))
                    .collect(Collectors.toList());
            List<AgentsResolvedData.FilteredRef> skippedRefs = resolved.skipped().stream()
                    .map(c -> new AgentsResolvedData.FilteredRef(
                            c.manifest().agentId(), "below-confidence-floor"))
                    .collect(Collectors.toList());
            tracePublisher.publish(TraceEvent.of("agents_resolved", requestId,
                    new AgentsResolvedData(selectedRefs, skippedRefs)));

            if (resolved.fallback() || resolved.selected().isEmpty()) {
                streamTextAndComplete(emitter, "I wasn't sure which services to consult. " +
                        "Please mention the client name and what you need.");
                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                return;
            }

            // Use the JWT-verified principal if available; the JWT book claim is authoritative.
            Principal principal = (jwtPrincipal != null)
                    ? jwtPrincipal
                    : principalStore.load(userId);

            // Prune agents the principal is not permitted to invoke (domain + classification).
            // Must happen BEFORE input synthesis so we never synthesize inputs for denied agents.
            List<AgentManifest> manifests = resolved.selected().stream()
                    .map(c -> c.manifest()).collect(Collectors.toList());
            List<AgentManifest> allowedManifests = entitlementService.filterAgents(principal, manifests);
            if (allowedManifests.isEmpty()) {
                streamTextAndComplete(emitter, "You do not have access to any of the required services for this query.");
                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                return;
            }
            if (allowedManifests.size() < manifests.size()) {
                log.info("Agent pruning: {}/{} agents allowed for principal={}",
                        allowedManifests.size(), manifests.size(), principal.id());
            }
            final List<AgentManifest> finalManifests = allowedManifests;

            // Use pre-extracted entities from the combined intent+entity LLM call if available.
            // Merge session relationship context into the pre-extracted bag when it lacks a ref.
            SynthesisResult synthesis;
            if (preExtracted != null) {
                EntityBag effectiveBag = preExtracted;
                if (preExtracted.relationshipReference() == null && session.relationshipId() != null) {
                    effectiveBag = EntityBag.extracted(
                            session.relationshipId(), preExtracted.fundReference(),
                            preExtracted.tickerReferences(), preExtracted.period());
                }
                synthesis = inputSynthesizer.synthesize(effectiveBag, finalManifests);
            } else {
                String entityPrompt = buildEntityPrompt(latestPrompt, session);
                synthesis = inputSynthesizer.synthesize(entityPrompt, finalManifests);
            }

            if (synthesis.needsClarification() && synthesis.inputs().isEmpty()) {
                streamTextAndComplete(emitter, synthesis.clarificationMessage()); return;
            }
            if (synthesis.inputs().isEmpty()) {
                streamTextAndComplete(emitter, "Please specify the client name (e.g. 'Whitman Family Office')."); return;
            }

            String resolvedRelId = extractResolvedRelId(synthesis);
            if (resolvedRelId != null) {
                EntitlementResult ent = entitlementService.checkRelationship(principal, resolvedRelId);
                tracePublisher.publish(TraceEvent.of("entitlement_check", requestId,
                        new EntitlementCheckData(resolvedRelId, userId, ent.allowed(), ent.reason(), ent.source())));
                if (!ent.allowed()) {
                    String denialMsg = "Access denied: you are not authorized to view relationship " +
                            resolvedRelId + ". This denial has been logged.";
                    try {
                        streamTextAndComplete(emitter, denialMsg);
                    } catch (Exception denialEx) {
                        log.warn("Could not stream denial message (client disconnect?): {}", denialEx.getMessage());
                        try { emitter.complete(); } catch (Exception ce) { emitter.completeWithError(ce); }
                    }
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
            }

            List<PlanNode> nodes = synthesis.inputs().entrySet().stream()
                    .map(e -> {
                        AgentManifest m = finalManifests.stream()
                                .filter(a -> a.agentId().equals(e.getKey())).findFirst().orElseThrow();
                        return new PlanNode(e.getKey(), m, e.getValue(), List.of());
                    }).collect(Collectors.toList());

            nodes.forEach(n -> tracePublisher.publish(TraceEvent.of("agent_start", requestId,
                    new AgentStartData(n.nodeId(), n.agent().protocol()))));

            List<NodeResult> results = executor.execute(new Plan(nodes));

            results.forEach(r -> {
                String prev = r.isOk() && r.data() != null
                        ? r.data().toString().substring(0, Math.min(80, r.data().toString().length())) : r.status().name();
                tracePublisher.publish(TraceEvent.of("agent_complete", requestId,
                        new AgentCompleteData(r.agentId(), r.latencyMs(), r.isOk() ? "ok" : "failed", prev)));
            });

            long okCount = results.stream().filter(NodeResult::isOk).count();
            log.info("Fan-out complete {}/{} ok", okCount, results.size());

            sessionStore.save(session.withResults(resolvedRelId, extractResolvedFundId(synthesis), results));

            tracePublisher.publish(TraceEvent.of("synthesis_start", requestId,
                    new SynthesisStartData(results.size(), (int) okCount)));

            answerSynthesizer.synthesize(results, latestPrompt, request.messages(), emitter, streamId);

            tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart,
                            results.size(), (int) okCount)));
        } finally { span.end(); }
    }

    private void handleFollowUp(ChatRequest request, SseEmitter emitter,
                                 String latestPrompt, String conversationId,
                                 ConversationSession session, String userId,
                                 Principal jwtPrincipal,
                                 String requestId, long requestStart, Span rootSpan,
                                 String streamId) throws Exception {
        Span span = tracer.spanBuilder("chat.follow_up").startSpan();
        try {
            if (session.hasFreshResults(RESULT_CACHE_TTL_MS)) {
                List<NodeResult> cached = session.lastAgentResults();
                int total = cached.size();
                long ok = cached.stream().filter(NodeResult::isOk).count();
                tracePublisher.publish(TraceEvent.of("synthesis_start", requestId,
                        new SynthesisStartData(total, (int) ok)));
                answerSynthesizer.synthesize(cached, latestPrompt, request.messages(), emitter, streamId);
                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, total, (int) ok)));
            } else if (session.relationshipId() != null) {
                String enriched = latestPrompt + " [session_relationship_id: " + session.relationshipId() + "]";
                handleFetchData(request, emitter, enriched, conversationId, session, userId, jwtPrincipal, requestId, requestStart, rootSpan, null, streamId);
                return;
            } else {
                answerSynthesizer.synthesizeFromHistory(request.messages(), latestPrompt, emitter, streamId);
                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
            }
        } finally { span.end(); }
    }

    private void handleClarify(SseEmitter emitter, ConversationSession session,
                                String userId, Principal jwtPrincipal,
                                String requestId, long requestStart) throws Exception {
        Principal principal = (jwtPrincipal != null) ? jwtPrincipal : principalStore.load(userId);

        StringBuilder msg = new StringBuilder();
        if (session.relationshipId() != null) {
            msg.append("Could you clarify what you'd like to know about ")
               .append(session.relationshipId())
               .append("? For example: holdings, performance, settlements, or corporate actions.");
        } else if (principal.book() != null && !principal.book().isEmpty()) {
            msg.append("Which client relationship are you asking about? You have access to:\n");
            for (String relId : principal.book()) {
                msg.append("  • ").append(relId).append("\n");
            }
            msg.append("\nReply with the relationship ID to continue.");
        } else {
            msg.append("Which client or relationship ID are you asking about?");
        }
        streamTextAndComplete(emitter, msg.toString());
        tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
    }

    private void handleChitchat(ChatRequest request, SseEmitter emitter,
                                 String requestId, long requestStart, String streamId) throws Exception {
        answerSynthesizer.synthesizeFromHistory(request.messages(), extractLatestUserMessage(request), emitter, streamId);
        tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
    }

    private String extractLatestUserMessage(ChatRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) return "";
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Message m = request.messages().get(i);
            if ("user".equals(m.role()) && m.content() != null) return m.content();
        }
        return "";
    }

    private String buildEntityPrompt(String latestPrompt, ConversationSession session) {
        if (session.relationshipId() == null && session.fundId() == null) return latestPrompt;
        StringBuilder sb = new StringBuilder(latestPrompt);
        if (session.relationshipId() != null)
            sb.append(" [session_relationship_id: ").append(session.relationshipId()).append("]");
        if (session.fundId() != null)
            sb.append(" [session_fund_id: ").append(session.fundId()).append("]");
        return sb.toString();
    }

    private String extractResolvedRelId(SynthesisResult synthesis) {
        return synthesis.inputs().values().stream()
                .map(node -> node.path("relationship_id").asText(null))
                .filter(v -> v != null && v.startsWith("REL-"))
                .findFirst().orElse(null);
    }

    private String extractResolvedFundId(SynthesisResult synthesis) {
        return synthesis.inputs().values().stream()
                .map(node -> node.path("fund_id").asText(null))
                .filter(v -> v != null && v.startsWith("FND-"))
                .findFirst().orElse(null);
    }

    private void incrementIntentCounter(Intent intent) {
        switch (intent) {
            case FETCH_DATA -> intentFetchCounter.increment();
            case FOLLOW_UP  -> intentFollowUpCounter.increment();
            case CLARIFY    -> intentClarifyCounter.increment();
            case CHITCHAT   -> intentChitchatCounter.increment();
        }
    }

    /** Sends text tokens as SSE deltas and calls {@code emitter.complete()}. Terminates the stream. */
    private void streamTextAndComplete(SseEmitter emitter, String text) throws Exception {
        String id = newId(); long ts = epochSeconds();
        emitter.send(SseEmitter.event().data(roleDelta(id, ts)));
        for (String word : text.split("(?<=\\s)|(?=\\s)")) {
            emitter.send(SseEmitter.event().data(contentDelta(id, ts, word)));
        }
        emitter.send(SseEmitter.event().data(stopDelta(id, ts)));
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
    }

    private String roleDelta(String id, long ts) throws Exception {
        return chunk(id, ts, delta -> delta.put("role", "assistant"), null);
    }

    private String contentDelta(String id, long ts, String content) throws Exception {
        return chunk(id, ts, delta -> delta.put("content", content), null);
    }

    private String stopDelta(String id, long ts) throws Exception {
        return chunk(id, ts, delta -> {}, "stop");
    }

    @FunctionalInterface
    private interface DeltaWriter { void write(ObjectNode delta) throws Exception; }

    private String chunk(String id, long ts, DeltaWriter w, String finishReason) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", id); root.put("object", "chat.completion.chunk");
        root.put("created", ts); root.put("model", MODEL_ID);
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        w.write(delta);
        if (finishReason != null) choice.put("finish_reason", finishReason);
        else choice.putNull("finish_reason");
        return mapper.writeValueAsString(root);
    }

    private static String newId() { return "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""); }
    private static long epochSeconds() { return System.currentTimeMillis() / 1_000L; }
}
