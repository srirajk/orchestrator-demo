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
import ai.meridian.gateway.synthesis.input.InputSynthesizer;
import ai.meridian.gateway.synthesis.input.InputSynthesizer.SynthesisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    static final String MODEL_ID = "meridian-assistant";
    private static final long RESULT_CACHE_TTL_MS = 5 * 60 * 1_000L;

    private static final Map<String, String> REL_NAMES = new LinkedHashMap<>() {{
        put("REL-00042", "Whitman Family Office");
        put("REL-00099", "Chen Tech Ventures");
        put("REL-00188", "Okafor Family Trust");
        put("REL-00200", "Andersen Hedge Partners");
        put("REL-00300", "Meridian Growth Fund");
    }};

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

    public void handleChat(ChatRequest request, SseEmitter emitter, String userId) {
        String requestId    = TraceEventPublisher.newRequestId();
        long   requestStart = System.currentTimeMillis();
        Span   rootSpan     = tracer.spanBuilder("chat.handle").startSpan();
        String latestPrompt = extractLatestUserMessage(request);
        String conversationId = deriveConversationId(request);

        log.info("handleChat requestId={} userId={} conversationId={} prompt='{}'",
                requestId, userId, conversationId,
                latestPrompt.length() > 120 ? latestPrompt.substring(0, 120) + "..." : latestPrompt);

        tracePublisher.publish(TraceEvent.of("request_start", requestId,
                new RequestStartData(userId, latestPrompt)));

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
                        conversationId, session, userId, requestId, requestStart, rootSpan);
                case FOLLOW_UP  -> handleFollowUp(request, emitter, latestPrompt,
                        conversationId, session, userId, requestId, requestStart, rootSpan);
                case CLARIFY    -> handleClarify(emitter, session, userId, requestId, requestStart);
                case CHITCHAT   -> handleChitchat(request, emitter, requestId, requestStart);
            }

        } catch (Exception e) {
            log.error("Chat pipeline failed requestId={}", requestId, e);
            rootSpan.setStatus(StatusCode.ERROR, e.getMessage());
            tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
            try { streamText(emitter, "An internal error occurred. Please try again."); }
            catch (Exception ex) { emitter.completeWithError(ex); }
        } finally {
            rootSpan.end();
        }
    }

    private void handleFetchData(ChatRequest request, SseEmitter emitter,
                                  String latestPrompt, String conversationId,
                                  ConversationSession session, String userId,
                                  String requestId, long requestStart, Span rootSpan) throws Exception {
        Span span = tracer.spanBuilder("chat.fetch_data").startSpan();
        try {
            ResolverResult resolved = resolver.resolve(latestPrompt);

            List<AgentsResolvedData.AgentRef> selectedRefs = resolved.selected().stream()
                    .map(c -> new AgentsResolvedData.AgentRef(
                            c.manifest().agentId(), c.manifest().protocol(), c.score()))
                    .collect(Collectors.toList());
            tracePublisher.publish(TraceEvent.of("agents_resolved", requestId,
                    new AgentsResolvedData(selectedRefs, List.of())));

            if (resolved.fallback() || resolved.selected().isEmpty()) {
                streamText(emitter, "I wasn't sure which services to consult. " +
                        "Please mention the client name and what you need.");
                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                return;
            }

            List<AgentManifest> manifests = resolved.selected().stream()
                    .map(c -> c.manifest()).collect(Collectors.toList());
            String entityPrompt = buildEntityPrompt(latestPrompt, session);
            SynthesisResult synthesis = inputSynthesizer.synthesize(entityPrompt, manifests);

            if (synthesis.needsClarification() && synthesis.inputs().isEmpty()) {
                streamText(emitter, synthesis.clarificationMessage()); return;
            }
            if (synthesis.inputs().isEmpty()) {
                streamText(emitter, "Please specify the client name (e.g. 'Whitman Family Office')."); return;
            }

            Principal principal = principalStore.load(userId);
            String resolvedRelId = extractResolvedRelId(synthesis);
            if (resolvedRelId != null) {
                EntitlementResult ent = entitlementService.checkRelationship(principal, resolvedRelId);
                tracePublisher.publish(TraceEvent.of("entitlement_check", requestId,
                        new EntitlementCheckData(resolvedRelId, userId, ent.allowed(), ent.reason())));
                if (!ent.allowed()) {
                    streamText(emitter, "Access denied: you are not authorised to view relationship " +
                            resolvedRelId + ". This denial has been logged.");
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
            }

            List<PlanNode> nodes = synthesis.inputs().entrySet().stream()
                    .map(e -> {
                        AgentManifest m = manifests.stream()
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

            answerSynthesizer.synthesize(results, latestPrompt, request.messages(), emitter);

            tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart,
                            results.size(), (int) okCount)));
        } finally { span.end(); }
    }

    private void handleFollowUp(ChatRequest request, SseEmitter emitter,
                                 String latestPrompt, String conversationId,
                                 ConversationSession session, String userId,
                                 String requestId, long requestStart, Span rootSpan) throws Exception {
        Span span = tracer.spanBuilder("chat.follow_up").startSpan();
        try {
            if (session.hasFreshResults(RESULT_CACHE_TTL_MS)) {
                long ok = session.lastAgentResults().stream().filter(NodeResult::isOk).count();
                tracePublisher.publish(TraceEvent.of("synthesis_start", requestId,
                        new SynthesisStartData(session.lastAgentResults().size(), (int) ok)));
                answerSynthesizer.synthesize(session.lastAgentResults(), latestPrompt, request.messages(), emitter);
            } else if (session.relationshipId() != null) {
                String enriched = latestPrompt + " [session_relationship_id: " + session.relationshipId() + "]";
                handleFetchData(request, emitter, enriched, conversationId, session, userId, requestId, requestStart, rootSpan);
                return;
            } else {
                answerSynthesizer.synthesizeFromHistory(request.messages(), latestPrompt, emitter);
            }
            tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
        } finally { span.end(); }
    }

    private void handleClarify(SseEmitter emitter, ConversationSession session,
                                String userId, String requestId, long requestStart) throws Exception {
        Principal principal = principalStore.load(userId);
        List<String> allowedRels = entitlementService.filterCovered(principal, new java.util.ArrayList<>(REL_NAMES.keySet()));

        StringBuilder msg = new StringBuilder();
        if (session.relationshipId() != null) {
            msg.append("Could you clarify what you'd like to know about ")
               .append(REL_NAMES.getOrDefault(session.relationshipId(), session.relationshipId()))
               .append("? For example: holdings, performance, settlements, or tax lots.");
        } else if (!allowedRels.isEmpty()) {
            msg.append("Which client relationship are you asking about? You have access to:\n");
            for (String relId : allowedRels) {
                String name = REL_NAMES.getOrDefault(relId, relId);
                msg.append("  • ").append(name).append(" (").append(relId).append(")\n");
            }
            msg.append("\nReply with the client name or relationship ID to continue.");
        } else {
            msg.append("Which client or fund are you asking about? For example: 'Show me the holdings for Whitman Family Office'.");
        }
        streamText(emitter, msg.toString());
        tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
    }

    private void handleChitchat(ChatRequest request, SseEmitter emitter,
                                 String requestId, long requestStart) throws Exception {
        answerSynthesizer.synthesizeFromHistory(request.messages(), extractLatestUserMessage(request), emitter);
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

    private String deriveConversationId(ChatRequest request) {
        if (request.messages() == null || request.messages().isEmpty())
            return "unknown-" + UUID.randomUUID();
        return request.messages().stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null)
                .findFirst()
                .map(m -> "conv-" + Math.abs(m.content().hashCode()))
                .orElse("conv-" + UUID.randomUUID());
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

    private void streamText(SseEmitter emitter, String text) throws Exception {
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
