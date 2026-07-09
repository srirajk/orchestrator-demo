package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.api.v1.chat.dto.ChatRequest;
import ai.conduit.gateway.api.v1.chat.dto.Message;
import ai.conduit.gateway.domain.clarify.ClarificationComposer;
import ai.conduit.gateway.domain.auth.EntitlementService;
import ai.conduit.gateway.domain.auth.EntitlementService.EntitlementResult;
import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.coverage.CoverageCheckResult;
import ai.conduit.gateway.domain.coverage.CoverageClient;
import ai.conduit.gateway.domain.coverage.CoverageResolveResult;
import ai.conduit.gateway.domain.coverage.CoverageResource;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EffectiveManifest;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.conduit.gateway.infrastructure.telemetry.event.AgentCompleteData;
import ai.conduit.gateway.infrastructure.telemetry.event.AgentStartData;
import ai.conduit.gateway.infrastructure.telemetry.event.AgentsResolvedData;
import ai.conduit.gateway.infrastructure.telemetry.event.CheckDeniedData;
import ai.conduit.gateway.infrastructure.telemetry.event.EntitlementCheckData;
import ai.conduit.gateway.infrastructure.telemetry.event.GateData;
import ai.conduit.gateway.infrastructure.telemetry.event.GroundedFiguresData;
import ai.conduit.gateway.infrastructure.telemetry.event.IntentClassifiedData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestCompleteData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData;
import ai.conduit.gateway.infrastructure.telemetry.event.SynthesisStartData;
import ai.conduit.gateway.infrastructure.telemetry.event.PlanGraphData;
import ai.conduit.gateway.orchestration.executor.Blackboard;
import ai.conduit.gateway.orchestration.executor.DagPlanExecutor;
import ai.conduit.gateway.orchestration.executor.FlatPlanExecutor;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.orchestration.planner.DagResolution;
import ai.conduit.gateway.orchestration.planner.DagResolver;
import ai.conduit.gateway.orchestration.planner.ResolutionError;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.service.AgentRegistry;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import ai.conduit.gateway.synthesis.answer.AnswerSynthesizer;
import ai.conduit.gateway.synthesis.answer.GroundedFigure;
import ai.conduit.gateway.synthesis.answer.GroundedFigureRenderer;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.EntityResolver;
import ai.conduit.gateway.synthesis.input.InputSynthesizer;
import ai.conduit.gateway.synthesis.input.InputSynthesizer.SynthesisResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    static final String MODEL_ID = "conduit-assistant";

    /**
     * How many recent user turns (including the current one) are concatenated into the routing
     * query so a keyword-less follow-up inherits the conversation's domain vocabulary. Bounded so a
     * long conversation cannot drift the routed domain; recent turns dominate. Configuration, not a
     * constant (CLAUDE.md §5) — tunable without a rebuild.
     */
    @org.springframework.beans.factory.annotation.Value("${conduit.chat.routing-context-turns:4}")
    private int routingContextTurns;

    private static final List<String> TITLE_TRIGGERS = List.of(
            "generate a concise", "generate a short", "provide a title",
            "name this conversation", "title for this"
    );

    private final ObjectMapper             mapper;
    private final IntentClassifier         intentClassifier;
    private final AgentResolver            resolver;
    private final InputSynthesizer         inputSynthesizer;
    private final FlatPlanExecutor         executor;
    private final AnswerSynthesizer        answerSynthesizer;
    private final GroundedFigureRenderer   figureRenderer;
    private final EntitlementService       entitlementService;
    private final TraceEventPublisher      tracePublisher;
    private final CoverageClient           coverageClient;
    private final DomainManifestStore      manifestStore;
    private final ai.conduit.gateway.domain.clarify.ClarificationComposer clarificationComposer;
    private final Tracer                   tracer;
    private final MeterRegistry meterRegistry;
    private final Counter intentFetchCounter;
    private final Counter intentFollowUpCounter;
    private final Counter intentClarifyCounter;
    private final Counter intentChitchatCounter;

    // ── Multi-step orchestration (feature-flagged; flag OFF ⇒ flat path unchanged) ──────────
    private final AgentRegistry     registry;
    private final DagResolver       dagResolver;
    private final DagPlanExecutor   dagExecutor;
    private final EntityResolver    entityResolver;
    /**
     * When true, a fan-in ("goal") capability whose io consumes another capability's output is
     * executed as a resolver-derived DAG (producers→consumer) instead of a flat fan-out. Any miss
     * (goal not uniquely identifiable, unresolved dependency, authz re-gate prune, unbound leaf)
     * falls through to the unchanged flat path. Default OFF — a no-op until explicitly enabled.
     */
    @org.springframework.beans.factory.annotation.Value("${conduit.orchestration.dag-enabled:false}")
    private boolean dagEnabled;

    public ChatService(ObjectMapper mapper,
                       IntentClassifier intentClassifier,
                       AgentResolver resolver,
                       InputSynthesizer inputSynthesizer,
                       FlatPlanExecutor executor,
                       AnswerSynthesizer answerSynthesizer,
                       GroundedFigureRenderer figureRenderer,
                       EntitlementService entitlementService,
                       TraceEventPublisher tracePublisher,
                       CoverageClient coverageClient,
                       DomainManifestStore manifestStore,
                       ai.conduit.gateway.domain.clarify.ClarificationComposer clarificationComposer,
                       Tracer tracer,
                       MeterRegistry meterRegistry,
                       AgentRegistry registry,
                       DagResolver dagResolver,
                       DagPlanExecutor dagExecutor,
                       EntityResolver entityResolver) {
        this.mapper              = mapper;
        this.intentClassifier    = intentClassifier;
        this.resolver            = resolver;
        this.inputSynthesizer    = inputSynthesizer;
        this.executor            = executor;
        this.answerSynthesizer   = answerSynthesizer;
        this.figureRenderer      = figureRenderer;
        this.entitlementService  = entitlementService;
        this.tracePublisher      = tracePublisher;
        this.coverageClient      = coverageClient;
        this.manifestStore       = manifestStore;
        this.clarificationComposer = clarificationComposer;
        this.tracer              = tracer;
        this.meterRegistry         = meterRegistry;
        this.registry            = registry;
        this.dagResolver         = dagResolver;
        this.dagExecutor         = dagExecutor;
        this.entityResolver      = entityResolver;
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
            emitter.send(SseEmitter.event().data(" [DONE]"));
            emitter.complete();
        } catch (Exception e) { emitter.completeWithError(e); }
    }

    /**
     * Entry point from the controller — called on a virtual thread after the async boundary.
     *
     * @param jwtPrincipal      the JWT-verified principal captured on the servlet thread
     *                          before {@code CompletableFuture.runAsync()}; null if no JWT
     * @param headerConvId      value of the {@code X-Conversation-Id} header, or null
     * @param callerToken       the caller's raw verified bearer token, captured on the servlet
     *                          thread alongside {@code jwtPrincipal} (F-IDENTITY). Threaded through
     *                          to every downstream agent invocation instead of being read from
     *                          {@code SecurityContextHolder}, which does not survive the hop onto
     *                          the pipeline/DAG virtual-thread executors. Null if no JWT.
     */
    public void handleChat(ChatRequest request, SseEmitter emitter, String userId,
                           Principal jwtPrincipal, String headerConvId, String callerToken) {
        long   requestStart = System.currentTimeMillis();
        emitAdoption(jwtPrincipal);   // adoption-by-role (cardinality-safe: role, not user)
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
        // conversation.id: keys the root/request span on the CONVERSATION (not the client),
        // so Tempo can be searched by { span.conversation.id = "<convId>" } — this is what the
        // Conversation-Trace board's trace panel queries. Domain-agnostic (an opaque id, World B).
        rootSpan.setAttribute("conversation.id", conversationId);
        rootSpan.setAttribute("user.id", userId != null ? userId : "anonymous");
        rootSpan.setAttribute("conduit.request.id", requestId);

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

        // ── Slicing dimensions (Phase 2): canonical user_id + segment tags ──────
        // Make cost/tokens/traces sliceable by principal (user_id) and by business
        // segment (segment:* tags). Every value comes from the verified JWT principal —
        // no hardcoded segment/domain literal (World B). These base tags are set on ALL
        // paths (chitchat, follow-up, early denials); the FETCH_DATA path augments the
        // same tag array with domain:/agent: chips below (it re-reads segmentTags()).
        rootSpan.setAttribute("langfuse.trace.user_id", userId != null ? userId : "anonymous");
        List<String> segTags = segmentTags(jwtPrincipal);
        if (!segTags.isEmpty()) {
            rootSpan.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), segTags);
            rootSpan.setAttribute("langfuse.metadata.segment",
                    String.join(",", jwtPrincipal.segments().keySet()));
        }

        // ── W3C Baggage: propagates outward on every downstream HTTP call ─────
        String effectiveUserId = userId != null ? userId : "anonymous";
        Baggage baggage = Baggage.current().toBuilder()
                .put("session.id", conversationId)
                .put("user.id", effectiveUserId)
                .put("conduit.request.id", requestId)
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
            tracePublisher.publish(TraceEvent.of("intent_classified", requestId, conversationId,
                    new IntentClassifiedData(intentResult.intent().name(),
                            intentResult.confidence(), intentResult.reasoning())));

            switch (intentResult.intent()) {
                case FETCH_DATA -> handleFetchData(request, emitter, latestPrompt,
                        conversationId, userId, jwtPrincipal, requestId, requestStart, rootSpan,
                        intentResult.extractedEntities(), streamId, true, callerToken);
                case FOLLOW_UP  -> handleFollowUp(request, emitter, latestPrompt,
                        conversationId, userId, jwtPrincipal, requestId, requestStart, rootSpan,
                        intentResult.extractedEntities(), streamId, callerToken);
                // CLARIFY is routed through the SAME deterministic coverage path as FETCH_DATA
                // (hard-rule e): the LLM never decides clarification. The coverage
                // discover/intersect/`required ∩ resolved = ∅` check inside handleFetchData
                // produces the proper grounded clarification from the RM's book — not a bare,
                // LLM-judged manifest message.
                //
                // carryContext=false: a CLARIFY turn is, by the classifier's own determination, a
                // terse under-specified ask ("Calderon Trust?") with no actionable request. We route
                // it on the bare message only — NOT enriched with prior-turn domain vocabulary — so
                // it cannot inherit a confident domain from history and be answered; it stays a
                // clarification. Enriching it would collapse the FETCH_DATA/CLARIFY distinction.
                case CLARIFY    -> handleFetchData(request, emitter, latestPrompt,
                        conversationId, userId, jwtPrincipal, requestId, requestStart, rootSpan,
                        intentResult.extractedEntities(), streamId, false, callerToken);
                case CHITCHAT   -> handleChitchat(request, emitter, conversationId, requestId, requestStart, streamId, rootSpan);
            }

        } catch (Exception e) {
            log.error("Chat pipeline failed requestId={}", requestId, e);
            rootSpan.setStatus(StatusCode.ERROR, e.getMessage());
            emitRequestOutcome("ERROR");
            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
            try { streamTextAndComplete(emitter, "An internal error occurred. Please try again.", streamId); }
            catch (Exception ex) { try { emitter.completeWithError(ex); } catch (Exception ignored) {} }
        } finally {
            baggageScope.close();
            rootSpan.end();
        }
    }

    private void handleFetchData(ChatRequest request, SseEmitter emitter,
                                  String latestPrompt, String conversationId,
                                  String userId,
                                  Principal jwtPrincipal,
                                  String requestId, long requestStart, Span rootSpan,
                                  EntityBag preExtracted, String streamId,
                                  boolean carryContext, String callerToken) throws Exception {
        Span span = tracer.spanBuilder("chat.fetch_data").startSpan();
        Scope fetchScope = span.makeCurrent();
        // coverageRelId is set by the coverage pipeline before synthesis.
        String coverageRelId = null;
        // Deterministic CLARIFY (WORLD-B §4.2): which entity MUST resolve before fetch is a
        // manifest declaration (sub-domain required_context), NOT a Java rule. The coverage
        // pipeline below clarifies when this required resolvable entity is unresolved — that is
        // the generic `required ∩ resolved = ∅ → CLARIFY` check, manifest-declared.
        //
        // These start from the cross-domain UNION (global fallback). Once resolution identifies
        // the routed sub-domain (its EffectiveManifest), they are RE-SCOPED to that sub-domain's
        // required_context/entity_types below — so a multi-domain registry gates on the right
        // entity (e.g. the insurance required entity for an insurance query) rather than the
        // union's first key. They are plain locals (captured by no lambda) so reassignment is safe.
        EntityType coverageEntity = coverageEntityType();
        try {
            // ── DETERMINISTIC IDENTIFIER PRE-CHECK ───────────────────────────────────
            // A bare identifier ("REL-00188?") carries no domain vocabulary, so embedding routing
            // scores it at noise level and can pick an unrelated domain — surfacing the WRONG
            // domain's copy ("which policy?" for a wealth id). But the id's manifest id_pattern names
            // its entity type — and hence its domain — exactly. When such an id RESOLVES
            // (principal-agnostically, World-B rule f) and the caller is NOT entitled, deny with THAT
            // domain's own copy, deterministically, before routing. Only a resolved-then-denied
            // verdict short-circuits; not-found (fake id), ambiguous, or allowed all fall through to
            // the normal pipeline unchanged. Manifest-driven (id_pattern + resolve_type + coverage) —
            // no id prefix or domain literal in the gateway.
            java.util.Optional<ai.conduit.gateway.domain.manifest.DomainManifestStore.IdentifiedReference>
                    idRef = manifestStore.identifyByIdPattern(latestPrompt);
            if (idRef.isPresent() && idRef.get().coverage() != null) {
                var ir = idRef.get();
                Principal p0 = (jwtPrincipal != null) ? jwtPrincipal : Principal.anonymous();
                String t0 = jwtPrincipal != null ? jwtPrincipal.tenantId() : "default";
                String sub0 = ir.subDomain().subDomainId();
                try {
                    CoverageResolveResult rr = coverageClient.resolve(
                        ir.id(), ir.entityType().resolveType(), t0, ir.coverage(), callerToken);
                    if (rr.resolved() && rr.id() != null) {
                        CoverageCheckResult check = coverageClient.check(p0.id(), t0, rr.id(), ir.coverage(), callerToken);
                        if (!check.allowed()) {
                            emitRequestOutcome("DENIED");
                            tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                                GateData.deny(GateData.GATE_COVERAGE,
                                    rr.id() + " not in " + p0.id() + "'s book", sub0)));
                            tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                                new CheckDeniedData("coverage", rr.id(), p0.id(), check.reason(), "coverage")));
                            streamTextAndComplete(emitter, mapDenialReason(check.reason(), sub0), streamId);
                            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                            return;
                        }
                    } else {
                        emitRequestOutcome("DENIED");
                        tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                            GateData.deny(GateData.GATE_COVERAGE,
                                ir.id() + " could not be resolved", sub0)));
                        tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                            new CheckDeniedData("coverage", ir.id(), p0.id(), "unresolved-entity", "coverage")));
                        streamTextAndComplete(emitter, mapDenialReason("unknown-resource", sub0), streamId);
                        tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                        return;
                    }
                } catch (CoverageClient.CoverageUnavailableException e) {
                    log.error("Identifier pre-check coverage unavailable for principal={}: {}",
                            p0.id(), e.getMessage());
                    emitRequestOutcome("DENIED");
                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                            new CheckDeniedData("coverage", ir.id(), p0.id(), "coverage-unavailable", "coverage")));
                    streamTextAndComplete(emitter,
                            "I am unable to verify your coverage right now, so I cannot provide that data.", streamId);
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
            }

            // ── CONTEXT-AWARE ROUTING ────────────────────────────────────────────────
            // Route with the smallest context needed for this turn. If the existing focal-reference
            // derivation already grounded the entity and the user did NOT type a fresh identifier,
            // the current message supplies the facet and should not be diluted by older turns. If
            // the user did type a fresh identifier, use only the immediately prior user turn plus
            // the current turn so terse overrides can inherit the prior facet without stale facets.
            // CLARIFY turns route on the bare message so an under-specified ask cannot inherit a
            // confident domain.
            boolean entityKnown = carryContext && hasGroundedResolvableReference(preExtracted, latestPrompt);
            String routingText = routingTextForFetch(request, latestPrompt, carryContext, entityKnown);
            ResolverResult resolved = resolver.resolveContextual(routingText, entityKnown);

            List<AgentsResolvedData.AgentRef> selectedRefs = resolved.selected().stream()
                    .map(c -> new AgentsResolvedData.AgentRef(
                            c.manifest().agentId(), c.manifest().protocol(), c.score()))
                    .collect(Collectors.toList());
            List<AgentsResolvedData.FilteredRef> skippedRefs = resolved.skipped().stream()
                    .map(c -> new AgentsResolvedData.FilteredRef(
                            c.manifest().agentId(), "below-confidence-floor"))
                    .collect(Collectors.toList());
            tracePublisher.publish(TraceEvent.of("agents_resolved", requestId, conversationId,
                    new AgentsResolvedData(selectedRefs, skippedRefs)));
            annotateRoutingDecision(span, rootSpan, resolved);

            if (resolved.fallback() || resolved.selected().isEmpty()) {
                // BIAS-TO-FETCH / graceful degrade: routing abstained (a vague or aggregate ask
                // with no confident single route — e.g. "compare cash across both clients",
                // "total value across my book"). On the context-carrying FETCH path, if the
                // conversation already holds relevant data, answer from that context instead of
                // punting — the grounded history synthesis compares/aggregates what was shown and
                // asks for detail only if it genuinely cannot. CLARIFY-intent turns (carryContext
                // false) keep the deterministic clarification so a bare under-specified ask still
                // clarifies (bug-232 behaviour preserved).
                if (carryContext && hasPriorAssistantData(request)) {
                    String answer = answerSynthesizer.synthesizeFromHistory(request.messages(), latestPrompt, emitter, streamId, requestStart);
                    setTraceOutput(rootSpan, answer);
                    emitRequestOutcome("ANSWERED");
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
                emitRequestOutcome("FAILED");
                streamTextAndComplete(emitter, msg("needs_more_detail",
                        "I wasn't sure which services to consult. Please add more detail about what you need."), streamId);
                tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                return;
            }

            // Identity comes ONLY from the verified JWT. The legacy X-User-Id → Redis
            // PrincipalStore fallback was removed (identity-spoofing hole); an unauthenticated
            // caller is anonymous with no coverage, so every entity check denies.
            Principal principal = (jwtPrincipal != null)
                    ? jwtPrincipal
                    : Principal.anonymous();

            // Prune agents the principal is not permitted to invoke (domain + classification).
            // Must happen BEFORE input synthesis so we never synthesize inputs for denied agents.
            List<AgentManifest> manifests = resolved.selected().stream()
                    .map(c -> c.manifest()).collect(Collectors.toList());

            // ── STRUCTURAL GATE TRACE (audience → segment → classification) ──────────
            // Emit one ordered `gate` frame per gate/agent so the glass box renders the
            // authorization decision as a legible step-by-step trace with each gate's
            // pass/deny + plain-english reason. This EXPLAINS the Cerbos verdict enforced
            // just below by filterAgents. Reasons are built from manifest/principal data
            // (segment, tier, classification) — no hardcoded domain literal (World B).
            entitlementService.explainStructuralGates(principal, manifests)
                    .forEach(g -> tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                            new GateData(g.gate(), g.allow() ? GateData.EFFECT_ALLOW : GateData.EFFECT_DENY,
                                    g.reason(), g.agent()))));

            List<AgentManifest> allowedManifests = entitlementService.filterAgents(principal, manifests);
            if (allowedManifests.isEmpty()) {
                emitRequestOutcome("DENIED");
                // Glass-box: make the structural (Cerbos agent-invoke) deny explicit in the trace
                // instead of jumping agents_resolved → request_complete. (bug 239)
                tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                        new CheckDeniedData("structural", null, userId, "no-covered-agents", "cerbos")));
                streamTextAndComplete(emitter, "You do not have access to any of the required services for this query.", streamId);
                tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                return;
            }
            if (allowedManifests.size() < manifests.size()) {
                log.info("Agent pruning: {}/{} agents allowed for principal={}",
                        allowedManifests.size(), manifests.size(), principal.id());
            }
            final List<AgentManifest> finalManifests = allowedManifests;
            final List<String> finalDomains = finalManifests.stream()
                    .map(AgentManifest::domain)
                    .filter(d -> d != null && !d.isBlank())
                    .distinct()
                    .toList();

            // Domains the request referenced but the structural entitlement gate pruned (outside the
            // caller's access). When the SAME ask mixed an accessible and an inaccessible domain
            // (e.g. wealth + insurance), the accessible part is still fulfilled below — these labels
            // are handed to the synthesizer so it states the withheld part honestly instead of
            // dropping it silently. Labels come from the pruned manifests' own domain() — World-B clean.
            final List<String> withheldDomains = manifests.stream()
                    .filter(m -> allowedManifests.stream().noneMatch(a -> a.agentId().equals(m.agentId())))
                    .map(AgentManifest::domain)
                    .filter(d -> d != null && !d.isBlank())
                    .distinct()
                    .collect(Collectors.toList());

            // ── Domain + agent tags on the root span ──────────────────────────────
            // First-class Langfuse filter chips (domain:*, agent:*) plus cost-by-domain
            // metadata. All values come from manifest.domain()/agentId() — never hardcoded
            // (WORLD-B §5). These tags drive observability slicing AND eval-suite resolution
            // (see docs/EVAL-FRAMEWORK.md — the worker picks a domain/agent's suite by tag).
            if (!finalManifests.isEmpty()) {
                String resolvedDomain = String.join(",", finalDomains);
                if (!resolvedDomain.isBlank()) {
                    rootSpan.setAttribute("langfuse.metadata.domain", resolvedDomain);
                    rootSpan.setAttribute("conduit.domain", resolvedDomain);
                }
                // First-class tags: segment(s) + one per distinct domain + one per invoked
                // agent. Seeding with segmentTags() preserves the segment:* slicing chips set
                // in handleChat (setAttribute REPLACES, so they must be re-included here).
                List<String> tags = new ArrayList<>(segmentTags(jwtPrincipal));
                finalDomains.forEach(d -> tags.add("domain:" + d));
                finalManifests.stream()
                        .map(AgentManifest::agentId)
                        .filter(a -> a != null && !a.isBlank())
                        .distinct()
                        .forEach(a -> tags.add("agent:" + a));
                if (!tags.isEmpty()) {
                    rootSpan.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), tags);
                }
            }

            // ── COVERAGE CHECK ──────────────────────────────────────────────────────
            // Find the first agent whose sub-domain is resource_scoped=true.
            // If found, run DISCOVER/RESOLVE/CHECK before synthesis so we never
            // waste agent calls on a relationship the RM doesn't cover.
            AgentManifest resourceScopedAgent = finalManifests.stream()
                // Enterprise/knowledge agents carry no per-user entity — skip the coverage gate.
                .filter(entitlementService::requiresCoverage)
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

                // RE-SCOPE the coverage/secondary entity to the ROUTED sub-domain. Up to here
                // these held the cross-domain union's first key (wrong once >1 domain is loaded);
                // now that `em` names the matched sub-domain we derive them from ITS
                // required_context/entity_types. Everything downstream (resolve/check/bind,
                // entitlement) uses these re-scoped values. Manifest-declared, domain-agnostic.
                coverageEntity = coverageEntityType(em);
                DomainManifest.Coverage coverage = em.coverage();
                String tenantId = jwtPrincipal != null ? jwtPrincipal.tenantId() : "default";
                String principalId = principal.id();

                if (coverage != null) {
                    try {
                        // The classifier extracts from the client-sent conversation window:
                        // latest user mention wins, otherwise the most recent prior user mention.
                        // There is no server-side carry-forward path here.
                        boolean hasConversationReference = coverageEntity != null && preExtracted != null
                                && preExtracted.reference(coverageEntity.extractAs()) != null;

                        if (hasConversationReference) {
                            // Resolve the client reference from the client-sent messages to a
                            // canonical relationship ID. Pivots re-resolve and re-authorize.
                            // RESOLVE is principal-agnostic (World-B invariant 5): scope by tenant,
                            // not by the caller's book. The book gate is the CHECK call below plus
                            // the candidates ∩ discover intersection in the ambiguous branch.
                            CoverageResolveResult resolveResult = coverageClient.resolve(
                                preExtracted.reference(coverageEntity.extractAs()),
                                coverageEntity.resolveType(), tenantId, coverage, callerToken);

                            if (resolveResult.resolved()) {
                                coverageRelId = resolveResult.id();
                                CoverageCheckResult check = coverageClient.check(
                                    principalId, tenantId, coverageRelId, coverage, callerToken);
                                if (!check.allowed()) {
                                    emitRequestOutcome("DENIED");
                                    // Structured coverage gate frame for the glass box: which entity,
                                    // whose book. Reason is data (entity id + principal id), World-B clean.
                                    tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                                        GateData.deny(GateData.GATE_COVERAGE,
                                            coverageRelId + " not in " + principalId + "'s book",
                                            resourceScopedAgent.agentId())));
                                    // Glass-box: explicit CHECK deny event (bug 239) so the trace shows the
                                    // coverage decision rather than jumping straight to request_complete.
                                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                                        new CheckDeniedData("coverage", coverageRelId, principalId,
                                            check.reason(), "coverage")));
                                    annotateCoverageDecision(span, rootSpan, "coverage", false, check.reason());
                                    // Denial copy scoped to the routed sub-domain so an insurance denial
                                    // emits "policy" copy, not the wealth "client relationship" default (bug 238).
                                    streamTextAndComplete(emitter,
                                        mapDenialReason(check.reason(), em.subDomainId()), streamId);
                                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                    return;
                                }
                                // Coverage cleared — emit the passing gate frame so the trace shows a
                                // green coverage row, not a silent skip to synthesis.
                                tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                                    GateData.allow(GateData.GATE_COVERAGE,
                                        coverageRelId + " in " + principalId + "'s book",
                                        resourceScopedAgent.agentId())));
                                annotateCoverageDecision(span, rootSpan, "coverage", true, check.reason());

                            } else if (resolveResult.isAmbiguous()) {
                                // Multiple matches — discover what's in RM's coverage and intersect.
                                List<CoverageResource> discovered = coverageClient.discover(
                                    principalId, tenantId, coverage, callerToken);
                                List<CoverageResolveResult.ResolveCandidate> filtered =
                                    resolveResult.candidates().stream()
                                        .filter(c -> discovered.stream()
                                            .anyMatch(d -> d.id().equals(c.id())))
                                        .collect(Collectors.toList());
                                String question = buildClarificationQuestion(em, filtered, discovered, request);
                                emitRequestOutcome("CLARIFIED");
                                streamTextAndComplete(emitter, question, streamId);
                                tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                return;

                            } else {
                                // Not found in coverage at all.
                                emitRequestOutcome("CLARIFIED");
                                streamTextAndComplete(emitter, msg("reference_not_found",
                                    "I could not find a match for that reference. Please provide a specific identifier.",
                                    em.subDomainId())
                                    .replace("{reference}", preExtracted.reference(coverageEntity.extractAs())), streamId);
                                tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                return;
                            }

                        } else {
                            // No LLM-extracted reference. Before falling back to a book clarification,
                            // try to resolve any proper-noun the user typed in the LATEST message — the
                            // extractor can drop a freshly-named entity under heavy conversational load,
                            // so an out-of-coverage NAMED entity ("the Okafor account") would otherwise
                            // clarify with the caller's OWN clients rather than deny. Resolution is
                            // principal-agnostic (World-B rule f); a name that resolves to ONE entity is
                            // fully specified, so we run the coverage CHECK on it (honest deny if out of
                            // book). Only a UNIQUE resolution takes this path — a genuinely ambiguous or
                            // unresolvable name still falls through to the clarification below.
                            String backstopId = resolveNamedReferenceBackstop(
                                latestPrompt, coverageEntity, tenantId, coverage, callerToken);
                            if (backstopId != null) {
                                coverageRelId = backstopId;
                                CoverageCheckResult check = coverageClient.check(
                                    principalId, tenantId, coverageRelId, coverage, callerToken);
                                if (!check.allowed()) {
                                    emitRequestOutcome("DENIED");
                                    tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                                        GateData.deny(GateData.GATE_COVERAGE,
                                            coverageRelId + " not in " + principalId + "'s book",
                                            resourceScopedAgent.agentId())));
                                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                                        new CheckDeniedData("coverage", coverageRelId, principalId,
                                            check.reason(), "coverage")));
                                    annotateCoverageDecision(span, rootSpan, "coverage", false, check.reason());
                                    streamTextAndComplete(emitter,
                                        mapDenialReason(check.reason(), em.subDomainId()), streamId);
                                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                    return;
                                }
                                // Covered — emit the passing gate frame and fall through to synthesis
                                // (coverageRelId is injected into the entity bag below).
                                tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                                    GateData.allow(GateData.GATE_COVERAGE,
                                        coverageRelId + " in " + principalId + "'s book",
                                        resourceScopedAgent.agentId())));
                                annotateCoverageDecision(span, rootSpan, "coverage", true, check.reason());
                            } else {
                            // No user-authored reference in the sent conversation. Deterministic
                            // CLARIFY: do not guess or auto-select a covered resource.
                            List<CoverageResource> discovered = coverageClient.discover(
                                principalId, tenantId, coverage, callerToken);

                            if (discovered.isEmpty()) {
                                emitRequestOutcome("DENIED");
                                // Structured coverage gate frame — the principal's book is empty.
                                tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                                    GateData.deny(GateData.GATE_COVERAGE,
                                        principalId + "'s book is empty", resourceScopedAgent.agentId())));
                                // Empty coverage book is a domain/coverage deny — surface it in the trace (bug 239).
                                tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                                    new CheckDeniedData("coverage", null, principalId, "empty-coverage", "coverage")));
                                streamTextAndComplete(emitter, msg("no_coverage",
                                    "Your coverage set is empty.", em.subDomainId()), streamId);
                                tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                                return;
                            }

                            String question = buildClarificationQuestion(em, List.of(), discovered, request);
                            emitRequestOutcome("CLARIFIED");
                            streamTextAndComplete(emitter, question, streamId);
                            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                            return;
                            }
                        }

                    } catch (CoverageClient.CoverageUnavailableException e) {
                        log.error("Coverage service unavailable for principal={}: {}", principal.id(), e.getMessage());
                        emitRequestOutcome("FAILED");
                        streamTextAndComplete(emitter,
                            "I am unable to process your request right now. Please try again shortly.", streamId);
                        tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                        return;
                    }
                }
            }
            // ── END COVERAGE CHECK ──────────────────────────────────────────────────

            // Use pre-extracted entities from the combined intent+entity LLM call if available.
            // Priority: coverage-resolved ID > raw reference (needs EntityResolver).
            // When coverageRelId is set, the coverage pipeline already resolved the name to a canonical ID
            // (e.g. "Whitman Family Office" → "REL-00042"). Injecting the ID here makes EntityResolver
            // short-circuit via its REL-\d+ pattern match — no call to the external resolve endpoint needed.
            // Hoisted so it is visible at the plan-build site below (the DAG path binds leaf inputs
            // and derives available entity keys from it). Null when there was no pre-extraction.
            EntityBag effectiveBag = preExtracted;
            SynthesisResult synthesis;
            if (preExtracted != null) {
                if (coverageEntity != null) {
                    String covExtractAs = coverageEntity.extractAs();
                    if (coverageRelId != null) {
                        // Inject the coverage-verified canonical ID as the entity reference so
                        // the resolver short-circuits via its id_pattern (no external lookup).
                        // Also carry the manifest entity key itself: DAG planning derives its
                        // available entity set from resolved keys, while the flat binder reads
                        // the same canonical value from synthesis. This only happens after the
                        // resource coverage gate has checked the ID for the current turn.
                        effectiveBag = preExtracted
                                .withReference(covExtractAs, coverageRelId)
                                .withResolvedValue(coverageEntity.key(), coverageRelId);
                    }
                }
                synthesis = inputSynthesizer.synthesize(effectiveBag, finalManifests);
            } else {
                synthesis = inputSynthesizer.synthesize(latestPrompt, finalManifests);
            }

            if (synthesis.needsClarification() && synthesis.inputs().isEmpty()) {
                emitRequestOutcome("CLARIFIED");
                streamTextAndComplete(emitter, synthesis.clarificationMessage(), streamId); return;
            }
            if (synthesis.inputs().isEmpty()) {
                emitRequestOutcome("FAILED");
                streamTextAndComplete(emitter, msg("specify_entity",
                        "Please specify the resource identifier."), streamId); return;
            }

            String resolvedRelId = extractResolvedId(synthesis, coverageEntity);
            if (resolvedRelId != null) {
                EntitlementResult ent = entitlementService.checkRelationship(principal, resolvedRelId);
                tracePublisher.publish(TraceEvent.of("entitlement_check", requestId, conversationId,
                        new EntitlementCheckData(resolvedRelId, userId, ent.allowed(), ent.reason(), ent.source())));
                annotateCoverageDecision(span, rootSpan, "entitlement", ent.allowed(), ent.reason());
                if (!ent.allowed()) {
                    emitRequestOutcome("DENIED");
                    // Explicit deny event for the glass-box trace (bug 239).
                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                            new CheckDeniedData("entitlement", resolvedRelId, userId, ent.reason(), ent.source())));
                    String denialMsg = "Access denied: you are not authorized to view relationship " +
                            resolvedRelId + ". This denial has been logged.";
                    try {
                        streamTextAndComplete(emitter, denialMsg, streamId);
                    } catch (Exception denialEx) {
                        log.warn("Could not stream denial message (client disconnect?): {}", denialEx.getMessage());
                        try { emitter.complete(); } catch (Exception ce) { emitter.completeWithError(ce); }
                    }
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
            }

            long fanoutStart = System.currentTimeMillis();
            // Attempt the multi-step DAG (feature-flagged). Any miss returns empty and the flat
            // fan-out below runs VERBATIM — flag OFF is a byte-for-byte no-op for this path.
            List<NodeResult> results = tryDag(synthesis, finalManifests, effectiveBag, principal,
                    requestId, conversationId, callerToken).orElseGet(() -> {
                List<PlanNode> nodes = synthesis.inputs().entrySet().stream()
                        .map(e -> {
                            AgentManifest m = finalManifests.stream()
                                    .filter(a -> a.agentId().equals(e.getKey())).findFirst().orElseThrow();
                            return new PlanNode(e.getKey(), m, e.getValue(), List.of());
                        }).collect(Collectors.toList());

                nodes.forEach(n -> tracePublisher.publish(TraceEvent.of("agent_start", requestId, conversationId,
                        new AgentStartData(n.nodeId(), n.agent().protocol()))));

                return executor.execute(new Plan(nodes), callerToken);
            });
            span.setAttribute("conduit.plan.node_count", results.size());
            rootSpan.setAttribute("conduit.plan.node_count", results.size());
            long fanoutElapsedMs = System.currentTimeMillis() - fanoutStart;
            Timer.builder("conduit.fanout.duration")
                    .description("Time from routing decision to all agents completing")
                    .tag("agent_count", String.valueOf(results.size()))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(fanoutElapsedMs, TimeUnit.MILLISECONDS);

            results.forEach(r -> {
                String prev = r.isOk() && r.data() != null
                        ? r.data().toString().substring(0, Math.min(80, r.data().toString().length())) : r.status().name();
                String status = r.isOk() ? "ok" : (r.isCleanSkip() ? "skipped" : "failed");
                tracePublisher.publish(TraceEvent.of("agent_complete", requestId, conversationId,
                        new AgentCompleteData(r.agentId(), r.latencyMs(), status, prev)));
            });

            long okCount = results.stream().filter(NodeResult::isOk).count();
            long failureCount = results.stream().filter(NodeResult::isFailure).count();
            log.info("Fan-out complete {}/{} ok", okCount, results.size());

            tracePublisher.publish(TraceEvent.of("synthesis_start", requestId, conversationId,
                    new SynthesisStartData(results.size(), (int) okCount)));
            publishGroundedFigures(requestId, conversationId, results);

            // Graceful-degradation signal: this request is about to SYNTHESIZE an answer, but not
            // every dispatched agent succeeded (0 < successCount < dispatched). A failed sibling
            // never cancels the fan-out (hard-rule d) — we harvest survivors and answer from what
            // came back. Count that partial-answer case so the Platform board can show a
            // degradation rate. Domain-agnostic: a pure count, no agent/domain identity.
            if (okCount > 0 && failureCount > 0) {
                emitRequestPartial();
            }

            String answer = answerSynthesizer.synthesize(results, latestPrompt, request.messages(), emitter, streamId, withheldDomains, requestStart);
            setTraceOutput(rootSpan, answer);
            emitRequestOutcome("ANSWERED");
            finalDomains.forEach(this::emitAssistantDomain);

            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart,
                            results.size(), (int) okCount)));
        } finally {
            fetchScope.close();
            span.end();
        }
    }

    private void publishGroundedFigures(String requestId, String conversationId, List<NodeResult> results) {
        List<GroundedFigure> figures = figureRenderer.render(results, registry::find);
        Span.current().setAttribute("conduit.grounding.figure_count", figures.size());
        Span.current().setAttribute("conduit.grounding.verdict", figures.isEmpty() ? "no-figures" : "figures-rendered");
        if (figures.isEmpty()) return;
        tracePublisher.publish(TraceEvent.of("grounded_figures", requestId, conversationId,
                new GroundedFiguresData(figures.stream()
                        .map(f -> new GroundedFiguresData.Figure(
                                f.label(),
                                f.renderedValue(),
                                f.rawValue() == null ? null : f.rawValue().toString(),
                                f.format(),
                                f.sourceAgent()))
                        .toList())));
    }

    private void annotateRoutingDecision(Span stageSpan, Span rootSpan, ResolverResult resolved) {
        if (resolved == null) return;
        String goalAgent = resolved.selected() != null && !resolved.selected().isEmpty()
                ? resolved.selected().get(0).manifest().agentId()
                : "";
        for (Span target : List.of(stageSpan, rootSpan)) {
            target.setAttribute("conduit.routing.top_score", resolved.topScore());
            target.setAttribute("conduit.routing.margin", resolved.margin());
            target.setAttribute("conduit.routing.goal_agent", goalAgent);
            target.setAttribute("conduit.routing.rerank_fired", resolved.rerankFired());
        }
    }

    private void annotateCoverageDecision(Span stageSpan, Span rootSpan,
                                          String stage, boolean allowed, String reason) {
        String decision = allowed ? "allow" : "deny";
        String safeReason = reason == null ? "" : reason;
        for (Span target : List.of(stageSpan, rootSpan)) {
            target.setAttribute("conduit.coverage.stage", stage);
            target.setAttribute("conduit.coverage.decision", decision);
            target.setAttribute("conduit.coverage.reason", safeReason);
        }
    }

    /**
     * Feature-flagged multi-step orchestration. When a selected+entitled capability is a fan-in
     * "goal" (its {@code io} consumes another capability's output via a {@code from} reference), the
     * {@link DagResolver} derives the producer→consumer DAG from the live registry and the
     * {@link DagPlanExecutor} runs it by topological layers. Returns {@link Optional#empty()} on ANY
     * miss — the caller then runs the unchanged flat fan-out, so the flag OFF (or any non-DAG
     * request) is a strict no-op.
     *
     * <p><b>World B:</b> every symbol reasoned over (goal id, entity keys, produced/consumed types)
     * comes from the manifests at runtime; this method embeds no domain vocabulary.
     *
     * @param synthesis      the flat synthesis result (its {@code inputs()} keys are the routed+allowed goal candidates)
     * @param finalManifests the routed, structurally-entitled manifests (post {@code filterAgents})
     * @param effectiveBag   the resolved entity bag (may be null when there was no pre-extraction)
     * @param principal      the verified principal — used to RE-GATE resolver-pulled producers
     * @param callerToken    the caller's raw verified bearer token (F-IDENTITY) — passed straight
     *                       through to {@link DagPlanExecutor#execute} for every agent invocation
     */
    private Optional<List<NodeResult>> tryDag(SynthesisResult synthesis,
                                              List<AgentManifest> finalManifests,
                                              EntityBag effectiveBag,
                                              Principal principal,
                                              String requestId,
                                              String conversationId,
                                              String callerToken) {
        if (!dagEnabled || effectiveBag == null) return Optional.empty();

        // 1. Goal = the highest-ranked selected+allowed capability whose io declares a `from`
        //    (produced-ref) consume — a fan-in analytics capability that depends on another
        //    capability's output. The resolver preserves ranking in finalManifests; choosing the
        //    first fan-in goal lets broad-access principals still get a DAG when adjacent fan-in
        //    analytics also pass the confidence floor.
        List<AgentManifest> goals = synthesis.inputs().keySet().stream()
                .map(id -> finalManifests.stream().filter(m -> m.agentId().equals(id)).findFirst().orElse(null))
                .filter(m -> m != null && hasProducedRefConsume(m))
                .toList();
        if (goals.isEmpty()) return Optional.empty();
        AgentManifest goal = goals.get(0);
        String goalId = goal.agentId();

        // 2. Available entity keys = what the manifest-driven resolver resolves from the bag (e.g.
        //    relationship_id from the coverage-injected canonical id). Keyed by entity `key`, which
        //    is exactly what io.consumes[].entity matches. The id-shaped reference short-circuits in
        //    the resolver without any network call, so this is deterministic.
        Set<String> availableEntities = entityResolver.resolve(effectiveBag).resolved().keySet();

        // 3. Resolve the dependency DAG from the live registry snapshot.
        DagResolution resolution = dagResolver.resolve(goalId, registry.listAll(), availableEntities);
        if (!resolution.ok() || resolution.plan() == null) {
            log.info("DAG resolve miss goal={} available={} errors={} — flat fallback",
                    goalId, availableEntities, resolution.errors());
            emitDagFallback(resolutionFallbackReason(resolution), goal.domain());
            return Optional.empty();
        }
        Plan plan = resolution.plan();
        if (plan.nodes().size() <= 1) {
            // Single-node plan ⇒ the flat path produces the identical result more simply.
            emitDagFallback("single-node", goal.domain());
            return Optional.empty();
        }

        // 4. AUTHZ RE-GATE (security-critical): the resolver pulled in producer capabilities that
        //    were NOT in the originally gated set. Re-run the structural entitlement filter over
        //    EVERY manifest the plan touches; if it prunes even one, refuse the DAG and fall back to
        //    flat — never invoke a resolver-pulled producer the principal isn't entitled to.
        List<AgentManifest> planManifests = plan.nodes().stream().map(PlanNode::agent).collect(Collectors.toList());
        List<AgentManifest> allowedPlan = entitlementService.filterAgents(principal, planManifests);
        if (allowedPlan.size() < planManifests.size()) {
            log.warn("DAG authz re-gate pruned {}→{} plan manifests for principal={} — flat fallback (no producer leak)",
                    planManifests.size(), allowedPlan.size(), principal.id());
            emitDagFallback("regate-prune", goal.domain());
            return Optional.empty();
        }

        // 5. Bind leaf inputs (entity-satisfied nodes with no upstream dependency) from the bag. A
        //    missing required leaf input ⇒ refuse the DAG (never fabricate an identifier).
        List<AgentManifest> leafManifests = plan.nodes().stream()
                .filter(n -> n.dependsOn().isEmpty()).map(PlanNode::agent).collect(Collectors.toList());
        Map<String, JsonNode> preBoundInputs = inputSynthesizer.synthesize(effectiveBag, leafManifests).inputs();
        for (AgentManifest leaf : leafManifests) {
            if (!preBoundInputs.containsKey(leaf.agentId())) {
                log.info("DAG leaf '{}' has no bound input (required field unresolved) — flat fallback", leaf.agentId());
                emitDagFallback("unmet-input", goal.domain());
                return Optional.empty();
            }
        }

        // 6. Publish the plan graph (glass-box) + one agent_start per node, then execute by layers.
        List<PlanGraphData.Node> graphNodes = plan.nodes().stream()
                .map(n -> new PlanGraphData.Node(n.nodeId(), n.agent().agentId(),
                        n.agent().protocol(), n.dependsOn(), "planned"))
                .collect(Collectors.toList());
        tracePublisher.publish(TraceEvent.of("plan_graph", requestId, conversationId,
                new PlanGraphData(graphNodes)));
        plan.nodes().forEach(n -> tracePublisher.publish(TraceEvent.of("agent_start", requestId, conversationId,
                new AgentStartData(n.nodeId(), n.agent().protocol()))));

        Blackboard blackboard = new Blackboard(availableEntities, preBoundInputs, mapper);
        log.info("DAG firing: goal={} nodes={} available={}", goalId,
                plan.nodes().stream().map(PlanNode::nodeId).collect(Collectors.toList()), availableEntities);
        emitDagPlan(goal.domain(), goalId, plan.nodes().size());
        DagPlanExecutor.CoverageContext coverageContext = new DagPlanExecutor.CoverageContext(
                principal.id(),
                principal.tenantId() == null || principal.tenantId().isBlank() ? "default" : principal.tenantId(),
                callerToken,
                m -> manifestStore.getEffective(m.agentId(), m.domain(), m.subDomain()),
                coverageClient::check);
        List<NodeResult> results = dagExecutor.execute(plan, blackboard, requestId, conversationId,
                callerToken, coverageContext);
        tracePublisher.publish(TraceEvent.of("plan_graph", requestId, conversationId,
                new PlanGraphData(plan.nodes().stream()
                        .map(n -> new PlanGraphData.Node(n.nodeId(), n.agent().agentId(),
                                n.agent().protocol(), n.dependsOn(), statusForPlan(n, results)))
                        .collect(Collectors.toList()))));
        return Optional.of(results);
    }

    private String statusForPlan(PlanNode node, List<NodeResult> results) {
        return results.stream()
                .filter(r -> r.nodeId().equals(node.nodeId()))
                .findFirst()
                .map(r -> switch (r.status()) {
                    case OK -> mapStatus(r);
                    case SKIPPED_CONDITION_FALSE -> "skipped_condition_false";
                    case CONDITION_ERROR -> "condition_error";
                    default -> r.status().name().toLowerCase();
                })
                .orElse("unknown");
    }

    private String mapStatus(NodeResult result) {
        JsonNode data = result.data();
        if (data != null && data.path("_map").asBoolean(false)) {
            String status = "map: " + data.path("_ok").asInt(0) + "/" + data.path("_ran").asInt(0) + " ok";
            if (data.path("_truncated").asBoolean(false)) status += " capped";
            return status;
        }
        return "ok";
    }

    private String resolutionFallbackReason(DagResolution resolution) {
        if (resolution == null || resolution.errors().isEmpty()) return "resolve-miss";
        boolean ambiguous = resolution.errors().stream()
                .anyMatch(e -> e.code() == ResolutionError.Code.AMBIGUOUS_PRODUCER);
        if (ambiguous) return "ambiguous-goal";
        boolean unmetInput = resolution.errors().stream()
                .anyMatch(e -> e.code() == ResolutionError.Code.UNMET_REQUIRED_INPUT
                        || e.code() == ResolutionError.Code.MISSING_PRODUCER
                        || e.code() == ResolutionError.Code.UNKNOWN_NODE);
        if (unmetInput) return "unmet-input";
        boolean cycle = resolution.errors().stream()
                .anyMatch(e -> e.code() == ResolutionError.Code.CYCLE);
        return cycle ? "cycle" : resolution.errors().get(0).code().name().toLowerCase();
    }

    /** True if any of the manifest's {@code io.consumes} entries is a produced-output ({@code from}) reference. */
    private static boolean hasProducedRefConsume(AgentManifest m) {
        AgentManifest.Io io = m.io();
        if (io == null || io.consumes() == null) return false;
        return io.consumes().stream().anyMatch(c -> c != null && c.isProducedRef());
    }

    private void handleFollowUp(ChatRequest request, SseEmitter emitter,
                                 String latestPrompt, String conversationId,
                                 String userId,
                                 Principal jwtPrincipal,
                                 String requestId, long requestStart, Span rootSpan,
                                 EntityBag preExtracted, String streamId, String callerToken) throws Exception {
        // ── BIAS-TO-FETCH FALLTHROUGH ────────────────────────────────────────────
        // A follow-up whose needed data is not already in context ("what's the performance on that
        // portfolio?" when only holdings were shown) must fetch it, not answer "no info" from
        // memory. When the turn carries a single grounded resolvable entity (a name/id the focal
        // derivation resolved, or an id typed in the message) AND the conversation routes
        // confidently to agents, serve it through the fetch pipeline. If either is missing it is a
        // genuine context-answerable follow-up (explain / recap / aggregate) → synthesize from
        // history. The probe uses entityKnown=true so a terse fresh-data follow-up is not stranded
        // by the margin abstain; the coverage CHECK remains the access gate.
        if (preExtracted != null && hasGroundedResolvableReference(preExtracted, latestPrompt)) {
            ResolverResult probe = resolver.resolveContextual(buildRoutingQuery(request), true);
            if (!probe.fallback() && !probe.selected().isEmpty()) {
                log.info("FOLLOW_UP → fetch fallthrough (grounded entity + confident route) requestId={}", requestId);
                handleFetchData(request, emitter, latestPrompt, conversationId, userId, jwtPrincipal,
                        requestId, requestStart, rootSpan, preExtracted, streamId, true, callerToken);
                return;
            }
        }
        Span span = tracer.spanBuilder("chat.follow_up").startSpan();
        try {
            String answer = answerSynthesizer.synthesizeFromHistory(request.messages(), latestPrompt, emitter, streamId, requestStart);
            setTraceOutput(rootSpan, answer);
            emitRequestOutcome("ANSWERED");
            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
        } finally { span.end(); }
    }

    private void handleChitchat(ChatRequest request, SseEmitter emitter,
                                 String conversationId, String requestId, long requestStart, String streamId,
                                 Span rootSpan) throws Exception {
        String answer = answerSynthesizer.synthesizeFromHistory(request.messages(), extractLatestUserMessage(request), emitter, streamId, requestStart);
        setTraceOutput(rootSpan, answer);
        emitRequestOutcome("ANSWERED");
        tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
    }

    /**
     * Mirror the final synthesized answer onto the ROOT {@code chat.handle} span as
     * {@code langfuse.trace.output}. Langfuse's OTLP ingestion reads the trace-level output from
     * this attribute on the root span; without it the continuous eval judges score an empty answer
     * and the Langfuse UI shows a blank response. Domain-agnostic — the value is the opaque answer
     * text (World B). Blank output (LLM error path) is skipped so it never overwrites a real answer.
     */
    private void setTraceOutput(Span rootSpan, String answer) {
        if (rootSpan != null && answer != null && !answer.isBlank()) {
            rootSpan.setAttribute("langfuse.trace.output", answer);
        }
    }

    // ── Coverage helper methods ────────────────────────────────────────────────

    /**
     * Maps a machine-readable denial reason code from the coverage service to a
     * human-readable message suitable for streaming to the end user.
     */
    private String mapDenialReason(String reason, String subDomainId) {
        // Reason codes (not-covered, coverage-transferred, relationship-closed) are the stable
        // contract; their user-facing TEXT is declared in the sub-domain manifest's
        // denial_messages map. The gateway holds no domain copy (WORLD-B §5). The lookup is scoped
        // to the routed sub-domain so multi-domain registries emit the right domain's copy (bug 238).
        String text = manifestStore.denialMessage(reason, subDomainId);
        return text != null ? text : "Access to the requested resource was denied.";
    }

    /** Returns a manifest-declared user-facing message, falling back to a domain-neutral default. */
    private String msg(String key, String fallback) {
        String m = manifestStore.message(key);
        return (m != null && !m.isBlank()) ? m : fallback;
    }

    /** Sub-domain-scoped variant of {@link #msg(String, String)} — prefers the routed domain's copy. */
    private String msg(String key, String fallback, String subDomainId) {
        String m = manifestStore.message(key, subDomainId);
        return (m != null && !m.isBlank()) ? m : fallback;
    }

    /**
     * Builds the clarification question over a grounded candidate set. The clarify DECISION is
     * already made deterministically upstream ({@code extracted ∩ required = ∅}); this only produces
     * the WORDING. Two manifest-declared styles:
     *
     * <ul>
     *   <li><b>template</b> (default, safe): the deterministic candidate list (by name + identifier,
     *       no positional numbers), inviting a reply by name or identifier.</li>
     *   <li><b>composed</b> (opt-in via {@code clarify_style}): the {@link ClarificationComposer}
     *       phrases a natural question over the SAME grounded candidates. Its output is validated to
     *       introduce no identifier outside the candidate set; on any rejection or composer failure
     *       we fall back to the exact deterministic template. Worst case equals today's behaviour.</li>
     * </ul>
     *
     * @param em         the effective manifest (question template, clarify style/tone, id_pattern)
     * @param candidates disambiguation candidates already narrowed to the RM's book (may be empty
     *                   — falls back to the full discovered list)
     * @param discovered full discovered list for this RM
     * @param request    the chat request, for recent conversation context (phrasing only)
     */
    private String buildClarificationQuestion(EffectiveManifest em,
                                               List<CoverageResolveResult.ResolveCandidate> candidates,
                                               List<CoverageResource> discovered,
                                               ChatRequest request) {
        ai.conduit.gateway.domain.manifest.ClarificationSchema cs =
                em.clarificationFor(em.primaryRequiredKey());
        String questionText = (cs != null && cs.question() != null && !cs.question().isBlank())
            ? cs.question()
            : msg("missing_entity_question", "Which resource are you asking about?");

        // When disambiguation candidates exist (already intersected with the RM's book),
        // narrow the options to just those — do NOT dump the full book. Fall back to the
        // full discovered list only when there are no candidates to narrow with.
        List<CoverageResource> options;
        if (candidates != null && !candidates.isEmpty()) {
            Set<String> candidateIds = candidates.stream()
                .map(CoverageResolveResult.ResolveCandidate::id)
                .collect(Collectors.toSet());
            options = discovered.stream()
                .filter(r -> candidateIds.contains(r.id()))
                .collect(Collectors.toList());
        } else {
            options = discovered;
        }

        // The entity noun + id_pattern come from the routed sub-domain's manifest (World-B: no domain
        // literal here). The noun frames both the deterministic invitation and the composed prompt.
        EntityType primary = primaryResolvableEntity(em);
        String entityNoun = primary != null ? primary.display() : null;
        String idPattern  = primary != null ? primary.idPattern() : null;

        // The deterministic template is BOTH the default style AND the fallback for the composed
        // style — computed first so it is always available unchanged.
        String template = buildDeterministicClarification(questionText, options, entityNoun);

        if (!em.clarifyComposed()) {
            return template; // default, auditable, deterministic
        }

        // Composed style: phrase a natural question over the SAME grounded candidates. Candidates are
        // handed to the composer as DELIMITED DATA; validation rejects any foreign identifier → fall
        // back to the template.
        List<ClarificationComposer.Candidate> composerCandidates = options.stream()
                .map(r -> new ClarificationComposer.Candidate(r.id(), r.label()))
                .collect(Collectors.toList());

        String composed = clarificationComposer.compose(
                questionText, entityNoun, composerCandidates, idPattern,
                recentUserContext(request), em.clarifyTone());
        return composed != null ? composed : template;
    }

    /**
     * The deterministic clarification string — the default style and the composed fallback. Candidates
     * are listed by NAME (+ identifier), never numbered, and the invitation asks for the name or
     * identifier — matching what the resolve path can actually honour (there is no positional-number
     * selection). The entity noun ({@code entityNoun}) is the manifest-declared display for the missing
     * slot; when absent the invitation stays generic. No domain copy is hardcoded here (World-B).
     */
    private String buildDeterministicClarification(String questionText, List<CoverageResource> options,
                                                   String entityNoun) {
        StringBuilder sb = new StringBuilder(questionText).append("\n");
        for (CoverageResource r : options) {
            sb.append("- ").append(r.label())
              .append(" (").append(r.id()).append(")").append("\n");
        }
        String noun = (entityNoun != null && !entityNoun.isBlank()) ? entityNoun.strip() + " " : "";
        sb.append("\nReply with the ").append(noun).append("name or identifier.");
        return sb.toString();
    }

    /** The routed sub-domain's primary required RESOLVABLE entity type, or null (manifest-driven). */
    private EntityType primaryResolvableEntity(EffectiveManifest em) {
        String key = em.primaryRequiredKey();
        if (key == null) return null;
        return manifestStore.entityTypesFor(em.subDomainId()).stream()
                .filter(et -> key.equals(et.key()) && et.isResolvable())
                .findFirst()
                .orElse(null);
    }

    /** Recent USER turns (newest last) as phrasing context for the composer — never a fact source. */
    private String recentUserContext(ChatRequest request) {
        if (request == null || request.messages() == null) return null;
        int window = Math.max(1, routingContextTurns);
        List<String> turns = new ArrayList<>();
        for (int i = request.messages().size() - 1; i >= 0 && turns.size() < window; i--) {
            Message m = request.messages().get(i);
            if ("user".equalsIgnoreCase(m.role()) && m.content() != null && !m.content().isBlank()) {
                turns.add(m.content().trim());
            }
        }
        if (turns.isEmpty()) return null;
        java.util.Collections.reverse(turns);
        return String.join("\n", turns);
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

    /**
     * Builds the routing query by concatenating the most recent user turns (bounded by
     * {@code routingContextTurns}, current turn last) so a keyword-less follow-up inherits the
     * conversation's established domain vocabulary. This is the "carried topic" the resolver uses to
     * derive the query's domain (plan §2). Only USER turns are included — assistant answers would
     * inject their own vocabulary and skew the embedding. No domain knowledge here: it is raw
     * conversation text. Falls back to the latest user message when there is only one turn.
     */
    private String buildRoutingQuery(ChatRequest request) {
        return buildRoutingQuery(request, Math.max(1, routingContextTurns));
    }

    private String buildRoutingQuery(ChatRequest request, int window) {
        String latest = extractLatestUserMessage(request);
        if (request.messages() == null || request.messages().isEmpty()) return latest;
        List<String> userTurns = new ArrayList<>();
        for (int i = request.messages().size() - 1; i >= 0 && userTurns.size() < window; i--) {
            Message m = request.messages().get(i);
            if ("user".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                userTurns.add(m.content().trim());
            }
        }
        if (userTurns.size() <= 1) return latest;
        java.util.Collections.reverse(userTurns);  // oldest → newest (current turn last)
        return String.join("\n", userTurns);
    }

    private String routingTextForFetch(ChatRequest request, String latestPrompt,
                                       boolean carryContext, boolean entityKnown) {
        if (!carryContext) return latestPrompt;
        if (entityKnown && !latestContainsResolvableId(latestPrompt)) {
            return latestPrompt;
        }
        if (entityKnown && latestContainsResolvableId(latestPrompt)) {
            return buildRoutingQuery(request, 2);
        }
        return buildRoutingQuery(request);
    }

    private boolean latestContainsResolvableId(String latestPrompt) {
        if (latestPrompt == null || latestPrompt.isBlank()) return false;
        return manifestStore.entityTypes().stream()
                .filter(EntityType::isResolvable)
                .map(EntityType::idPattern)
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .anyMatch(pattern -> java.util.regex.Pattern.compile(pattern).matcher(latestPrompt).find());
    }

    /**
     * True when the turn carries an explicit, grounded resolvable entity reference — either a
     * reference the deterministic focal derivation already placed in {@code preExtracted}, or an
     * identifier the user literally typed in the latest message (matched against each resolvable
     * entity type's manifest {@code id_pattern}). Drives the resolver's bias-to-fetch relaxation.
     * Manifest-driven (entity types + id_pattern) — no domain literal or ID prefix in the gateway.
     */
    /**
     * True when the client-sent window contains prior assistant content to answer from — used to
     * decide whether a routing-abstained turn can degrade gracefully to grounded history synthesis
     * rather than punting. Purely structural (role + non-blank content); no domain knowledge.
     */
    private boolean hasPriorAssistantData(ChatRequest request) {
        if (request.messages() == null) return false;
        return request.messages().stream()
                .anyMatch(m -> "assistant".equalsIgnoreCase(m.role())
                        && m.content() != null && !m.content().isBlank());
    }

    private boolean hasGroundedResolvableReference(EntityBag preExtracted, String latestPrompt) {
        List<EntityType> resolvables = manifestStore.entityTypes().stream()
                .filter(EntityType::isResolvable)
                .collect(Collectors.toList());
        for (EntityType et : resolvables) {
            if (preExtracted != null && et.extractAs() != null
                    && preExtracted.reference(et.extractAs()) != null) {
                return true;
            }
            String pattern = et.idPattern();
            if (pattern != null && !pattern.isBlank() && latestPrompt != null
                    && java.util.regex.Pattern.compile(pattern).matcher(latestPrompt).find()) {
                return true;
            }
        }
        return false;
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

    /**
     * The coverage (required, resolvable) entity for a SPECIFIC routed sub-domain — the entity a
     * coverage/entitlement check gates on. Scoped to the sub-domain's own {@code required_context}
     * and {@code entity_types}, so a multi-domain registry selects the entity that belongs to the
     * routed sub-domain (e.g. the insurance required entity for an insurance query) instead of the
     * cross-domain union's first key. Manifest-declared, never hardcoded. Falls back to the global
     * no-arg selection when the effective manifest or its sub-domain entity_types are unavailable.
     */
    private EntityType coverageEntityType(EffectiveManifest em) {
        if (em == null) return coverageEntityType();
        java.util.List<String> requiredKeys = em.requiredContext();
        java.util.List<EntityType> scoped = manifestStore.entityTypesFor(em.subDomainId());
        if (scoped.isEmpty()) return coverageEntityType();
        return scoped.stream()
                .filter(EntityType::isResolvable)
                .filter(et -> requiredKeys != null && requiredKeys.contains(et.key()))
                .findFirst().orElse(null);
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

    /**
     * Deterministic named-entity backstop for the coverage pipeline. The LLM extractor can drop a
     * freshly-named entity under heavy conversational load; this recovers it WITHOUT the model, by
     * pulling the proper-noun phrases the user typed in the latest message and resolving each against
     * the routed sub-domain's coverage service, principal-agnostically (World-B rule f). Returns the
     * canonical id when EXACTLY ONE phrase resolves UNAMBIGUOUSLY, else null — no phrase resolves, or
     * resolution is ambiguous, so the caller falls back to a proper clarification (never a guess).
     * Language-generic (capitalized-word heuristic) + manifest-driven (resolve_type + coverage) — no
     * domain names or id prefixes in the gateway.
     */
    private String resolveNamedReferenceBackstop(String latestPrompt, EntityType coverageEntity,
                                                 String tenantId, DomainManifest.Coverage coverage,
                                                 String callerToken) {
        if (coverageEntity == null || coverage == null || latestPrompt == null || latestPrompt.isBlank()) {
            return null;
        }
        String found = null;
        for (String phrase : properNounPhrases(latestPrompt)) {
            try {
                CoverageResolveResult r = coverageClient.resolve(
                    phrase, coverageEntity.resolveType(), tenantId, coverage, callerToken);
                if (r.resolved() && r.id() != null) {
                    if (found != null && !found.equals(r.id())) return null; // two distinct → ambiguous
                    found = r.id();
                } else if (r.isAmbiguous()) {
                    return null; // a single phrase is itself ambiguous → clarify, do not guess
                }
            } catch (CoverageClient.CoverageUnavailableException e) {
                return null; // fail closed → fall back to the deterministic clarification
            }
        }
        return found;
    }

    /** Capitalized proper-noun token (length ≥ 3) — a cheap entity-name heuristic (language-generic). */
    private static final java.util.regex.Pattern PROPER_NOUN =
            java.util.regex.Pattern.compile("[A-Z][A-Za-z]{2,}(?:\\s+[A-Z][A-Za-z]{2,})*");

    /**
     * Maximal runs of consecutive capitalized words in {@code text} — candidate entity names to try
     * resolving (e.g. "the Okafor account" → ["Okafor"], "Whitman Family Office" → ["Whitman Family
     * Office"]). Language-generic; carries no domain knowledge.
     */
    private static List<String> properNounPhrases(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        Matcher m = PROPER_NOUN.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Sends text tokens as SSE deltas and calls {@code emitter.complete()}. Terminates the stream. */
    private void streamTextAndComplete(SseEmitter emitter, String text) throws Exception {
        streamTextAndComplete(emitter, text, null);
    }

    /** Sends text tokens using the request's completion id when one is already allocated. */
    private void streamTextAndComplete(SseEmitter emitter, String text, String streamId) throws Exception {
        String id = (streamId != null && !streamId.isBlank()) ? streamId : newId();
        long ts = epochSeconds();
        emitter.send(SseEmitter.event().data(roleDelta(id, ts)));
        for (String word : text.split("(?<=\\s)|(?=\\s)")) {
            emitter.send(SseEmitter.event().data(contentDelta(id, ts, word)));
        }
        emitter.send(SseEmitter.event().data(stopDelta(id, ts)));
        emitter.send(SseEmitter.event().data(" [DONE]"));
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
        return " " + mapper.writeValueAsString(root);  // leading space → OpenAI-exact "data: {json}" (Spring omits it)
    }

    /** Increments the request outcome counter. Micrometer caches the Counter by key. */
    private void emitRequestOutcome(String outcome) {
        Counter.builder("conduit.request.outcome")
                .description("Request resolution outcome")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increments the graceful-degradation counter (Prometheus: conduit_request_partial_total) —
     * a request that synthesized an answer from a partial fan-out (some dispatched agents failed
     * but at least one succeeded). Complements conduit_request_outcome_total (which still counts
     * this request as ANSWERED). Domain-agnostic: a pure count with no agent/domain identity.
     */
    private void emitRequestPartial() {
        Counter.builder("conduit.request.partial")
                .description("Requests answered from a partial fan-out (a dispatched agent failed)")
                .register(meterRegistry)
                .increment();
    }

    /** Counts DAG plans that actually fired, tagged by manifest-declared dimensions. */
    private void emitDagPlan(String domain, String goalId, int nodeCount) {
        Counter.builder("conduit.dag.plan")
                .description("Multi-step DAG plans executed by the gateway")
                .tag("domain", safeMetricTag(domain))
                .tag("goal", safeMetricTag(goalId))
                .tag("node_count", String.valueOf(nodeCount))
                .register(meterRegistry)
                .increment();
    }

    /** Counts DAG candidates that fell back to the flat path, without domain-specific logic. */
    private void emitDagFallback(String reason, String domain) {
        Counter.builder("conduit.dag.fallback")
                .description("DAG candidates that fell back to flat orchestration")
                .tag("reason", safeMetricTag(reason))
                .tag("domain", safeMetricTag(domain))
                .register(meterRegistry)
                .increment();
    }

    /** Counts successful data answers by manifest-declared domain. */
    private void emitAssistantDomain(String domain) {
        Counter.builder("conduit.assistant.domain")
                .description("Successful data answers by manifest-declared domain")
                .tag("domain", safeMetricTag(domain))
                .register(meterRegistry)
                .increment();
    }

    private static String safeMetricTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /**
     * Adoption counter tagged by ROLE (not per-user — user-level cardinality would explode the
     * metric). One increment per chat request; feeds the Insights "adoption by role" panel.
     */
    private void emitAdoption(Principal principal) {
        String role = (principal != null && principal.roles() != null && !principal.roles().isEmpty())
                ? principal.roles().get(0) : "anonymous";
        Counter.builder("conduit.adoption")
                .description("Chat requests by principal role")
                .tag("role", role)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Segment slicing tags ({@code "segment:<name>"}) from the principal's per-segment
     * classification map. Values come only from the verified JWT (World B — the gateway
     * embeds no segment/domain literal). Empty for anonymous/segment-less principals.
     */
    private static List<String> segmentTags(Principal p) {
        if (p == null || p.segments() == null || p.segments().isEmpty()) return List.of();
        return p.segments().keySet().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> "segment:" + s)
                .distinct()
                .toList();
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
