package ai.meridian.gateway.domain.chat;

import ai.meridian.gateway.api.v1.chat.dto.ChatRequest;
import ai.meridian.gateway.api.v1.chat.dto.Message;
import ai.meridian.gateway.domain.auth.EntitlementService;
import ai.meridian.gateway.domain.auth.EntitlementService.EntitlementResult;
import ai.meridian.gateway.domain.auth.Principal;
import ai.meridian.gateway.domain.auth.PrincipalStore;
import ai.meridian.gateway.domain.coverage.CoverageCheckResult;
import ai.meridian.gateway.domain.coverage.CoverageClient;
import ai.meridian.gateway.domain.coverage.CoverageResolveResult;
import ai.meridian.gateway.domain.coverage.CoverageResource;
import ai.meridian.gateway.domain.intent.Intent;
import ai.meridian.gateway.domain.intent.IntentClassifier;
import ai.meridian.gateway.domain.intent.IntentResult;
import ai.meridian.gateway.domain.manifest.DomainManifest;
import ai.meridian.gateway.domain.manifest.DomainManifestStore;
import ai.meridian.gateway.domain.manifest.EffectiveManifest;
import ai.meridian.gateway.domain.manifest.EntityType;
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
import io.micrometer.core.instrument.Timer;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

    private static final List<String> SUMMARIZATION_TRIGGERS = List.of(
            "summarize the above", "create a summary", "summarize this conversation",
            "provide a summary of the conversation", "tldr"
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
    private final CoverageClient           coverageClient;
    private final DomainManifestStore      manifestStore;
    private final Tracer                   tracer;
    private final MeterRegistry meterRegistry;
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
                       CoverageClient coverageClient,
                       DomainManifestStore manifestStore,
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
        this.coverageClient      = coverageClient;
        this.manifestStore       = manifestStore;
        this.tracer              = tracer;
        this.meterRegistry         = meterRegistry;
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

    public boolean isSummarizationRequest(ChatRequest request) {
        if (request.messages() == null) return false;
        return request.messages().stream()
                .filter(m -> "system".equals(m.role()) && m.content() != null)
                .map(m -> m.content().toLowerCase())
                .anyMatch(c -> SUMMARIZATION_TRIGGERS.stream().anyMatch(c::contains));
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
        // When LibreChat doesn't send X-Conversation-Id, derive a stable ID from
        // userId + first user message — LibreChat always includes full history so
        // the first message is the same across all turns of the same conversation.
        String conversationId = (headerConvId != null && !headerConvId.isBlank())
                ? headerConvId
                : deriveConversationId(userId, request);

        // ── Span attributes: visible on this span in Phoenix / Tempo ────────────
        rootSpan.setAttribute("session.id", conversationId);
        rootSpan.setAttribute("user.id", userId != null ? userId : "anonymous");
        rootSpan.setAttribute("meridian.request.id", requestId);

        // ── Langfuse session/trace mapping ──────────────────────────────────────
        // Langfuse's OTLP ingestion reads the `langfuse.*` span attributes. Mapping
        // the conversationId to langfuse.session.id makes every turn of a conversation
        // group under ONE Langfuse session, with each turn its own trace. This is the
        // conversationId → many-traces hierarchy: session = conversation, trace = turn,
        // observations = the stage/agent spans below. user.id ties traces to the principal.
        rootSpan.setAttribute("langfuse.session.id", conversationId);
        rootSpan.setAttribute("langfuse.user.id", userId != null ? userId : "anonymous");
        rootSpan.setAttribute("langfuse.trace.name", "chat-turn");
        rootSpan.setAttribute("langfuse.trace.input", latestPrompt);

        // ── W3C Baggage: propagates outward on every downstream HTTP call ─────
        String effectiveUserId = userId != null ? userId : "anonymous";
        Baggage baggage = Baggage.current().toBuilder()
                .put("session.id", conversationId)
                .put("user.id", effectiveUserId)
                .put("meridian.request.id", requestId)
                .build();
        // Make `chat.handle` the ACTIVE span for the whole request AND attach baggage in
        // one scope. Without `.with(rootSpan)` the root is never current, so every stage
        // span (intent.classify, llm.synthesize) and the fan-out's agent.invoke spans root
        // their own orphan traces instead of nesting — breaking the one-trace-per-turn tree.
        Scope baggageScope = baggage.storeInContext(Context.current().with(rootSpan)).makeCurrent();

        log.info("handleChat requestId={} userId={} conversationId={} prompt='{}'",
                requestId, userId, conversationId,
                latestPrompt.length() > 120 ? latestPrompt.substring(0, 120) + "..." : latestPrompt);

        tracePublisher.publish(TraceEvent.of("request_start", requestId, conversationId,
                new RequestStartData(userId, latestPrompt)));

        // streamId is set here and passed to all handlers so that every SSE chunk
        // in this request carries the same completion ID.  The role delta is now emitted
        // by the synthesizer (or streamTextAndComplete) rather than up-front, because
        // sending a role delta here and a second one inside streamTextAndComplete caused
        // LibreChat to track two separate in-flight completions — leaving the send button
        // disabled after short denial/clarification responses.
        String streamId = "chatcmpl-" + requestId.replace("-", "").substring(0, 32);

        try {
            IntentResult intentResult = intentClassifier.classify(request.messages());
            rootSpan.setAttribute("intent", intentResult.intent().name());
            incrementIntentCounter(intentResult.intent());
            tracePublisher.publish(TraceEvent.of("intent_classified", requestId,
                    new IntentClassifiedData(intentResult.intent().name(),
                            intentResult.confidence(), intentResult.reasoning())));

            ConversationSession session = sessionStore.load(conversationId);
            log.debug("Session: conversationId={} entities={} turns={}", conversationId,
                    session.resolvedEntities(), session.turnCount());

            // Gap 4: Intercept LibreChat's internal summarization call
            if (isSummarizationRequest(request)) {
                handleSummarization(emitter, session, requestId, requestStart, streamId);
                return;
            }

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
            emitRequestOutcome("ERROR");
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
        // coverageRelId is set by the coverage pipeline before synthesis;
        // it takes priority over the synthesis-extracted relationship ID when saving the session.
        String coverageRelId = null;
        // Deterministic CLARIFY (WORLD-B §4.2): which entity MUST resolve before fetch is a
        // manifest declaration (sub-domain required_context), NOT a Java rule. The coverage
        // pipeline below clarifies when this required resolvable entity is unresolved — that is
        // the generic `required ∩ resolved = ∅ → CLARIFY` check, manifest-declared.
        EntityType coverageEntity = coverageEntityType();
        EntityType secondaryEntity = secondaryEntityType();
        // Session-carried coverage id (resolved in a prior turn), keyed by manifest entity key.
        String carriedCoverageId = coverageEntity != null
                ? session.resolvedEntity(coverageEntity.key()) : null;
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
                emitRequestOutcome("FAILED");
                streamTextAndComplete(emitter, msg("needs_more_detail",
                        "I wasn't sure which services to consult. Please add more detail about what you need."));
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
                emitRequestOutcome("DENIED");
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

            // ── COVERAGE CHECK ──────────────────────────────────────────────────────
            // Find the first agent whose sub-domain is resource_scoped=true.
            // If found, run DISCOVER/RESOLVE/CHECK before synthesis so we never
            // waste agent calls on a relationship the RM doesn't cover.
            AgentManifest resourceScopedAgent = finalManifests.stream()
                .filter(m -> {
                    EffectiveManifest em = manifestStore.getEffective(
                        m.agentId(), m.domain(), m.subDomain());
                    return em.resourceScoped();
                })
                .findFirst().orElse(null);

            if (resourceScopedAgent != null) {
                EffectiveManifest em = manifestStore.getEffective(
                    resourceScopedAgent.agentId(),
                    resourceScopedAgent.domain(),
                    resourceScopedAgent.subDomain());
                DomainManifest.Coverage coverage = em.coverage();
                String tenantId = jwtPrincipal != null ? jwtPrincipal.tenantId() : "default";
                String principalId = principal.id();

                if (coverage != null) {
                    try {
                        if (carriedCoverageId != null) {
                            // Session already carries a verified relationship — just re-check.
                            CoverageCheckResult check = coverageClient.check(
                                principalId, tenantId, carriedCoverageId, coverage);
                            if (!check.allowed()) {
                                emitRequestOutcome("DENIED");
                                streamTextAndComplete(emitter, mapDenialReason(check.reason()));
                                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                return;
                            }
                            coverageRelId = carriedCoverageId;

                        } else if (coverageEntity != null && preExtracted != null
                                && preExtracted.reference(coverageEntity.extractAs()) != null) {
                            // RM named a client — resolve the text to a canonical relationship ID.
                            CoverageResolveResult resolveResult = coverageClient.resolve(
                                preExtracted.reference(coverageEntity.extractAs()),
                                coverageEntity.resolveType(), principalId, coverage);

                            if (resolveResult.resolved()) {
                                coverageRelId = resolveResult.id();
                                CoverageCheckResult check = coverageClient.check(
                                    principalId, tenantId, coverageRelId, coverage);
                                if (!check.allowed()) {
                                    emitRequestOutcome("DENIED");
                                    streamTextAndComplete(emitter, mapDenialReason(check.reason()));
                                    tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                    return;
                                }

                            } else if (resolveResult.isAmbiguous()) {
                                // Multiple matches — discover what's in RM's coverage and intersect.
                                List<CoverageResource> discovered = coverageClient.discover(
                                    principalId, tenantId, coverage);
                                List<CoverageResolveResult.ResolveCandidate> filtered =
                                    resolveResult.candidates().stream()
                                        .filter(c -> discovered.stream()
                                            .anyMatch(d -> d.id().equals(c.id())))
                                        .collect(Collectors.toList());
                                String question = buildClarificationQuestion(em, filtered, discovered);
                                emitRequestOutcome("CLARIFIED");
                                streamTextAndComplete(emitter, question);
                                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                return;

                            } else {
                                // Not found in coverage at all.
                                emitRequestOutcome("CLARIFIED");
                                streamTextAndComplete(emitter, msg("reference_not_found",
                                    "I could not find a match for that reference. Please provide a specific identifier.")
                                    .replace("{reference}", preExtracted.reference(coverageEntity.extractAs())));
                                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                return;
                            }

                        } else {
                            // No reference at all — discover what this RM covers.
                            List<CoverageResource> discovered = coverageClient.discover(
                                principalId, tenantId, coverage);

                            if (discovered.isEmpty()) {
                                emitRequestOutcome("DENIED");
                                streamTextAndComplete(emitter, msg("no_coverage",
                                    "Your coverage set is empty."));
                                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                return;

                            } else if (discovered.size() == 1) {
                                coverageRelId = discovered.get(0).id();
                                CoverageCheckResult check = coverageClient.check(
                                    principalId, tenantId, coverageRelId, coverage);
                                if (!check.allowed()) {
                                    emitRequestOutcome("DENIED");
                                    streamTextAndComplete(emitter, mapDenialReason(check.reason()));
                                    tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                    return;
                                }
                                // Single relationship auto-selected — will be persisted in session via finalRelId.
                                log.info("Coverage: auto-selected single relationship={} for principal={}",
                                    coverageRelId, principalId);

                            } else {
                                // Multiple relationships — ask the RM to choose.
                                String question = buildClarificationQuestion(em, List.of(), discovered);
                                emitRequestOutcome("CLARIFIED");
                                streamTextAndComplete(emitter, question);
                                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                return;
                            }
                        }

                    } catch (CoverageClient.CoverageUnavailableException e) {
                        log.error("Coverage service unavailable for principal={}: {}", principal.id(), e.getMessage());
                        emitRequestOutcome("FAILED");
                        streamTextAndComplete(emitter,
                            "I am unable to process your request right now. Please try again shortly.");
                        tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                        return;
                    }
                }
            }
            // ── END COVERAGE CHECK ──────────────────────────────────────────────────

            // Use pre-extracted entities from the combined intent+entity LLM call if available.
            // Priority: coverage-resolved ID > session carry-forward > raw reference (needs EntityResolver).
            // When coverageRelId is set, the coverage pipeline already resolved the name to a canonical ID
            // (e.g. "Whitman Family Office" → "REL-00042"). Injecting the ID here makes EntityResolver
            // short-circuit via its REL-\d+ pattern match — no call to the external resolve endpoint needed.
            SynthesisResult synthesis;
            if (preExtracted != null) {
                EntityBag effectiveBag = preExtracted;
                if (coverageEntity != null) {
                    String covExtractAs = coverageEntity.extractAs();
                    if (coverageRelId != null) {
                        // Inject the coverage-verified canonical ID as the entity reference so
                        // the resolver short-circuits via its id_pattern (no external lookup).
                        effectiveBag = preExtracted.withReference(covExtractAs, coverageRelId);
                    } else if (preExtracted.reference(covExtractAs) == null
                            && carriedCoverageId != null) {
                        effectiveBag = preExtracted.withReference(covExtractAs, carriedCoverageId);
                    }
                }
                synthesis = inputSynthesizer.synthesize(effectiveBag, finalManifests);
            } else {
                String entityPrompt = buildEntityPrompt(latestPrompt, session);
                synthesis = inputSynthesizer.synthesize(entityPrompt, finalManifests);
            }

            if (synthesis.needsClarification() && synthesis.inputs().isEmpty()) {
                emitRequestOutcome("CLARIFIED");
                streamTextAndComplete(emitter, synthesis.clarificationMessage()); return;
            }
            if (synthesis.inputs().isEmpty()) {
                emitRequestOutcome("FAILED");
                streamTextAndComplete(emitter, msg("specify_entity",
                        "Please specify the resource identifier.")); return;
            }

            String resolvedRelId = extractResolvedId(synthesis, coverageEntity);
            if (resolvedRelId != null) {
                EntitlementResult ent = entitlementService.checkRelationship(principal, resolvedRelId);
                tracePublisher.publish(TraceEvent.of("entitlement_check", requestId,
                        new EntitlementCheckData(resolvedRelId, userId, ent.allowed(), ent.reason(), ent.source())));
                if (!ent.allowed()) {
                    emitRequestOutcome("DENIED");
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

            long fanoutStart = System.currentTimeMillis();
            List<NodeResult> results = executor.execute(new Plan(nodes));
            long fanoutElapsedMs = System.currentTimeMillis() - fanoutStart;
            Timer.builder("meridian.fanout.duration")
                    .description("Time from routing decision to all agents completing")
                    .tag("agent_count", String.valueOf(nodes.size()))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(fanoutElapsedMs, TimeUnit.MILLISECONDS);

            results.forEach(r -> {
                String prev = r.isOk() && r.data() != null
                        ? r.data().toString().substring(0, Math.min(80, r.data().toString().length())) : r.status().name();
                tracePublisher.publish(TraceEvent.of("agent_complete", requestId,
                        new AgentCompleteData(r.agentId(), r.latencyMs(), r.isOk() ? "ok" : "failed", prev)));
            });

            long okCount = results.stream().filter(NodeResult::isOk).count();
            log.info("Fan-out complete {}/{} ok", okCount, results.size());

            // Prefer the coverage-verified relationship ID over the synthesis-extracted one.
            String finalRelId = coverageRelId != null ? coverageRelId : extractResolvedId(synthesis, coverageEntity);
            Map<String, String> resolvedEntities = new java.util.HashMap<>();
            if (coverageEntity != null && finalRelId != null) {
                resolvedEntities.put(coverageEntity.key(), finalRelId);
            }
            if (secondaryEntity != null) {
                String secId = extractResolvedId(synthesis, secondaryEntity);
                if (secId != null) resolvedEntities.put(secondaryEntity.key(), secId);
            }
            sessionStore.save(session.withResults(resolvedEntities, results));

            tracePublisher.publish(TraceEvent.of("synthesis_start", requestId,
                    new SynthesisStartData(results.size(), (int) okCount)));

            answerSynthesizer.synthesize(results, latestPrompt, request.messages(), emitter, streamId);
            emitRequestOutcome("ANSWERED");

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
                emitRequestOutcome("ANSWERED");
                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, total, (int) ok)));
            } else if (sessionCoverageEntity(session) != null) {
                EntityType cov = coverageEntityType();
                String enriched = latestPrompt + " [session_" + cov.key() + ": "
                        + session.resolvedEntity(cov.key()) + "]";
                handleFetchData(request, emitter, enriched, conversationId, session, userId, jwtPrincipal, requestId, requestStart, rootSpan, null, streamId);
                return;
            } else {
                answerSynthesizer.synthesizeFromHistory(request.messages(), latestPrompt, emitter, streamId);
                emitRequestOutcome("ANSWERED");
                tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
            }
        } finally { span.end(); }
    }

    private void handleClarify(SseEmitter emitter, ConversationSession session,
                                String userId, Principal jwtPrincipal,
                                String requestId, long requestStart) throws Exception {
        Principal principal = (jwtPrincipal != null) ? jwtPrincipal : principalStore.load(userId);

        String message;
        String covId = sessionCoverageEntity(session);
        if (covId != null) {
            message = msg("followup_clarification",
                    "Could you clarify what you'd like to know about {entity}? Please add more detail.")
                    .replace("{entity}", covId);
        } else {
            message = msg("missing_entity_question",
                    "Which resource are you asking about? Please provide its identifier to continue.");
        }
        emitRequestOutcome("CLARIFIED");
        streamTextAndComplete(emitter, message);
        tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
    }

    private void handleChitchat(ChatRequest request, SseEmitter emitter,
                                 String requestId, long requestStart, String streamId) throws Exception {
        answerSynthesizer.synthesizeFromHistory(request.messages(), extractLatestUserMessage(request), emitter, streamId);
        emitRequestOutcome("ANSWERED");
        tracePublisher.publish(TraceEvent.of("request_complete", requestId,
                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
    }

    private void handleSummarization(SseEmitter emitter, ConversationSession session,
                                      String requestId, long requestStart, String streamId) throws Exception {
        StringBuilder summary = new StringBuilder();
        summary.append("Conversation summary:\n");
        String covId = sessionCoverageEntity(session);
        if (covId != null) {
            EntityType cov = coverageEntityType();
            String label = (cov != null && cov.display() != null && !cov.display().isBlank())
                    ? cov.display() : "Entity";
            summary.append("- ").append(label).append(": ").append(covId);
            if (session.clientName() != null) summary.append(" (").append(session.clientName()).append(")");
            summary.append("\n");
        }
        if (session.domain() != null) summary.append("- Domain: ").append(session.domain()).append("\n");
        if (session.timePeriod() != null) summary.append("- Period: ").append(session.timePeriod()).append("\n");
        summary.append("- Turns completed: ").append(session.turnCount());
        emitRequestOutcome("ANSWERED");
        streamTextAndComplete(emitter, summary.toString());
        tracePublisher.publish(TraceEvent.of("request_complete", requestId,
            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
    }

    // ── Coverage helper methods ────────────────────────────────────────────────

    /**
     * Maps a machine-readable denial reason code from the coverage service to a
     * human-readable message suitable for streaming to the end user.
     */
    private String mapDenialReason(String reason) {
        // Reason codes (not-covered, coverage-transferred, relationship-closed) are the stable
        // contract; their user-facing TEXT is declared in the sub-domain manifest's
        // denial_messages map. The gateway holds no domain copy (WORLD-B §5).
        String text = manifestStore.denialMessage(reason);
        return text != null ? text : "Access to the requested resource was denied.";
    }

    /** Returns a manifest-declared user-facing message, falling back to a domain-neutral default. */
    private String msg(String key, String fallback) {
        String m = manifestStore.message(key);
        return (m != null && !m.isBlank()) ? m : fallback;
    }

    /**
     * Builds a clarification question presenting the RM with a numbered list of
     * client relationships to choose from.
     *
     * @param em         the effective manifest (provides the question template from clarification schema)
     * @param candidates resolve candidates already known (may be empty — falls back to discovered)
     * @param discovered full discovered list for this RM
     */
    private String buildClarificationQuestion(EffectiveManifest em,
                                               List<? extends Object> candidates,
                                               List<CoverageResource> discovered) {
        ai.meridian.gateway.domain.manifest.ClarificationSchema cs =
                em.clarificationFor(em.primaryRequiredKey());
        String questionText = (cs != null && cs.question() != null && !cs.question().isBlank())
            ? cs.question()
            : msg("missing_entity_question", "Which resource are you asking about?");

        // Use discovered list when candidates is empty or no match narrowing was done.
        List<CoverageResource> options = discovered.isEmpty()
            ? discovered
            : (candidates.isEmpty() ? discovered : discovered); // always show full discovered list

        StringBuilder sb = new StringBuilder(questionText).append("\n");
        int i = 1;
        for (CoverageResource r : options) {
            sb.append(i++).append(". ").append(r.label())
              .append(" (").append(r.id()).append(")").append("\n");
        }
        sb.append("\nReply with the number or relationship ID.");
        return sb.toString();
    }

    // ── Private utilities ─────────────────────────────────────────────────────

    private String extractLatestUserMessage(ChatRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) return "";
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Message m = request.messages().get(i);
            if ("user".equals(m.role()) && m.content() != null) return m.content();
        }
        return "";
    }

    private String buildEntityPrompt(String latestPrompt, ConversationSession session) {
        if (!session.hasResolvedEntities()) return latestPrompt;
        StringBuilder sb = new StringBuilder(latestPrompt);
        // Emit one [session_<entityKey>: <id>] hint per carried entity. The token name is the
        // manifest entity key (data), so adding an entity type needs no gateway change.
        session.resolvedEntities().forEach((k, v) -> {
            if (k != null && v != null) {
                sb.append(" [session_").append(k).append(": ").append(v).append("]");
            }
        });
        return sb.toString();
    }

    /**
     * The manifest-declared, resource-scoped (required) resolvable entity type — the one a
     * coverage/entitlement check gates on. Derived from the manifest, never hardcoded.
     */
    private EntityType coverageEntityType() {
        java.util.List<String> requiredKeys = manifestStore.requiredContextKeys();
        return manifestStore.entityTypes().stream()
                .filter(EntityType::isResolvable)
                .filter(et -> requiredKeys.contains(et.key()))
                .findFirst().orElse(null);
    }

    /** The first non-required resolvable entity type (e.g. a secondary lookup), or null. */
    private EntityType secondaryEntityType() {
        java.util.List<String> requiredKeys = manifestStore.requiredContextKeys();
        return manifestStore.entityTypes().stream()
                .filter(EntityType::isResolvable)
                .filter(et -> !requiredKeys.contains(et.key()))
                .findFirst().orElse(null);
    }

    /** The session-carried resolved id for the coverage entity, or null. */
    private String sessionCoverageEntity(ConversationSession session) {
        EntityType ce = coverageEntityType();
        return ce != null ? session.resolvedEntity(ce.key()) : null;
    }

    /**
     * Extracts the resolved id for an entity type from the bound agent inputs. The field name
     * (entity {@code key}) and the validity check ({@code id_pattern}) both come from the
     * manifest — no hardcoded field names or ID prefixes.
     */
    private String extractResolvedId(SynthesisResult synthesis, EntityType et) {
        if (et == null) return null;
        return synthesis.inputs().values().stream()
                .map(node -> node.path(et.key()).asText(null))
                .filter(v -> v != null && !v.isBlank())
                .filter(v -> et.idPattern() == null || et.idPattern().isBlank()
                        || v.toUpperCase().matches(et.idPattern()))
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

    /** Increments the request outcome counter. Micrometer caches the Counter by key. */
    private void emitRequestOutcome(String outcome) {
        Counter.builder("meridian.request.outcome")
                .description("Request resolution outcome")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private static String newId() { return "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""); }
    private static long epochSeconds() { return System.currentTimeMillis() / 1_000L; }

    /**
     * Derives a stable conversation ID when LibreChat does not send X-Conversation-Id.
     *
     * LibreChat includes the full message history in every request, so messages[0]
     * (the first user message) is constant across all turns of the same conversation.
     * Hashing userId + firstUserMessage gives a consistent key for session lookup.
     */
    private static String deriveConversationId(String userId, ChatRequest request) {
        if (request.messages() != null && !request.messages().isEmpty()) {
            String firstUserContent = request.messages().stream()
                    .filter(m -> "user".equals(m.role()) && m.content() != null)
                    .map(Message::content)
                    .findFirst()
                    .orElse("");
            if (!firstUserContent.isBlank()) {
                String key = (userId != null ? userId : "anon") + ":" + firstUserContent;
                return "conv-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            }
        }
        return "conv-" + UUID.randomUUID();
    }
}
