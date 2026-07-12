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
import ai.conduit.gateway.domain.coverage.EntityBinding;
import ai.conduit.gateway.domain.coverage.EntityBindingSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundingResult;
import ai.conduit.gateway.domain.coverage.UnboundReference;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EffectiveManifest;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.conduit.gateway.infrastructure.metrics.GatewaySloMetrics;
import ai.conduit.gateway.infrastructure.telemetry.event.AgentCompleteData;
import ai.conduit.gateway.infrastructure.telemetry.event.AgentStartData;
import ai.conduit.gateway.infrastructure.telemetry.event.AgentsResolvedData;
import ai.conduit.gateway.infrastructure.telemetry.event.RoutePreparedData;
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
import ai.conduit.gateway.registry.model.RoutingCandidate;
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

    /**
     * The abstain-triage required-entity CLARIFY (Stage 3) only proposes a candidate whose routing
     * score cleared this floor — REUSED from {@code conduit.resolver.confidence-floor} (the resolver's
     * per-candidate long-tail prune), not a new knob. Config, not a constant (CLAUDE.md §5).
     */
    @org.springframework.beans.factory.annotation.Value("${conduit.resolver.confidence-floor:0.35}")
    private double confidenceFloor;

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
    private final ReferenceGroundingService referenceGrounding;
    private final RoutePreparer            routePreparer;
    private final DomainManifestStore      manifestStore;
    private final ai.conduit.gateway.domain.clarify.ClarificationComposer clarificationComposer;
    private final Tracer                   tracer;
    private final MeterRegistry meterRegistry;
    private final GatewaySloMetrics gatewaySloMetrics;
    private final ThreadLocal<GatewaySloMetrics.RequestScope> gatewayMetricScope = new ThreadLocal<>();
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

    /**
     * Whether to derive and pass the grounded-references' domain set into {@code resolveContextual} so the
     * Piece-5 conflict trigger is reachable (routing spec V2 Piece 4/5). Bound to the SAME property as the
     * resolver's own trigger so one flag controls both sides. OFF by default: the gateway then calls the
     * two-arg {@code resolveContextual}, so routing behaviour and every existing resolver mock are
     * byte-identical. Flipping it ON supplies the grounded domain set; the trigger still never denies — it
     * only makes a near-tie rerank abstain on error instead of trusting the embedding leader.
     */
    @org.springframework.beans.factory.annotation.Value("${conduit.routing.rerank-conflict-trigger.enabled:false}")
    private boolean conflictTriggerEnabled;

    /**
     * The preparation/masking contract version stamped onto every {@link RouteDecision} the Piece-6
     * decision endpoint returns, so the goal-pick harness can assert the endpoint exercised the current
     * production preparation (not a stale/forked one). Config, never a constant (CLAUDE.md §5).
     */
    @org.springframework.beans.factory.annotation.Value("${conduit.routing.preparation-version:route-prep-v2}")
    private String preparationVersion;

    /**
     * Multi-entity COMPARE fan-out caps (routing spec — Multi-Entity Compare §D4). Config, never Java
     * constants (CLAUDE.md §5). {@code max-entity-bindings} bounds the entities per request;
     * {@code max-total-groups} bounds the facet×entity product after expansion. Capping is deterministic
     * (mention order = the order the user named them) and NEVER silent — it is logged, traced, and stated
     * in the answer.
     */
    @org.springframework.beans.factory.annotation.Value("${conduit.groups.max-entity-bindings:3}")
    private int maxEntityBindings;

    @org.springframework.beans.factory.annotation.Value("${conduit.groups.max-total-groups:6}")
    private int maxTotalGroups;

    /**
     * Compare-CLARIFY kill-switch (Compare-CLARIFY spec §3). When ON (default), a request that binds ≥1
     * entity but names MORE than it bound asks ONE deterministic question instead of silently serving a
     * one-sided compare. OFF restores the pre-feature behaviour byte-for-byte (the detector never runs).
     * Config, never a Java constant (CLAUDE.md §5).
     */
    @org.springframework.beans.factory.annotation.Value("${conduit.grounding.residual-detection.enabled:true}")
    private boolean residualDetectionEnabled;

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
                       ReferenceGroundingService referenceGrounding,
                       RoutePreparer routePreparer,
                       DomainManifestStore manifestStore,
                       ai.conduit.gateway.domain.clarify.ClarificationComposer clarificationComposer,
                       Tracer tracer,
                       MeterRegistry meterRegistry,
                       GatewaySloMetrics gatewaySloMetrics,
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
        this.referenceGrounding  = referenceGrounding;
        this.routePreparer       = routePreparer;
        this.manifestStore       = manifestStore;
        this.clarificationComposer = clarificationComposer;
        this.tracer              = tracer;
        this.meterRegistry         = meterRegistry;
        this.gatewaySloMetrics     = gatewaySloMetrics;
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
        GatewaySloMetrics.RequestScope metricScope = gatewaySloMetrics.start();
        gatewayMetricScope.set(metricScope);
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
                        intentResult.extractedEntities(), streamId, true, callerToken, null);
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
                        intentResult.extractedEntities(), streamId, false, callerToken, null);
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
            gatewaySloMetrics.finish(metricScope);
            gatewayMetricScope.remove();
            rootSpan.end();
        }
    }

    private void handleFetchData(ChatRequest request, SseEmitter emitter,
                                  String latestPrompt, String conversationId,
                                  String userId,
                                  Principal jwtPrincipal,
                                  String requestId, long requestStart, Span rootSpan,
                                  EntityBag preExtracted, String streamId,
                                  boolean carryContext, String callerToken,
                                  PreparedRoute preparedMemo) throws Exception {
        Span span = tracer.spanBuilder("chat.fetch_data").startSpan();
        Scope fetchScope = span.makeCurrent();
        // coverageRelId is set by the coverage pipeline (or the Stage-1 grounding memo) before synthesis.
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
        // Identity comes ONLY from the verified JWT; an unauthenticated caller is anonymous with no
        // coverage, so every entity check denies. Hoisted above routing because Stage-1 grounding
        // needs it (the coverage CHECK) and the abstain triage re-uses it. (Was computed post-route.)
        Principal principal = (jwtPrincipal != null) ? jwtPrincipal : Principal.anonymous();
        String tenantId = jwtPrincipal != null ? jwtPrincipal.tenantId() : "default";
        try {
            // ── STAGE 1: SINGLE-PASS REFERENCE GROUNDING via the shared prepared route ─────────
            // A security-relevant disposition (coverage DENY) and its inputs — the extracted entity
            // reference, the manifest required entity, the coverage service — do not depend on routing
            // at all, so a grounded out-of-book reference denies at ANY embedding score BEFORE the router
            // runs. (routing spec V2 Piece 4) The pre-Piece-4 code ground the focal reference TWICE — in
            // ReferenceGroundingService.ground() for this terminal DENY/UNAVAILABLE/CLARIFY lattice, and
            // again in RoutePreparer.prepare()->groundMentions() for masking + relaxation. The group
            // model now owns disposition, so we ground ONCE (via prepare) and drive the SAME terminal
            // lattice from the memoized focal verdict. A scalar-only extraction that carries no mention
            // model (a legacy extractor path or a unit-test bag) yields an empty grounded set; only THEN
            // do we fall back to the scalar ground() lattice — so a reference that DID ground through the
            // mention model is never re-resolved (the double coverage check is removed). RESOLVE is
            // principal-agnostic; CHECK only ever sees a resolved id; fails closed.
            PreparedRoute prepared = (preparedMemo != null) ? preparedMemo
                    : routePreparer.prepare(request, latestPrompt, preExtracted, carryContext,
                            false, principal, tenantId, callerToken);
            GroundingResult focalVerdict = prepared.groundedSet().focalVerdict();
            GroundingResult grounding = focalVerdict.verdict() != ReferenceGroundingService.Verdict.NONE
                    ? focalVerdict
                    : referenceGrounding.ground(preExtracted, latestPrompt, principal, tenantId, callerToken);
            // ── MULTI-ENTITY COMPARE — per-entity bindings (Multi-Entity Compare §D1) ──────────────
            // Derived from the ALREADY-computed grounded set (no new coverage calls); only latest-turn
            // EXPLICIT mentions form bindings (anaphora never multiplies fan-out → S8/S9 unchanged). Below
            // two bindings the feature is INERT and the terminal lattice is byte-identical.
            EntityBindingSet bindingSet = EntityBindingSet.derive(prepared.groundedSet());
            boolean multiEntity = bindingSet.multiEntity();

            GroundingResult groundedMemo = null;
            if (multiEntity) {
                // Generalized terminal lattice (D2 — the MC-3 fix): disposition derives from the SET of
                // bindings, not the focal mention. A focal DENY no longer kills the whole request when a
                // sibling client is covered.
                if (bindingSet.allowedCount() == 0) {
                    if (bindingSet.anyDenied()) {
                        // ALL bindings denied (zero allowed) → terminal COVERAGE_DENIED. Emit one
                        // gate/check_denied pair per binding, stream the manifest coverage copy once (S4).
                        emitRequestOutcome("DENIED");
                        for (EntityBinding b : bindingSet.bindings()) {
                            if (!b.hasDenied() && !b.hasUnavailable()) continue;
                            String reason = b.terminalDenialReason();
                            String sub = b.terminalDeniedSubDomainId();
                            tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                                GateData.deny(GateData.GATE_COVERAGE,
                                    b.canonicalId() + " not in " + principal.id() + "'s book", sub)));
                            tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                                new CheckDeniedData("coverage", b.canonicalId(), principal.id(), reason, "coverage")));
                        }
                        EntityBinding first = bindingSet.firstWithheld();
                        streamTextAndComplete(emitter, mapDenialReason(
                            first != null ? first.terminalDenialReason() : null,
                            first != null ? first.terminalDeniedSubDomainId() : null), streamId);
                        tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                        return;
                    }
                    // ALL bindings UNAVAILABLE → fail closed (existing UNAVAILABLE terminal semantics).
                    emitRequestOutcome("DENIED");
                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                        new CheckDeniedData("coverage", null, principal.id(), "coverage-unavailable", "coverage")));
                    streamTextAndComplete(emitter,
                        "I am unable to verify your coverage right now, so I cannot provide that data.", streamId);
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
                // ≥1 allowed → proceed to routing. The focal verdict still seeds the memo ONLY when it is
                // itself RESOLVED_ALLOWED; the memo id-equality guard (coverageForGroup) then prevents it
                // from ever binding a non-focal entity group.
                if (grounding.verdict() == ReferenceGroundingService.Verdict.RESOLVED_ALLOWED) {
                    groundedMemo = grounding;
                }
            } else {
            switch (grounding.verdict()) {
                case DENIED -> {
                    emitRequestOutcome("DENIED");
                    tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                        GateData.deny(GateData.GATE_COVERAGE,
                            grounding.resolvedId() + " not in " + principal.id() + "'s book",
                            grounding.subDomainId())));
                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                        new CheckDeniedData("coverage", grounding.resolvedId(), principal.id(),
                            grounding.denialReason(), "coverage")));
                    streamTextAndComplete(emitter,
                        mapDenialReason(grounding.denialReason(), grounding.subDomainId()), streamId);
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
                case UNAVAILABLE -> {
                    // FAIL CLOSED — never grant access when coverage can't be verified.
                    emitRequestOutcome("DENIED");
                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                        new CheckDeniedData("coverage", grounding.resolvedId(), principal.id(),
                            "coverage-unavailable", "coverage")));
                    streamTextAndComplete(emitter,
                        "I am unable to verify your coverage right now, so I cannot provide that data.", streamId);
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
                case RESOLVED_ALLOWED ->
                    // Memoize the resolved+covered id so the coverage pipeline consumes it instead of
                    // re-resolving/re-checking, and route with entityKnown=true (subject is known).
                    groundedMemo = grounding;
                default -> { /* AMBIGUOUS / NOT_FOUND / NONE — normal pipeline runs unchanged. */ }
            }
            }

            // ── CONTEXT-AWARE ROUTING (Piece 3 prepared route + Piece 5 conflict-trigger wiring) ──
            // The routing text + bias-to-fetch relaxation come from the shared preparation pipeline
            // (RoutePreparer, computed above): resolved entity spans are MASKED so routing keys on the
            // capability asked rather than the entity named, an empty masked residual widens to the full
            // masked window, and relaxation is granted ONLY on a RESOLVED_ALLOWED-and-masked focal (or a
            // deterministic non-coverage-scoped id). A FOLLOW_UP passes its already-prepared memo through.
            tracePublisher.publish(TraceEvent.of("route_prepared", requestId, conversationId,
                    RoutePreparedData.from(prepared.maskDiagnostics(), prepared.relaxationAllowed())));
            long routingStart = System.nanoTime();
            // Piece 4/5 wiring: derive the grounded references' DOMAIN set from the memoized grounded set
            // and pass it so the (config-OFF) conflict trigger is reachable. Gated on the SAME property as
            // the resolver's trigger, so with the trigger OFF (default) we call the two-arg overload and
            // behaviour — plus every existing resolver mock — is byte-identical. The trigger never denies;
            // it only makes the near-tie rerank's error handling stricter on a genuine cross-domain conflict.
            Set<String> groundedDomainIds = conflictTriggerEnabled
                    ? groundedDomainIds(prepared.groundedSet()) : Set.of();
            ResolverResult resolved = groundedDomainIds.isEmpty()
                    ? resolver.resolveContextual(prepared.maskedRoutingText(), prepared.relaxationAllowed())
                    : resolver.resolveContextual(prepared.maskedRoutingText(), prepared.relaxationAllowed(),
                            groundedDomainIds);
            recordGatewayStage("routing", System.nanoTime() - routingStart);
            resolved = withPrimaryCandidate(resolved);

            List<AgentsResolvedData.AgentRef> selectedRefs = resolved.selected().stream()
                    .map(c -> new AgentsResolvedData.AgentRef(
                            c.manifest().agentId(), c.manifest().protocol(), c.score()))
                    .collect(Collectors.toList());
            markGatewayDomain(domainsOf(resolved.selected().stream()
                    .map(c -> c.manifest()).collect(Collectors.toList())));
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
                // ── STAGE 3: ABSTAIN TRIAGE (routing produced no route) ───────────────
                // Routing abstained — but that is now a ROUTING outcome, not a request disposition.
                // Triage in order: (1) answer from prior context if this turn carries data to reuse;
                // (2) a DETERMINISTIC required-entity CLARIFY built from the broad candidate list, so a
                // vague-but-in-domain ask ("show me holdings") clarifies WITH the caller's in-book
                // options instead of a bare "no service"; (3) only then the clean no-service message.
                if (carryContext && hasPriorAssistantData(request)) {
                    String answer = answerSynthesizer.synthesizeFromHistory(request.messages(), latestPrompt, emitter, streamId, requestStart);
                    setTraceOutput(rootSpan, answer);
                    emitRequestOutcome("ANSWERED");
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return;
                }
                if (attemptRequiredEntityClarify(resolved, preExtracted, principal, tenantId, callerToken,
                        emitter, streamId, requestId, conversationId, requestStart, request)) {
                    return;
                }
                emitRequestOutcome("FAILED");
                // Own the limitation, do not blame the user: routing could not match the request to a
                // capability we actually have. Domain-specific wording comes from the manifest.
                streamTextAndComplete(emitter, msg("needs_more_detail",
                        "Sorry — none of the services I can reach are able to answer that as asked. "
                        + "If you add a little detail about what you need, I'll try again."), streamId);
                tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                return;
            }

            // ── REQUESTED-CAPABILITY-GROUP MODEL (routing spec V2 Piece 4) ───────────────
            // Partition the routed capabilities into requested GROUPS. When the Piece-5 reranker named an
            // explicit multi-facet shortlist there is one group per facet; otherwise (the common case —
            // a single confident pick or a balanced cross-domain fan-out) there is exactly ONE group of
            // every selected candidate. The multi-group path runs structural authz + coverage + binding +
            // execution + withhold PER GROUP and merges the allowed groups, so an entitled facet is served
            // while a denied facet is withheld honestly instead of first-survivor roulette. The
            // single-group path falls through to the pre-Piece-4 flat disposition below UNCHANGED, so the
            // overwhelmingly common single-capability request is byte-identical to today.
            // Multi-entity COMPARE expansion (§D3): cross-product facet groups × entity bindings AFTER
            // routing, on the untouched masked text. A no-op below two bindings, so the plan (and every
            // single-entity path) is byte-identical. ≥2 entity groups ⇒ the per-group disposition engine.
            ExpandResult expansion = expandPerEntity(buildRequestedPlan(resolved), bindingSet);
            RequestedPlan plan = expansion.plan();

            // ── COMPARE-CLARIFY (spec §3): the user named more entity references than we bound ──────────
            // Deterministic clarify (World-B rules 2/6) instead of a silent one-sided serve. Fires ONLY
            // post-routing (a real route exists) and ONLY when ≥1 entity is bound-and-covered. Precedence
            // deny > clarify holds by construction: a coverage-denied entity FORMS a binding, so it is
            // never in `unbound` — the existing PARTIAL/deny paths own it; this fires only for UNBOUND
            // references. Zero agent/synth spend. Sits before BOTH the multi-group engine and the flat
            // path, since a one-sided PC-1 lands in a flat single-selection group.
            if (residualDetectionEnabled) {
                EntityBindingSet clarifyBindings = EntityBindingSet.deriveAll(prepared.groundedSet());
                if (clarifyBindings.allowedCount() >= 1) {
                    List<UnboundReference> unbound = referenceGrounding.detectUnboundReferences(
                            prepared.groundedSet(), clarifyBindings, latestPrompt, tenantId, callerToken);
                    if (!unbound.isEmpty()) {
                        emitRequestOutcome("CLARIFIED");
                        streamTextAndComplete(emitter,
                                buildCompareClarification(clarifyBindings, unbound,
                                        clarifyScopeSubDomain(resolved, clarifyBindings)), streamId);
                        tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                        return;
                    }
                }
            }

            if (plan.isMultiGroup()) {
                disposeGroups(plan, request, emitter, latestPrompt, conversationId, userId, principal,
                        tenantId, groundedMemo, preExtracted, requestId, requestStart, rootSpan,
                        streamId, callerToken, prepared, expansion.cappedNote());
                return;
            }

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
                // Structural capability-unavailable copy, scoped to the retained pre-authz candidate's
                // sub-domain (never HashMap-order roulette); the generic fallback keeps this byte-identical
                // when a sub-domain declares no `capability_unavailable` key (bug-238 class).
                streamTextAndComplete(emitter, msg("capability_unavailable",
                        "You do not have access to any of the required services for this query.",
                        subDomainOf(manifests)), streamId);
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
            //
            // A domain is withheld ONLY if NONE of its selected agents survived the gate. If any agent
            // in the domain is still allowed (e.g. settlement_status survives while the higher-
            // classification settlement_risk is pruned), the domain IS served below — labeling it
            // "outside your access" would contradict the data returned for it (bug-260). So withheld =
            // referenced domains MINUS served domains, not "every pruned agent's domain".
            final java.util.Set<String> servedDomains = new java.util.HashSet<>(finalDomains);
            final List<String> withheldDomains = computeWithheldDomains(manifests, servedDomains);

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
                // tenantId is the request-scoped value hoisted above (used by Stage-1 grounding too).
                String principalId = principal.id();

                if (coverage != null) {
                    try {
                        // The classifier extracts from the client-sent conversation window:
                        // latest user mention wins, otherwise the most recent prior user mention.
                        // There is no server-side carry-forward path here.
                        boolean hasConversationReference = coverageEntity != null && preExtracted != null
                                && preExtracted.reference(coverageEntity.extractAs()) != null;
                        boolean memoApplies = consumeGroundingMemo(groundedMemo, em, coverageEntity);

                        if (memoApplies) {
                            // Stage-1 grounding already RESOLVED + CHECK-allowed this exact id for this
                            // turn and routed sub-domain — consume the memo instead of re-resolving
                            // (the "+1 resolve/turn" the grounding step spent is repaid here). Emit the
                            // passing gate so the trace still shows a green coverage row.
                            coverageRelId = groundedMemo.resolvedId();
                            tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                                GateData.allow(GateData.GATE_COVERAGE,
                                    coverageRelId + " in " + principalId + "'s book",
                                    resourceScopedAgent.agentId())));
                            annotateCoverageDecision(span, rootSpan, "coverage", true, "grounded");
                        } else if (hasConversationReference) {
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
            long resolutionStart = System.nanoTime();
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
            recordGatewayStage("resolution", System.nanoTime() - resolutionStart);

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
                markGatewayPath("flat");
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

            // Total failure: agents were dispatched and NONE succeeded. The synthesizer still
            // renders honest prose ("data unavailable") over the MISSING sections, and the stream
            // has already begun, so the HTTP status cannot change. But the request must not be
            // recorded as ANSWERED — otherwise metrics, the SLO board, eval and audit all report
            // success for a request that returned zero data, and a load test that checks only the
            // status code reports a false green. Note the guard on failureCount: a plan whose nodes
            // were all CLEANLY SKIPPED (condition false) has okCount==0 too, and is not a failure.
            boolean noAgentData = okCount == 0 && failureCount > 0;
            if (noAgentData) {
                emitRequestNoData();
            }
            if (rootSpan != null) {
                rootSpan.setAttribute("conduit.agents.ok", okCount);
                rootSpan.setAttribute("conduit.agents.failed", failureCount);
                rootSpan.setAttribute("conduit.answer.degraded", failureCount > 0);
            }

            long synthesisStart = System.nanoTime();
            String answer = answerSynthesizer.synthesize(results, latestPrompt, request.messages(), emitter, streamId, withheldDomains, requestStart);
            recordGatewayStage("synthesis", System.nanoTime() - synthesisStart);
            setTraceOutput(rootSpan, answer);
            emitRequestOutcome(noAgentData ? "FAILED" : "ANSWERED");
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
        markGatewayPath("dag");
        markGatewayDomain(goal.domain());
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

    // ═══ Production-path routing decision — the Piece-6 test/dev decision endpoint's engine ══════════

    /**
     * Runs the REAL shared pre-routing preparation for ONE turn and projects the outcome into a
     * {@link RouteDecision}, STOPPING before any agent is invoked (routing spec V2 Piece 6). This is the
     * engine behind the test-profile decision endpoint the goal-pick harness gates on.
     *
     * <p><b>Not a fork.</b> The sequence below is the exact production {@code handleFetchData} pre-routing
     * path — {@link IntentClassifier#classify} → {@link RoutePreparer#prepare} → the same
     * {@code resolveContextual} overload → {@link #withPrimaryCandidate} → {@link #buildRequestedPlan} →
     * per-group {@link EntitlementService#filterAgents} — reusing the SAME beans the chat request path
     * uses (no forked masking, no forked resolver). The projection stops before input synthesis /
     * execution, so no agent is called and nothing mutates. {@link RouteDecision#path()} is stamped
     * {@code "production"} as a witness that this ran, not a debug shortcut.
     *
     * <p><b>Disposition scope.</b> {@link RouteDecision#disposition()} carries the structural (Cerbos)
     * authorization verdict per requested capability — the gate that decides a persona-token conflict row
     * — computed without invoking agents. The coverage (book-of-business) verdict is surfaced separately
     * as {@link RouteDecision#focalVerdict()} / {@link RouteDecision#overallDisposition()}. DAG
     * producer-closure pruning is NOT re-run here (it needs execution-time closure); a DAG group's
     * disposition reflects its own structural authz only.
     *
     * @param request     the client-sent conversation window (same DTO the chat path takes).
     * @param jwtPrincipal the verified caller principal (never {@code null} on the authenticated endpoint).
     * @param callerToken the caller's own verified bearer token, threaded to the coverage CHECK.
     */
    public RouteDecision decideRoute(ChatRequest request, Principal jwtPrincipal, String callerToken) {
        IntentResult intentResult = intentClassifier.classify(request.messages());
        Intent intent = intentResult.intent();
        EntityBag preExtracted = intentResult.extractedEntities();
        String latestPrompt = extractLatestUserMessage(request);
        // Window policy parity with handleChat's dispatch: a CLARIFY turn routes on its bare message
        // (carryContext=false) so it cannot inherit a confident domain from history; every other intent
        // carries context, exactly as FETCH_DATA/FOLLOW_UP do.
        boolean carryContext = intent != Intent.CLARIFY;

        Principal principal = (jwtPrincipal != null) ? jwtPrincipal : Principal.anonymous();
        String tenantId = jwtPrincipal != null ? jwtPrincipal.tenantId() : "default";

        // 1) Shared preparation (masking + relaxation + full grounded set) — the production bean.
        PreparedRoute prepared = routePreparer.prepare(request, latestPrompt, preExtracted, carryContext,
                false, principal, tenantId, callerToken);

        // 2) Terminal focal grounding verdict — the SAME memo-or-scalar fallback handleFetchData uses.
        GroundingResult focal = prepared.groundedSet().focalVerdict();
        GroundingResult grounding = focal.verdict() != ReferenceGroundingService.Verdict.NONE
                ? focal
                : referenceGrounding.ground(preExtracted, latestPrompt, principal, tenantId, callerToken);

        // 3) Context-aware routing over the MASKED text — the identical overload selection production makes.
        Set<String> groundedDomainIds = conflictTriggerEnabled
                ? groundedDomainIds(prepared.groundedSet()) : Set.of();
        ResolverResult resolved = groundedDomainIds.isEmpty()
                ? resolver.resolveContextual(prepared.maskedRoutingText(), prepared.relaxationAllowed())
                : resolver.resolveContextual(prepared.maskedRoutingText(), prepared.relaxationAllowed(),
                        groundedDomainIds);
        resolved = withPrimaryCandidate(resolved);

        // 4) Requested-capability groups (production partitioner) + multi-entity COMPARE expansion, so the
        //    probe projects the SAME entity-facet groups the fetch path disposes.
        EntityBindingSet bindingSet = EntityBindingSet.derive(prepared.groundedSet());
        boolean multiEntity = bindingSet.multiEntity();
        RequestedPlan plan = expandPerEntity(buildRequestedPlan(resolved), bindingSet).plan();

        // 5) Per-group disposition — structural authorization, plus (for an entity group) THIS entity's own
        //    coverage CHECK result (consumed from the grounded set; no agent invocation, no new coverage call).
        List<RouteDecision.GroupDisposition> dispositions = new ArrayList<>();
        int servedGroups = 0;
        int deniedGroups = 0;
        for (RequestedPlan.RequestedGroup group : plan.groups()) {
            List<AgentManifest> allowed = entitlementService.filterAgents(principal, group.candidates());
            Set<String> allowedIds = allowed.stream().map(AgentManifest::agentId)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            List<String> allowedList = new ArrayList<>();
            List<String> deniedList = new ArrayList<>();
            for (String id : group.requestedCapabilityIds()) {
                if (allowedIds.contains(id)) allowedList.add(id); else deniedList.add(id);
            }
            String disp = allowedList.isEmpty() ? "DENIED"
                    : (deniedList.isEmpty() ? "SERVED" : "PARTIAL");
            // An entity group structurally allowed but whose bound client is NOT covered is withheld →
            // DENIED (the S11/S12 PARTIAL projection). Fail-closed: no covered interpretation ⇒ denied.
            if (group.binding() != null && !"DENIED".equals(disp)
                    && !entityGroupCovered(group, group.binding())) {
                disp = "DENIED";
                if (deniedList.isEmpty()) { deniedList = new ArrayList<>(group.requestedCapabilityIds()); }
                allowedList = new ArrayList<>();
            }
            if ("DENIED".equals(disp)) deniedGroups++; else servedGroups++;
            dispositions.add(new RouteDecision.GroupDisposition(
                    group.requestedCapabilityIds(), allowedList, deniedList, disp));
        }

        // 6) Compare-CLARIFY mirror (spec §3): the SAME detection the fetch path runs, surfaced as
        //    overallDisposition="CLARIFY" + a count so smoke can assert the never-one-sided invariant. Runs
        //    only on a real route with ≥1 bound-and-covered entity; single-entity queries filter to zero.
        int unresolvedCount = 0;
        if (residualDetectionEnabled && !resolved.fallback() && !resolved.selected().isEmpty()) {
            EntityBindingSet clarifyBindings = EntityBindingSet.deriveAll(prepared.groundedSet());
            if (clarifyBindings.allowedCount() >= 1) {
                unresolvedCount = referenceGrounding.detectUnboundReferences(
                        prepared.groundedSet(), clarifyBindings, latestPrompt, tenantId, callerToken).size();
            }
        }
        boolean clarify = unresolvedCount > 0;

        return new RouteDecision(
                RouteDecision.PRODUCTION_PATH,
                preparationVersion,
                prepared.maskDiagnostics().maskMode(),
                prepared.maskedRoutingText(),
                prepared.maskDiagnostics(),
                prepared.relaxationAllowed(),
                grounding.verdict().name(),
                projectGrounded(prepared.groundedSet()),
                projectResolver(resolved),
                projectCandidates(resolved),
                projectGroups(plan),
                dispositions,
                overallDisposition(grounding, resolved, servedGroups, deniedGroups, multiEntity, clarify),
                unresolvedCount);
    }

    /**
     * True when an entity group's bound client passed its OWN coverage CHECK for the group's routed
     * sub-domain (a RESOLVED_ALLOWED interpretation of THIS binding's canonicalId). A DENIED/UNAVAILABLE
     * or non-matching interpretation is NOT covered — fail closed. Consumes the binding's grounded
     * interpretations; makes no coverage call. Used by the {@code /debug/route} probe projection only.
     */
    private boolean entityGroupCovered(RequestedPlan.RequestedGroup group, EntityBinding binding) {
        for (AgentManifest m : group.candidates()) {
            String sd = subDomainIdOf(m);
            if (sd == null) continue;
            for (GroundedInterpretation gi : binding.interpretations()) {
                if (!sd.equals(gi.subDomainId())) continue;
                if (!binding.canonicalId().equals(gi.canonicalId())) continue;
                if (gi.isAllowed()) return true;
            }
        }
        return false;
    }

    /** Terminal disposition class: coverage verdict first, then abstain, then the merged group verdict. */
    private static String overallDisposition(GroundingResult grounding, ResolverResult resolved,
                                             int servedGroups, int deniedGroups, boolean multiEntity,
                                             boolean clarify) {
        // For a multi-entity COMPARE the focal verdict does NOT short-circuit — disposition derives from the
        // SET of entity-group verdicts (§D2). Single-entity keeps the focal coverage verdict first (S4).
        if (!multiEntity) {
            switch (grounding.verdict()) {
                case DENIED -> { return "COVERAGE_DENIED"; }
                case UNAVAILABLE -> { return "COVERAGE_UNAVAILABLE"; }
                default -> { /* fall through */ }
            }
        }
        if (resolved.fallback() || resolved.selected().isEmpty()) return "ABSTAIN";
        // Compare-CLARIFY (deny > clarify): only ever true when a real route exists and ≥1 entity is
        // bound-and-covered AND the detector confirmed a truly UNBOUND reference (a denied entity forms a
        // binding, so it can never make `clarify` true). A one-turn question beats a wrong scope.
        if (clarify) return "CLARIFY";
        if (servedGroups == 0) return multiEntity ? "COVERAGE_DENIED" : "STRUCTURAL_DENIED";
        if (deniedGroups > 0) return "PARTIAL";
        return "SERVED";
    }

    /** Projects each grounded interpretation into a status view — never a raw verbatim text or id. */
    private static List<RouteDecision.GroundedStatus> projectGrounded(GroundedReferenceSet groundedSet) {
        List<RouteDecision.GroundedStatus> out = new ArrayList<>();
        if (groundedSet == null) return out;
        for (ReferenceGroundingService.GroundedMention gm : groundedSet.mentions()) {
            boolean aligned = gm.mention() != null && gm.mention().span() != null;
            for (GroundedInterpretation gi : gm.interpretations()) {
                out.add(new RouteDecision.GroundedStatus(
                        gi.entityKey(), gi.subDomainId(), gi.status().name(),
                        gi.mentionKind() != null ? gi.mentionKind().name() : null,
                        aligned, gi.denialReason(), gm.focal()));
            }
        }
        return out;
    }

    private RouteDecision.ResolverDecision projectResolver(ResolverResult resolved) {
        ResolverResult.PrimaryCandidate pc = resolved.primaryCandidate();
        return new RouteDecision.ResolverDecision(
                resolved.fallback(), resolved.topScore(), resolved.margin(), resolved.rerankFired(),
                resolved.rerankSelectedIds(),
                pc != null ? pc.agentId() : null,
                pc != null ? pc.subDomainId() : null,
                pc != null ? pc.domain() : null);
    }

    /** ALL routed candidates: the selected set (above floor) plus the below-floor skipped tail. */
    private List<RouteDecision.CandidateView> projectCandidates(ResolverResult resolved) {
        List<RouteDecision.CandidateView> out = new ArrayList<>();
        for (RoutingCandidate c : resolved.selected()) out.add(candidateView(c, true));
        for (RoutingCandidate c : resolved.skipped()) out.add(candidateView(c, false));
        return out;
    }

    private RouteDecision.CandidateView candidateView(RoutingCandidate c, boolean selected) {
        AgentManifest m = c.manifest();
        return new RouteDecision.CandidateView(
                m.agentId(), m.domain(), subDomainIdOf(m), m.protocol(), c.score(), selected);
    }

    private static List<RouteDecision.GroupView> projectGroups(RequestedPlan plan) {
        List<RouteDecision.GroupView> out = new ArrayList<>();
        for (RequestedPlan.RequestedGroup g : plan.groups()) {
            out.add(new RouteDecision.GroupView(
                    g.requestedCapabilityIds(), g.kind().name(), g.goalId(),
                    g.requiredEntityKeys(), g.routingEvidence()));
        }
        return out;
    }

    // ═══ Requested-capability-group model + per-group disposition (routing spec V2 Piece 4) ══════════

    /**
     * Partition the routed capabilities into requested {@link RequestedPlan.RequestedGroup groups}. When
     * the Piece-5 reranker named an explicit multi-facet shortlist
     * ({@link ResolverResult#rerankSelectedIds()} non-empty and ≥2 back-able), there is ONE group per
     * facet; otherwise there is exactly one group of every selected candidate — the pre-Piece-4 flat set,
     * so the common single-capability request stays byte-identical. Groups are NEVER inferred from raw
     * below-floor noise (spec DO-NOT): only from the resolver's selection + the reranker's explicit ids.
     */
    private RequestedPlan buildRequestedPlan(ResolverResult resolved) {
        List<AgentManifest> selected = resolved.selected().stream().map(c -> c.manifest()).toList();
        List<String> facetIds = resolved.rerankSelectedIds();
        if (facetIds == null || facetIds.isEmpty()) {
            return new RequestedPlan(List.of(groupOf(selected, "single-selection")));
        }
        List<RequestedPlan.RequestedGroup> groups = new ArrayList<>();
        for (String id : facetIds) {
            AgentManifest m = findManifest(id, resolved);
            if (m != null) groups.add(groupOf(List.of(m), "rerank-facet"));
        }
        // A reranker that named ≤1 back-able facet is not a genuine multi-group request — fall back to the
        // single flat group rather than split off a lone facet and silently drop the rest.
        if (groups.size() <= 1) {
            return new RequestedPlan(List.of(groupOf(selected, "single-selection")));
        }
        return new RequestedPlan(groups);
    }

    /** Builds one requested group over {@code manifests}, deriving flat/DAG kind + goal from the manifests. */
    private RequestedPlan.RequestedGroup groupOf(List<AgentManifest> manifests, String evidence) {
        List<String> ids = manifests.stream().map(AgentManifest::agentId).toList();
        String goalId = manifests.stream().filter(ChatService::hasProducedRefConsume)
                .map(AgentManifest::agentId).findFirst().orElse(null);
        RequestedPlan.RequestedGroup.Kind kind = goalId != null
                ? RequestedPlan.RequestedGroup.Kind.DAG : RequestedPlan.RequestedGroup.Kind.FLAT;
        return new RequestedPlan.RequestedGroup(ids, manifests, kind, goalId,
                requiredEntityKeysFor(manifests), evidence);
    }

    private List<String> requiredEntityKeysFor(List<AgentManifest> manifests) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (AgentManifest m : manifests) {
            EffectiveManifest em = manifestStore.getEffective(m.agentId(), m.domain(), m.subDomain());
            if (em != null && em.requiredContext() != null) keys.addAll(em.requiredContext());
        }
        return List.copyOf(keys);
    }

    /**
     * The routed manifest for {@code id}, searched in the ABOVE-FLOOR {@link ResolverResult#selected()}
     * set ONLY. A requested group is never formed from a below-floor ({@code skipped()}) candidate: the
     * reranker's top-5 shortlist is taken BEFORE the confidence floor, so a {@code multiple([ids])} may
     * name a below-floor id — grouping it would let it be invoked past the floor (spec Piece 4: groups
     * come from above-floor candidates only; DO-NOT infer groups from noise). A named id that is not
     * above-floor returns {@code null}, so {@code buildRequestedPlan} drops it (and falls back to the
     * single flat group when fewer than two facets survive).
     */
    private static AgentManifest findManifest(String id, ResolverResult resolved) {
        return resolved.selected().stream()
                .map(c -> c.manifest())
                .filter(m -> id.equals(m.agentId()))
                .findFirst().orElse(null);
    }

    /**
     * Multi-entity COMPARE expansion (Multi-Entity Compare §D3/D4): cross-product the reranker's facet
     * groups × the per-entity bindings into {@code entity-facet} groups, each carrying its OWN binding.
     * A NO-OP below two bindings — every existing plan is returned unchanged, so the single-entity path is
     * byte-identical and the whole feature is inert. Capping is deterministic (mention order) and NEVER
     * silent: {@code max-entity-bindings} bounds entities, {@code max-total-groups} bounds F·E, and a cap
     * is logged, traced, and surfaced in the answer.
     */
    private ExpandResult expandPerEntity(RequestedPlan plan, EntityBindingSet bindings) {
        return expandPerEntity(plan, bindings, maxEntityBindings, maxTotalGroups);
    }

    /** Pure core of {@link #expandPerEntity(RequestedPlan, EntityBindingSet)} with explicit caps (testable). */
    static ExpandResult expandPerEntity(RequestedPlan plan, EntityBindingSet bindings,
                                        int maxEntityBindings, int maxTotalGroups) {
        if (bindings == null || !bindings.multiEntity()) {
            return new ExpandResult(plan, null);
        }
        List<EntityBinding> all = bindings.bindings();
        int keptBindings = Math.min(all.size(), Math.max(1, maxEntityBindings));
        List<EntityBinding> useBindings = all.subList(0, keptBindings);
        int requested = plan.groups().size() * all.size();

        List<RequestedPlan.RequestedGroup> expanded = new ArrayList<>();
        for (RequestedPlan.RequestedGroup g : plan.groups()) {
            for (EntityBinding b : useBindings) {
                if (expanded.size() >= Math.max(1, maxTotalGroups)) break;
                expanded.add(g.withEntityBinding(b));
            }
        }
        boolean capped = keptBindings < all.size() || expanded.size() < requested;
        String note = null;
        if (capped) {
            int keptEntities = (int) expanded.stream()
                    .map(gr -> gr.binding().canonicalId()).distinct().count();
            log.warn("Multi-entity COMPARE capped: {} entities named, compared {} (max-entity-bindings={}, "
                            + "max-total-groups={}); {} of {} facet×entity groups kept",
                    all.size(), keptEntities, maxEntityBindings, maxTotalGroups, expanded.size(), requested);
            note = "The request named more entities than can be compared at once; only the first "
                    + keptEntities + " were compared. State this and invite the user to ask again for the rest.";
        }
        return new ExpandResult(new RequestedPlan(expanded), note);
    }

    /** The expanded plan plus a non-null cap note when the fan-out was capped (surfaced in the answer). */
    record ExpandResult(RequestedPlan plan, String cappedNote) {}

    /**
     * Entity-qualify each served node's {@code nodeId} to {@code agentId#canonicalId} for a COMPARE group
     * so two calls to the SAME agent (two clients) are distinguishable in synthesis and trace. The bare
     * {@code agentId} is retained (manifest lookups / trace tags), so figures and DATA headers attribute
     * per client while numeric validation stays per-figure. A NO-OP for non-entity groups (binding null),
     * keeping {@code nodeId == agentId} byte-identical on the single-entity path.
     */
    private static List<NodeResult> qualifyEntityNodeIds(List<NodeResult> results, EntityBinding binding) {
        if (binding == null || binding.canonicalId() == null || results == null) return results;
        List<NodeResult> out = new ArrayList<>(results.size());
        for (NodeResult r : results) {
            out.add(new NodeResult(r.agentId() + "#" + binding.canonicalId(), r.agentId(), r.protocol(),
                    r.status(), r.data(), r.latencyMs(), r.errorMessage()));
        }
        return out;
    }

    /** The per-client DATA-header label for a served entity: resolver-produced id + canonical name. */
    private static String entityLabel(EntityBinding binding) {
        if (binding == null) return null;
        String name = binding.canonicalName();
        return (name == null || name.isBlank())
                ? binding.canonicalId()
                : binding.canonicalId() + " \"" + name + "\"";
    }

    /** Attaches the diagnostic {@link ResolverResult.PrimaryCandidate} (post-rerank leader, pre-filter). */
    private ResolverResult withPrimaryCandidate(ResolverResult resolved) {
        if (resolved == null || resolved.selected() == null || resolved.selected().isEmpty()) return resolved;
        AgentManifest leader = resolved.selected().get(0).manifest();
        String subDomainId = subDomainIdOf(leader);
        Span.current().setAttribute("conduit.routing.primary_agent", safeMetricTag(leader.agentId()));
        if (subDomainId != null) Span.current().setAttribute("conduit.routing.primary_subdomain", subDomainId);
        return resolved.withPrimaryCandidate(
                new ResolverResult.PrimaryCandidate(leader.agentId(), subDomainId, leader.domain()));
    }

    /** The grounded references' DOMAIN ids (sub-domain → parent domain) for the Piece-5 conflict trigger. */
    private java.util.Set<String> groundedDomainIds(GroundedReferenceSet groundedSet) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (groundedSet == null) return out;
        for (GroundedInterpretation gi : groundedSet.allInterpretations()) {
            String sd = gi.subDomainId();
            if (sd == null) continue;
            manifestStore.getSubDomain(sd)
                    .map(s -> s.parentDomain())
                    .filter(d -> d != null && !d.isBlank())
                    .ifPresent(out::add);
        }
        return out;
    }

    private String subDomainIdOf(AgentManifest m) {
        try {
            EffectiveManifest em = manifestStore.getEffective(m.agentId(), m.domain(), m.subDomain());
            return em != null && em.subDomainId() != null ? em.subDomainId() : m.subDomain();
        } catch (RuntimeException e) {
            return m.subDomain();
        }
    }

    /** The sub-domain id of a candidate list's first (retained pre-authz) manifest — for denial copy. */
    private String subDomainOf(List<AgentManifest> manifests) {
        return (manifests == null || manifests.isEmpty()) ? null : subDomainIdOf(manifests.get(0));
    }

    /**
     * Per-group disposition: structural authz + coverage + binding + execution + withhold run PER GROUP,
     * then the allowed groups are MERGED into one answer. Rules (spec Piece 4):
     * <ul>
     *   <li>all groups denied → structural {@code capability_unavailable} copy;</li>
     *   <li>some denied → serve the allowed groups, label the fully-withheld domains honestly (bug-260
     *       scoping: a domain still served by ANY group is never withheld);</li>
     *   <li>a DAG group whose required producer is authorization-pruned → DENY that group, never a silent
     *       flat-fallback;</li>
     *   <li>a group with no requested facet is never formed, so a denial is only ever recorded for a
     *       capability the user actually requested (noise never surfaces a user-visible denial).</li>
     * </ul>
     * The memoized {@link GroundedReferenceSet} is consumed for coverage — no reference is re-resolved.
     */
    private void disposeGroups(RequestedPlan plan, ChatRequest request, SseEmitter emitter,
                               String latestPrompt, String conversationId, String userId, Principal principal,
                               String tenantId, GroundingResult groundedMemo, EntityBag preExtracted,
                               String requestId, long requestStart, Span rootSpan, String streamId,
                               String callerToken, PreparedRoute prepared, String cappedNote) throws Exception {
        markGatewayPath("groups");
        List<NodeResult> merged = new ArrayList<>();
        java.util.Set<String> servedDomains = new java.util.LinkedHashSet<>();
        List<AgentManifest> deniedUserVisible = new ArrayList<>();
        List<AgentManifest> allCandidates = new ArrayList<>();
        // "which resource?" questions from entitled groups whose required entity is unresolved. Emitted
        // at merge ONLY when no group served — a served answer is never pre-empted by a clarify.
        List<String> clarifyQuestions = new ArrayList<>();
        // Multi-entity COMPARE attribution (empty on every non-COMPARE request → single-entity byte-identical):
        //  • entityLabels: nodeId → "<canonicalId> \"<canonicalName>\"" for each served entity-qualified node.
        //  • withheldEntities: uncovered clients — user's verbatim + the exact manifest denial copy (S4 parity).
        Map<String, String> entityLabels = new java.util.LinkedHashMap<>();
        List<AnswerSynthesizer.WithheldEntity> withheldEntities = new ArrayList<>();
        // Merge-copy fix (D3): track whether the denials that produced an all-denied merge were coverage
        // denials (→ coverage copy, S4) rather than structural (→ capability-unavailable copy).
        boolean[] anyCoverageDenied = {false};
        String[] lastCoverageDenial = {null, null}; // [reason, subDomainId]

        for (RequestedPlan.RequestedGroup group : plan.groups()) {
            allCandidates.addAll(group.candidates());

            // (1) Structural authz over THIS group's own candidates (scoped, not request-global).
            entitlementService.explainStructuralGates(principal, group.candidates())
                    .forEach(g -> tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                            new GateData(g.gate(), g.allow() ? GateData.EFFECT_ALLOW : GateData.EFFECT_DENY,
                                    g.reason(), g.agent()))));
            List<AgentManifest> allowed = entitlementService.filterAgents(principal, group.candidates());

            // (2) DAG producer prune → deny this group (never a silent flat-fallback). Closure is derived
            //     intra-group here (ground/bind → closure → authz), not at group formation (spec Piece 4/6).
            boolean dagProducerDenied = group.isDag()
                    && dagProducerPruned(group, principal, preExtracted);

            if (allowed.isEmpty() || dagProducerDenied) {
                recordGroupDenied(group, requestId, conversationId, userId, dagProducerDenied);
                deniedUserVisible.add(group.candidates().get(0));
                continue;
            }

            // (3) Coverage — consume the memoized grounded set for this group's sub-domain (no re-resolve).
            //     For an entity group the binding filters coverage to THIS client's own CHECK result.
            GroupCoverage cov = coverageForGroup(allowed, prepared.groundedSet(), groundedMemo, principal,
                    tenantId, callerToken, requestId, conversationId, preExtracted, request, group.binding());
            if (cov.denied()) {
                recordGroupDenied(group, requestId, conversationId, userId, false);
                deniedUserVisible.add(group.candidates().get(0));
                anyCoverageDenied[0] = true;
                lastCoverageDenial[0] = cov.denialReason();
                lastCoverageDenial[1] = cov.deniedSubDomainId();
                // WITHHELD BEFORE BINDING (no-leak): an uncovered entity's data never enters synthesis —
                // the ONLY text about it is the user's own verbatim + the same manifest denial copy a
                // standalone ask streams. Attribution uses the user's words, never the resolver's name/id.
                if (group.binding() != null) {
                    withheldEntities.add(new AnswerSynthesizer.WithheldEntity(
                            group.binding().userVerbatim(),
                            mapDenialReason(cov.denialReason(), cov.deniedSubDomainId())));
                }
                continue;
            }
            if (cov.outcome() == GroupCoverageOutcome.CLARIFY) {
                // Entitled caller with an unresolved required entity — collect the clarify; emit at merge
                // only if no group served. The group is NEVER bound (no unchecked id reaches an agent).
                clarifyQuestions.add(cov.clarifyQuestion());
                deniedUserVisible.add(group.candidates().get(0));
                continue;
            }
            if (cov.outcome() == GroupCoverageOutcome.WITHHOLD) {
                // Fail-closed: unresolved required coverage with no clarify formable — never bind an id.
                recordGroupDenied(group, requestId, conversationId, userId, false);
                deniedUserVisible.add(group.candidates().get(0));
                continue;
            }

            // (4) Bind + execute. A DAG group runs through tryDag (its producer prune was already handled
            //     above as a group denial, so the fall-through here is an intra-group flat plan only).
            EntityBag groupBag = injectCoverage(preExtracted, cov);
            SynthesisResult synthesis = (groupBag != null)
                    ? inputSynthesizer.synthesize(groupBag, allowed)
                    : inputSynthesizer.synthesize(latestPrompt, allowed);
            if (synthesis.inputs().isEmpty()) {
                // Nothing bindable (an unresolved required entity) — withhold rather than fabricate an id.
                deniedUserVisible.add(group.candidates().get(0));
                continue;
            }
            List<NodeResult> results = tryDag(synthesis, allowed, groupBag, principal, requestId,
                    conversationId, callerToken).orElseGet(() -> {
                        List<PlanNode> nodes = synthesis.inputs().entrySet().stream()
                                .map(e -> new PlanNode(e.getKey(), allowed.stream()
                                        .filter(a -> a.agentId().equals(e.getKey())).findFirst().orElseThrow(),
                                        e.getValue(), List.of()))
                                .collect(Collectors.toList());
                        nodes.forEach(n -> tracePublisher.publish(TraceEvent.of("agent_start", requestId,
                                conversationId, new AgentStartData(n.nodeId(), n.agent().protocol()))));
                        return executor.execute(new Plan(nodes), callerToken);
                    });
            // Entity-qualify the served nodes so two calls to the SAME agent (two clients) are
            // distinguishable in synthesis (DATA headers + figures keyed by nodeId = agentId#canonicalId),
            // and record each node's entity label. No-op for non-entity groups (binding == null).
            List<NodeResult> groupResults = qualifyEntityNodeIds(results, group.binding());
            if (group.binding() != null) {
                String label = entityLabel(group.binding());
                groupResults.forEach(r -> entityLabels.put(r.nodeId(), label));
            }
            groupResults.forEach(r -> {
                String prev = r.isOk() && r.data() != null
                        ? r.data().toString().substring(0, Math.min(80, r.data().toString().length()))
                        : r.status().name();
                String status = r.isOk() ? "ok" : (r.isCleanSkip() ? "skipped" : "failed");
                tracePublisher.publish(TraceEvent.of("agent_complete", requestId, conversationId,
                        new AgentCompleteData(r.agentId(), r.latencyMs(), status, prev)));
            });
            merged.addAll(groupResults);
            allowed.stream().map(AgentManifest::domain).filter(d -> d != null && !d.isBlank())
                    .forEach(servedDomains::add);
        }

        int okAgents = (int) merged.stream().filter(NodeResult::isOk).count();
        int failedAgents = (int) merged.stream().filter(NodeResult::isFailure).count();

        // MERGE — nothing served. An ENTITLED caller whose required entity is merely unresolved is
        // CLARIFIED (ask which resource), NOT told they lack access; only a genuine all-denied request
        // gets the structural capability-unavailable copy.
        if (merged.isEmpty()) {
            if (!clarifyQuestions.isEmpty()) {
                emitRequestOutcome("CLARIFIED");
                streamTextAndComplete(emitter, clarifyQuestions.get(0), streamId);
                tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                return;
            }
            emitRequestOutcome("DENIED");
            tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                    new CheckDeniedData("structural", null, userId, "no-covered-groups", "cerbos")));
            // Merge-copy fix (D3): when every denial was an entity-COVERAGE denial (comparing clients you
            // cover NONE of), stream the manifest coverage copy — the same S4 wording — not the structural
            // "you lack the service" copy. (Multi-entity all-denied is normally pre-empted before routing;
            // this defends the case where routing still forms entity groups that all deny.)
            String denyCopy = (anyCoverageDenied[0] && deniedUserVisible.size() == plan.groups().size())
                    ? mapDenialReason(lastCoverageDenial[0], lastCoverageDenial[1])
                    : msg("capability_unavailable",
                            "You do not have access to any of the required services for this query.",
                            subDomainOf(allCandidates));
            streamTextAndComplete(emitter, denyCopy, streamId);
            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
            return;
        }

        // Some served, some denied → serve the allowed groups; label ONLY fully-withheld domains (bug-260:
        // a domain still served by any group is never withheld).
        List<String> withheldDomains = computeWithheldDomains(deniedUserVisible, servedDomains);
        if (okAgents > 0 && failedAgents > 0) emitRequestPartial();
        boolean noAgentData = okAgents == 0 && failedAgents > 0;
        if (noAgentData) emitRequestNoData();
        if (rootSpan != null) {
            rootSpan.setAttribute("conduit.agents.ok", okAgents);
            rootSpan.setAttribute("conduit.agents.failed", failedAgents);
            rootSpan.setAttribute("conduit.answer.degraded", failedAgents > 0);
            rootSpan.setAttribute("conduit.plan.node_count", merged.size());
        }

        tracePublisher.publish(TraceEvent.of("synthesis_start", requestId, conversationId,
                new SynthesisStartData(merged.size(), okAgents)));
        publishGroundedFigures(requestId, conversationId, merged);

        long synthesisStart = System.nanoTime();
        // Single-entity / non-COMPARE multi-group: no entity attribution → the 7-arg overload keeps the
        // synthesis prompt byte-identical. A COMPARE request threads per-client DATA labels, withheld-entity
        // blocks, and the cap note through the entity-aware overload.
        boolean entityCompare = !entityLabels.isEmpty() || !withheldEntities.isEmpty()
                || (cappedNote != null && !cappedNote.isBlank());
        String answer = entityCompare
                ? answerSynthesizer.synthesize(merged, latestPrompt, request.messages(), emitter, streamId,
                        withheldDomains, requestStart, entityLabels, withheldEntities, cappedNote)
                : answerSynthesizer.synthesize(merged, latestPrompt, request.messages(), emitter,
                        streamId, withheldDomains, requestStart);
        recordGatewayStage("synthesis", System.nanoTime() - synthesisStart);
        setTraceOutput(rootSpan, answer);
        emitRequestOutcome(noAgentData ? "FAILED" : "ANSWERED");
        servedDomains.forEach(this::emitAssistantDomain);
        tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                new RequestCompleteData(System.currentTimeMillis() - requestStart, merged.size(), okAgents)));
    }

    /** How one group's coverage resolved (per-group disposition, spec Piece 4). */
    private enum GroupCoverageOutcome { PROCEED, DENIED, CLARIFY, WITHHOLD }

    /**
     * The coverage outcome for one group.
     * <ul>
     *   <li>{@code PROCEED} — bind with {@code coverageRelId} (an ALLOWED id), or with no id at all when
     *       the group needs no resource-scoped coverage;</li>
     *   <li>{@code DENIED} — fail-closed access denial (a matched out-of-book DENY, or coverage
     *       UNAVAILABLE for the group's own sub-domain);</li>
     *   <li>{@code CLARIFY} — an ENTITLED caller whose required entity is unresolved is asked which
     *       resource ({@code clarifyQuestion}) — never told they lack access;</li>
     *   <li>{@code WITHHOLD} — fail-closed when no clarify can be formed (empty book / coverage down).</li>
     * </ul>
     */
    private record GroupCoverage(GroupCoverageOutcome outcome, String coverageRelId,
                                 EntityType coverageEntity, String clarifyQuestion,
                                 String denialReason, String deniedSubDomainId) {
        boolean denied() { return outcome == GroupCoverageOutcome.DENIED; }
        static GroupCoverage proceed(String id, EntityType e) {
            return new GroupCoverage(GroupCoverageOutcome.PROCEED, id, e, null, null, null);
        }
        static GroupCoverage denied(EntityType e) {
            return new GroupCoverage(GroupCoverageOutcome.DENIED, null, e, null, null, null);
        }
        /** A coverage DENY carrying the reason code + owning sub-domain so an entity withhold can quote
         *  the exact same manifest denial copy a standalone ask for that client streams (S4 parity). */
        static GroupCoverage denied(EntityType e, String reason, String subDomainId) {
            return new GroupCoverage(GroupCoverageOutcome.DENIED, null, e, null, reason, subDomainId);
        }
        static GroupCoverage clarify(String question, EntityType e) {
            return new GroupCoverage(GroupCoverageOutcome.CLARIFY, null, e, question, null, null);
        }
        static GroupCoverage withhold(EntityType e) {
            return new GroupCoverage(GroupCoverageOutcome.WITHHOLD, null, e, null, null, null);
        }
    }

    /**
     * Coverage for ONE group, consuming the memoized {@link GroundedReferenceSet} rather than re-resolving.
     * The first resource-scoped allowed agent's sub-domain is matched against the grounded interpretations:
     * a DENIED interpretation denies the group; a sub-domain-matching UNAVAILABLE interpretation FAILS
     * CLOSED (coverage could not be verified — mirrors the flat path's UNAVAILABLE terminal deny); a
     * RESOLVED_ALLOWED one yields its canonical id to bind; the single-lattice focal memo is honoured for
     * the scalar-bag path.
     *
     * <p>A resource-scoped, required-coverage group with NO allowed interpretation and no allowed memo has
     * an UNRESOLVED required entity. It must NOT bind+invoke an unchecked id — {@link EntityResolver}
     * binds an id-shaped reference VERBATIM with no lookup, so a group whose bag still carries a coverage
     * reference would otherwise run an agent on an id that never passed CHECK (the fail-open hole). Such a
     * group is CLARIFIED (an entitled caller is asked which resource, reusing the flat clarify machinery)
     * or, when no clarify can be formed, WITHHELD — never bound.
     */
    private GroupCoverage coverageForGroup(List<AgentManifest> allowed, GroundedReferenceSet groundedSet,
                                           GroundingResult groundedMemo, Principal principal, String tenantId,
                                           String callerToken, String requestId, String conversationId,
                                           EntityBag preExtracted, ChatRequest request, EntityBinding binding) {
        for (AgentManifest m : allowed) {
            if (!entitlementService.requiresCoverage(m)) continue;
            EffectiveManifest em = manifestStore.getEffective(m.agentId(), m.domain(), m.subDomain());
            if (em == null || !em.resourceScoped()) continue;
            EntityType coverageEntity = coverageEntityType(em);
            for (GroundedInterpretation gi : groundedSet.allInterpretations()) {
                if (!em.subDomainId().equals(gi.subDomainId())) continue;
                if (coverageEntity != null && gi.entityType() != null
                        && !coverageEntity.key().equals(gi.entityType().key())) continue;
                // SECURITY (rule 4f): an entity group's coverage verdict is THIS entity's own CHECK result
                // for the routed sub-domain and NOTHING else. The scan is filtered to the group's own bound
                // canonicalId so a sibling client's ALLOWED interpretation can never serve this group — the
                // core no-leak filter. Null binding = the single-entity path, unchanged.
                if (!bindingSelectsInterpretation(binding, gi)) continue;
                if (gi.isDenied()) {
                    tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                            GateData.deny(GateData.GATE_COVERAGE,
                                    gi.canonicalId() + " not in " + principal.id() + "'s book", m.agentId())));
                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                            new CheckDeniedData("coverage", gi.canonicalId(), principal.id(),
                                    gi.denialReason(), "coverage")));
                    return GroupCoverage.denied(coverageEntity, gi.denialReason(), gi.subDomainId());
                }
                if (gi.status() == ReferenceGroundingService.GroundStatus.UNAVAILABLE) {
                    // FAIL CLOSED — the coverage service was unreachable for THIS group's own sub-domain,
                    // so we cannot verify the id is in-book. Never grant access on an unverified id
                    // (matches the flat path's UNAVAILABLE terminal deny).
                    tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                            GateData.deny(GateData.GATE_COVERAGE,
                                    "coverage unavailable for " + principal.id(), m.agentId())));
                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                            new CheckDeniedData("coverage", gi.canonicalId(), principal.id(),
                                    "coverage-unavailable", "coverage")));
                    return GroupCoverage.denied(coverageEntity, "coverage-unavailable", gi.subDomainId());
                }
                if (gi.isAllowed()) {
                    tracePublisher.publish(TraceEvent.of("gate", requestId, conversationId,
                            GateData.allow(GateData.GATE_COVERAGE,
                                    gi.canonicalId() + " in " + principal.id() + "'s book", m.agentId())));
                    return GroupCoverage.proceed(gi.canonicalId(), coverageEntity);
                }
                // NOT_FOUND / AMBIGUOUS for this sub-domain — keep scanning; resolved after the loop.
            }
            // MEMO ID-EQUALITY GUARD (SECURITY-CRITICAL): the focal grounding memo may seed this group's
            // binding ONLY when it is the SAME entity. For an entity group (binding != null), consume the
            // memo iff its resolved id equals this group's own bound canonicalId — otherwise the focal
            // client's memoized id would bind a sibling entity group (Calderon's group serving Whitman's
            // id: wrong data under a right entitlement). Null binding = single-entity path, unchanged.
            if (consumeGroundingMemo(groundedMemo, em, coverageEntity)
                    && focalMemoBindsEntity(groundedMemo, binding)) {
                return GroupCoverage.proceed(groundedMemo.resolvedId(), coverageEntity);
            }
            // No ALLOWED interpretation and no allowed focal memo → the required coverage entity is
            // unresolved. If the bag carries a coverage-entity reference, EntityResolver would self-bind
            // it VERBATIM (no CHECK) — fail-open. Distinguish a genuinely unresolved required entity
            // (CLARIFY — ask which resource, never an access denial) from a group with nothing to bind.
            boolean carriesUncheckedRef = coverageEntity != null && preExtracted != null
                    && preExtracted.reference(coverageEntity.extractAs()) != null;
            if (carriesUncheckedRef) {
                return coverageClarifyOrWithhold(em, coverageEntity, principal, tenantId, callerToken,
                        request, requestId, conversationId);
            }
            // Nothing in the bag for EntityResolver to self-bind; the group proceeds and input synthesis
            // simply yields no bound agent while the required entity is still missing (belt-and-suspenders
            // strip in injectCoverage guarantees no coverage reference survives an id-less PROCEED).
            return GroupCoverage.proceed(null, coverageEntity);
        }
        return GroupCoverage.proceed(null, null);
    }

    /**
     * The core no-leak scan filter: an entity group's coverage scan considers ONLY interpretations of its
     * OWN bound canonicalId, so a sibling client's interpretation can never decide this group's coverage.
     * A null binding (single-entity path) selects every interpretation — byte-identical to before.
     */
    static boolean bindingSelectsInterpretation(EntityBinding binding, GroundedInterpretation gi) {
        if (binding == null) return true;
        return gi.canonicalId() != null && binding.canonicalId().equals(gi.canonicalId());
    }

    /**
     * The memo id-equality guard: the focal grounding memo may seed an entity group's binding ONLY when it
     * is the SAME entity (its resolved id equals the group's own bound canonicalId). Prevents the focal
     * client's memoized id from binding a sibling entity group — the one cross-entity hole this feature
     * could open. A null binding (single-entity path) always consumes the memo — unchanged behaviour.
     */
    static boolean focalMemoBindsEntity(GroundingResult memo, EntityBinding binding) {
        if (binding == null) return true;
        return memo != null && memo.resolvedId() != null
                && memo.resolvedId().equals(binding.canonicalId());
    }

    /**
     * A resource-scoped group whose required coverage entity is unresolved (NOT_FOUND / AMBIGUOUS / no
     * matching interpretation): reuse the flat path's clarify machinery so an ENTITLED caller is asked
     * which resource ("which ⟨entity⟩?"), never told they lack access. Fails closed (WITHHOLD) when the
     * book cannot be discovered (empty / no coverage contract) or the coverage service is unavailable.
     */
    private GroupCoverage coverageClarifyOrWithhold(EffectiveManifest em, EntityType coverageEntity,
                                                    Principal principal, String tenantId, String callerToken,
                                                    ChatRequest request, String requestId, String conversationId) {
        if (em.coverage() == null) return GroupCoverage.withhold(coverageEntity);
        try {
            List<CoverageResource> discovered = coverageClient.discover(
                    principal.id(), tenantId, em.coverage(), callerToken);
            if (discovered == null || discovered.isEmpty()) {
                return GroupCoverage.withhold(coverageEntity);
            }
            return GroupCoverage.clarify(
                    buildClarificationQuestion(em, List.of(), discovered, request), coverageEntity);
        } catch (CoverageClient.CoverageUnavailableException e) {
            // Coverage service down while forming the clarify — fail closed.
            tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                    new CheckDeniedData("coverage", null, principal.id(), "coverage-unavailable", "coverage")));
            return GroupCoverage.withhold(coverageEntity);
        }
    }

    /** Injects a group's coverage-verified canonical id into the bag (mirrors the flat binder). */
    private EntityBag injectCoverage(EntityBag preExtracted, GroupCoverage cov) {
        if (preExtracted == null) return null;
        if (cov == null || cov.coverageEntity() == null) return preExtracted;
        if (cov.coverageRelId() == null) {
            // PROCEED without an ALLOWED coverage id — strip any coverage-entity reference so
            // EntityResolver cannot self-bind an unchecked id verbatim (belt-and-suspenders for the
            // fail-open hole; a group with a bindable reference is diverted to CLARIFY/WITHHOLD upstream).
            return preExtracted.withReference(cov.coverageEntity().extractAs(), null);
        }
        return preExtracted
                .withReference(cov.coverageEntity().extractAs(), cov.coverageRelId())
                .withResolvedValue(cov.coverageEntity().key(), cov.coverageRelId());
    }

    /**
     * True when a DAG group's required producer is authorization-pruned — the group must then be DENIED,
     * never silently flat-fallen-back (spec DO-NOT). Derives the producer closure intra-group (goal →
     * registry DAG → producers) and re-gates every manifest the plan touches; a prune returns true. A
     * goal that resolves to no multi-node DAG (or the flag is off) is NOT a producer prune → false, so it
     * runs as an ordinary flat group.
     */
    private boolean dagProducerPruned(RequestedPlan.RequestedGroup group, Principal principal,
                                      EntityBag preExtracted) {
        if (!dagEnabled || group.goalId() == null || preExtracted == null) return false;
        Set<String> availableEntities = entityResolver.resolve(preExtracted).resolved().keySet();
        DagResolution resolution = dagResolver.resolve(group.goalId(), registry.listAll(), availableEntities);
        if (!resolution.ok() || resolution.plan() == null || resolution.plan().nodes().size() <= 1) {
            return false;
        }
        List<AgentManifest> planManifests = resolution.plan().nodes().stream()
                .map(PlanNode::agent).collect(Collectors.toList());
        return entitlementService.filterAgents(principal, planManifests).size() < planManifests.size();
    }

    /** Records (trace only — never streamed) that one requested group was denied and is withheld. */
    private void recordGroupDenied(RequestedPlan.RequestedGroup group, String requestId,
                                   String conversationId, String userId, boolean dagProducerDenied) {
        String reason = dagProducerDenied ? "dag-producer-denied" : "no-covered-agents";
        tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                new CheckDeniedData("structural", group.goalId(), userId, reason, "cerbos")));
        log.info("Requested group {} denied ({}) — withheld from the merged answer",
                group.requestedCapabilityIds(), reason);
    }

    private void handleFollowUp(ChatRequest request, SseEmitter emitter,
                                 String latestPrompt, String conversationId,
                                 String userId,
                                 Principal jwtPrincipal,
                                 String requestId, long requestStart, Span rootSpan,
                                 EntityBag preExtracted, String streamId, String callerToken) throws Exception {
        // ── BIAS-TO-FETCH FALLTHROUGH (Piece 3: unified prepared route) ───────────
        // A follow-up whose needed data is not already in context ("what's the performance on that
        // portfolio?" when only holdings were shown) must fetch it, not answer "no info" from memory.
        // When the turn carries a grounded resolvable entity AND the conversation routes confidently,
        // serve it through the fetch pipeline; otherwise it is a genuine context-answerable follow-up
        // (explain / recap / aggregate) → synthesize from history. The probe now routes on the SAME
        // masked text + relaxation the fetch path uses (no separate unmasked probe), and the prepared
        // memo is threaded into handleFetchData so grounding/masking runs exactly once.
        if (preExtracted != null && hasGroundedResolvableReference(preExtracted, latestPrompt)) {
            Principal principal = (jwtPrincipal != null) ? jwtPrincipal : Principal.anonymous();
            String tenantId = jwtPrincipal != null ? jwtPrincipal.tenantId() : "default";
            PreparedRoute prepared = routePreparer.prepare(request, latestPrompt, preExtracted,
                    true, false, principal, tenantId, callerToken);
            ResolverResult probe = resolver.resolveContextual(
                    prepared.maskedRoutingText(), prepared.relaxationAllowed());
            if (!probe.fallback() && !probe.selected().isEmpty()) {
                log.info("FOLLOW_UP → fetch fallthrough (grounded entity + confident route) requestId={}", requestId);
                handleFetchData(request, emitter, latestPrompt, conversationId, userId, jwtPrincipal,
                        requestId, requestStart, rootSpan, preExtracted, streamId, true, callerToken, prepared);
                return;
            }
        }
        Span span = tracer.spanBuilder("chat.follow_up").startSpan();
        try {
            long synthesisStart = System.nanoTime();
            String answer = answerSynthesizer.synthesizeFromHistory(request.messages(), latestPrompt, emitter, streamId, requestStart);
            recordGatewayStage("synthesis", System.nanoTime() - synthesisStart);
            setTraceOutput(rootSpan, answer);
            emitRequestOutcome("ANSWERED");
            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
        } finally { span.end(); }
    }

    private void handleChitchat(ChatRequest request, SseEmitter emitter,
                                 String conversationId, String requestId, long requestStart, String streamId,
                                 Span rootSpan) throws Exception {
        long synthesisStart = System.nanoTime();
        String answer = answerSynthesizer.synthesizeFromHistory(request.messages(), extractLatestUserMessage(request), emitter, streamId, requestStart);
        recordGatewayStage("synthesis", System.nanoTime() - synthesisStart);
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

    /**
     * The Compare-CLARIFY question (spec §4): a manifest-sourced template with {@code {resolved}} = the
     * bound-and-covered clients' display (resolver data — discloses exactly what a SERVED answer would) and
     * {@code {unresolved}} = the user's OWN verbatim words. The gateway holds only a domain-neutral fallback.
     *
     * <p><b>SECURITY (non-negotiable).</b> {@code {unresolved}} is rendered from {@link
     * UnboundReference#verbatim} — the user's own words — NEVER from Tier-B's resolved canonical name. We do
     * not disclose that a partially/wrongly named reference resolves to a real client.
     */
    private String buildCompareClarification(EntityBindingSet bindings, List<UnboundReference> unbound,
                                             String subDomainId) {
        String template = msg("compare_partial_resolution",
                "I found {resolved}. I couldn't identify the other reference you mentioned ({unresolved}). "
                + "Please provide the name or identifier.",
                subDomainId);
        return composeComparePartial(template, bindings, unbound);
    }

    /**
     * Pure {@code {resolved}}/{@code {unresolved}} substitution — factored out so the SECURITY property
     * ({@code {unresolved}} = user verbatim, never a resolved canonical name) is unit-testable without Spring.
     * {@code {resolved}} lists the ALLOWED bindings' canonical names (falling back to the id); {@code
     * {unresolved}} lists the unbound references' user verbatims, in order, de-duplicated.
     */
    static String composeComparePartial(String template, EntityBindingSet bindings,
                                        List<UnboundReference> unbound) {
        String resolved = bindings.bindings().stream()
                .filter(EntityBinding::hasAllowed)
                .map(b -> {
                    String n = b.canonicalName();
                    return (n != null && !n.isBlank()) ? n : b.canonicalId();
                })
                .collect(Collectors.joining(", "));
        String unresolved = unbound.stream()
                .map(UnboundReference::verbatim)          // user's OWN words — never the resolved canonical name
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
        return template.replace("{resolved}", resolved).replace("{unresolved}", unresolved);
    }

    /**
     * The sub-domain to scope the Compare-CLARIFY copy to: the routed primary candidate's sub-domain, else
     * the first ALLOWED binding interpretation's sub-domain (spec §4). Manifest-driven; no domain literal.
     */
    private String clarifyScopeSubDomain(ResolverResult resolved, EntityBindingSet bindings) {
        ResolverResult.PrimaryCandidate pc = resolved != null ? resolved.primaryCandidate() : null;
        if (pc != null && pc.subDomainId() != null) return pc.subDomainId();
        for (EntityBinding b : bindings.bindings()) {
            for (GroundedInterpretation gi : b.interpretations()) {
                if (gi.isAllowed() && gi.subDomainId() != null) return gi.subDomainId();
            }
        }
        return null;
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

    // The routing-query builder, the fetch-window selector, and the typed-id check that used to live
    // here now belong to RoutePreparer (Piece 3), which owns window selection + message-local masking
    // as one pipeline shared by FETCH_DATA and the FOLLOW_UP fallthrough.

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
                    && manifestStore.compiledIdPattern(pattern).matcher(latestPrompt).find()) {
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
     * True when the Stage-1 grounding memo is a RESOLVED+allowed reference for EXACTLY the routed
     * sub-domain and its coverage entity — so the coverage pipeline may reuse the already-checked id
     * instead of re-resolving. Guarded on both sub-domain and entity key so a memo grounded in one
     * sub-domain can never bypass a different routed sub-domain's coverage check.
     */
    private boolean consumeGroundingMemo(GroundingResult memo, EffectiveManifest em, EntityType coverageEntity) {
        return memo != null
                && memo.isAllowed()
                && memo.resolvedId() != null
                && memo.subDomainId() != null
                && em != null && memo.subDomainId().equals(em.subDomainId())
                && coverageEntity != null && memo.entityType() != null
                && coverageEntity.key().equals(memo.entityType().key());
    }

    /**
     * Stage-3 abstain triage — the DETERMINISTIC required-entity CLARIFY. Routing abstained, so this
     * asks: is there an allowed, resource-scoped capability among the broad (below-floor) candidates
     * whose declared {@code required_context} the turn did NOT satisfy? If so, a vague-but-in-domain
     * ask ("show me holdings") is a missing-entity CLARIFY, not a "no service" — so we DISCOVER the
     * caller's book and pose the manifest-declared clarification with in-book options.
     *
     * <p>Order preserved (candidates are score-descending); floor is the reused
     * {@code conduit.resolver.confidence-floor}; the CLARIFY decision stays the same Java
     * set-intersection ({@code required ∩ extracted = ∅}) decided here, never LLM-judged. FAIL-CLOSED:
     * candidates pass through {@link EntitlementService#filterAgents} (Cerbos) first, and a coverage
     * outage while discovering the book abandons the clarify (→ clean no-service) rather than guessing.
     *
     * @return true if a clarification (or empty-book denial) was streamed and the request is complete
     */
    private boolean attemptRequiredEntityClarify(ResolverResult resolved, EntityBag preExtracted,
                                                 Principal principal, String tenantId, String callerToken,
                                                 SseEmitter emitter, String streamId, String requestId,
                                                 String conversationId, long requestStart,
                                                 ChatRequest request) throws Exception {
        if (resolved == null || resolved.skipped() == null || resolved.skipped().isEmpty()) return false;
        List<AgentManifest> candidateManifests = resolved.skipped().stream()
                .filter(c -> c.score() >= confidenceFloor)
                .map(c -> c.manifest())
                .collect(Collectors.toList());
        if (candidateManifests.isEmpty()) return false;

        // FAIL-CLOSED: only ever clarify over capabilities the principal may actually invoke.
        List<AgentManifest> allowed = entitlementService.filterAgents(principal, candidateManifests);
        for (AgentManifest m : allowed) {
            if (!entitlementService.requiresCoverage(m)) continue;
            EffectiveManifest em = manifestStore.getEffective(m.agentId(), m.domain(), m.subDomain());
            if (em == null || !em.resourceScoped() || !em.requiresContext()) continue;
            if (!requiredIntersectionEmpty(em, preExtracted)) continue;   // entity WAS provided → not a clarify
            DomainManifest.Coverage coverage = em.coverage();
            if (coverage == null) continue;
            try {
                List<CoverageResource> discovered =
                        coverageClient.discover(principal.id(), tenantId, coverage, callerToken);
                if (discovered.isEmpty()) {
                    emitRequestOutcome("DENIED");
                    tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                            new CheckDeniedData("coverage", null, principal.id(), "empty-coverage", "coverage")));
                    streamTextAndComplete(emitter, msg("no_coverage",
                            "Your coverage set is empty.", em.subDomainId()), streamId);
                    tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                            new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                    return true;
                }
                String question = buildClarificationQuestion(em, List.of(), discovered, request);
                emitRequestOutcome("CLARIFIED");
                streamTextAndComplete(emitter, question, streamId);
                tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                        new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                return true;
            } catch (CoverageClient.CoverageUnavailableException e) {
                // Fail closed: cannot build a grounded clarify → let the caller emit clean no-service.
                log.warn("Abstain-triage clarify: coverage discover unavailable for principal={}: {}",
                        principal.id(), e.getMessage());
                return false;
            }
        }
        return false;
    }

    /**
     * The deterministic CLARIFY predicate ({@code required ∩ extracted = ∅}) for one effective
     * manifest: true when NONE of the sub-domain's declared required entity keys is satisfied by the
     * extracted bag (a reference under its {@code extract_as}, or an already-resolved value under its
     * {@code key}). Pure Java set-intersection over manifest data — never an LLM judgment.
     */
    private boolean requiredIntersectionEmpty(EffectiveManifest em, EntityBag bag) {
        List<String> required = em.requiredContext();
        if (required == null || required.isEmpty()) return false;   // nothing required → not a missing-entity case
        List<EntityType> ets = manifestStore.entityTypesFor(em.subDomainId());
        for (String key : required) {
            EntityType et = ets.stream().filter(e -> key.equals(e.key())).findFirst().orElse(null);
            if (et == null) continue;
            boolean satisfied = bag != null
                    && ((et.extractAs() != null && bag.reference(et.extractAs()) != null)
                        || bag.resolved(et.key()) != null);
            if (satisfied) return false;   // at least one required entity present → intersection non-empty
        }
        return true;   // no required entity present → empty intersection → clarify
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
                        || manifestStore.compiledIdPattern(et.idPattern()).matcher(v.toUpperCase()).matches())
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
        GatewaySloMetrics.RequestScope scope = gatewayMetricScope.get();
        if (scope != null) {
            scope.outcome(outcome);
        }
        Counter.builder("conduit.request.outcome")
                .description("Request resolution outcome")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private void markGatewayPath(String path) {
        GatewaySloMetrics.RequestScope scope = gatewayMetricScope.get();
        if (scope != null) {
            scope.path(path);
        }
    }

    private void markGatewayDomain(String domain) {
        GatewaySloMetrics.RequestScope scope = gatewayMetricScope.get();
        if (scope != null && domain != null && !domain.isBlank()) {
            scope.domain(domain);
        }
    }

    private void recordGatewayStage(String stage, long elapsedNanos) {
        GatewaySloMetrics.RequestScope scope = gatewayMetricScope.get();
        String path = scope == null ? "unknown" : scope.path();
        String domain = scope == null ? "unknown" : scope.domain();
        gatewaySloMetrics.recordStage(stage, path, domain, elapsedNanos);
    }

    private static String domainsOf(List<AgentManifest> manifests) {
        if (manifests == null || manifests.isEmpty()) {
            return "unknown";
        }
        return manifests.stream()
                .map(AgentManifest::domain)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * Domains to label "withheld" (outside the caller's access) in the synthesized answer.
     *
     * <p>A domain is withheld ONLY when none of its selected agents survived the structural gate.
     * If any agent in the domain is still allowed — e.g. {@code settlement_status} (confidential)
     * survives while the higher-classification {@code settlement_risk} (confidential-pii) is pruned —
     * the domain IS served, so it must NOT be labeled "outside your access"; doing so contradicts the
     * data returned for it (bug-260). Withheld = referenced domains MINUS served domains.
     */
    static List<String> computeWithheldDomains(List<AgentManifest> selected, Set<String> servedDomains) {
        return selected.stream()
                .map(AgentManifest::domain)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .filter(d -> !servedDomains.contains(d))
                .collect(Collectors.toList());
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

    /**
     * Counts requests where agents were dispatched and none succeeded. The user still receives
     * honest prose stating the data was unavailable, but zero domain data was returned — so this
     * must never be counted as an answered request. This is the metric an operator alerts on when
     * an agent's circuit breaker opens under load.
     */
    private void emitRequestNoData() {
        Counter.builder("conduit.request.no_data")
                .description("Requests where every dispatched agent failed (no domain data returned)")
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
