package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.api.v1.chat.dto.ChatRequest;
import ai.conduit.gateway.api.v1.chat.dto.Message;
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
import ai.conduit.gateway.infrastructure.telemetry.event.IntentClassifiedData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestCompleteData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData;
import ai.conduit.gateway.infrastructure.telemetry.event.SynthesisStartData;
import ai.conduit.gateway.orchestration.executor.FlatPlanExecutor;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import ai.conduit.gateway.synthesis.answer.AnswerSynthesizer;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.InputSynthesizer;
import ai.conduit.gateway.synthesis.input.InputSynthesizer.SynthesisResult;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    static final String MODEL_ID = "conduit-assistant";

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
                       AgentResolver resolver,
                       InputSynthesizer inputSynthesizer,
                       FlatPlanExecutor executor,
                       AnswerSynthesizer answerSynthesizer,
                       EntitlementService entitlementService,
                       TraceEventPublisher tracePublisher,
                       CoverageClient coverageClient,
                       DomainManifestStore manifestStore,
                       Tracer tracer,
                       MeterRegistry meterRegistry) {
        this.mapper              = mapper;
        this.intentClassifier    = intentClassifier;
        this.resolver            = resolver;
        this.inputSynthesizer    = inputSynthesizer;
        this.executor            = executor;
        this.answerSynthesizer   = answerSynthesizer;
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
     */
    public void handleChat(ChatRequest request, SseEmitter emitter, String userId,
                           Principal jwtPrincipal, String headerConvId) {
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
                        intentResult.extractedEntities(), streamId);
                case FOLLOW_UP  -> handleFollowUp(request, emitter, latestPrompt,
                        conversationId, userId, jwtPrincipal, requestId, requestStart, rootSpan, streamId);
                // CLARIFY is routed through the SAME deterministic coverage path as FETCH_DATA
                // (hard-rule e): the LLM never decides clarification. The coverage
                // discover/intersect/`required ∩ resolved = ∅` check inside handleFetchData
                // produces the proper numbered clarification from the RM's book — not a bare,
                // LLM-judged manifest message.
                case CLARIFY    -> handleFetchData(request, emitter, latestPrompt,
                        conversationId, userId, jwtPrincipal, requestId, requestStart, rootSpan,
                        intentResult.extractedEntities(), streamId);
                case CHITCHAT   -> handleChitchat(request, emitter, conversationId, requestId, requestStart, streamId);
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
                                  EntityBag preExtracted, String streamId) throws Exception {
        Span span = tracer.spanBuilder("chat.fetch_data").startSpan();
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
            ResolverResult resolved = resolver.resolve(latestPrompt);

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

            if (resolved.fallback() || resolved.selected().isEmpty()) {
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

            // ── Domain + agent tags on the root span ──────────────────────────────
            // First-class Langfuse filter chips (domain:*, agent:*) plus cost-by-domain
            // metadata. All values come from manifest.domain()/agentId() — never hardcoded
            // (WORLD-B §5). These tags drive observability slicing AND eval-suite resolution
            // (see docs/EVAL-FRAMEWORK.md — the worker picks a domain/agent's suite by tag).
            if (!finalManifests.isEmpty()) {
                List<String> domains = finalManifests.stream()
                        .map(AgentManifest::domain)
                        .filter(d -> d != null && !d.isBlank())
                        .distinct()
                        .toList();
                String resolvedDomain = String.join(",", domains);
                if (!resolvedDomain.isBlank()) {
                    rootSpan.setAttribute("langfuse.metadata.domain", resolvedDomain);
                    rootSpan.setAttribute("conduit.domain", resolvedDomain);
                }
                // First-class tags: segment(s) + one per distinct domain + one per invoked
                // agent. Seeding with segmentTags() preserves the segment:* slicing chips set
                // in handleChat (setAttribute REPLACES, so they must be re-included here).
                List<String> tags = new ArrayList<>(segmentTags(jwtPrincipal));
                domains.forEach(d -> tags.add("domain:" + d));
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
                                coverageEntity.resolveType(), tenantId, coverage);

                            if (resolveResult.resolved()) {
                                coverageRelId = resolveResult.id();
                                CoverageCheckResult check = coverageClient.check(
                                    principalId, tenantId, coverageRelId, coverage);
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
                            // No user-authored reference in the sent conversation. Deterministic
                            // CLARIFY: do not guess or auto-select a covered resource.
                            List<CoverageResource> discovered = coverageClient.discover(
                                principalId, tenantId, coverage);

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

                            String question = buildClarificationQuestion(em, List.of(), discovered);
                            emitRequestOutcome("CLARIFIED");
                            streamTextAndComplete(emitter, question, streamId);
                            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
                            return;
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
            SynthesisResult synthesis;
            if (preExtracted != null) {
                EntityBag effectiveBag = preExtracted;
                if (coverageEntity != null) {
                    String covExtractAs = coverageEntity.extractAs();
                    if (coverageRelId != null) {
                        // Inject the coverage-verified canonical ID as the entity reference so
                        // the resolver short-circuits via its id_pattern (no external lookup).
                        effectiveBag = preExtracted.withReference(covExtractAs, coverageRelId);
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

            List<PlanNode> nodes = synthesis.inputs().entrySet().stream()
                    .map(e -> {
                        AgentManifest m = finalManifests.stream()
                                .filter(a -> a.agentId().equals(e.getKey())).findFirst().orElseThrow();
                        return new PlanNode(e.getKey(), m, e.getValue(), List.of());
                    }).collect(Collectors.toList());

            nodes.forEach(n -> tracePublisher.publish(TraceEvent.of("agent_start", requestId, conversationId,
                    new AgentStartData(n.nodeId(), n.agent().protocol()))));

            long fanoutStart = System.currentTimeMillis();
            List<NodeResult> results = executor.execute(new Plan(nodes));
            long fanoutElapsedMs = System.currentTimeMillis() - fanoutStart;
            Timer.builder("conduit.fanout.duration")
                    .description("Time from routing decision to all agents completing")
                    .tag("agent_count", String.valueOf(nodes.size()))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(fanoutElapsedMs, TimeUnit.MILLISECONDS);

            results.forEach(r -> {
                String prev = r.isOk() && r.data() != null
                        ? r.data().toString().substring(0, Math.min(80, r.data().toString().length())) : r.status().name();
                tracePublisher.publish(TraceEvent.of("agent_complete", requestId, conversationId,
                        new AgentCompleteData(r.agentId(), r.latencyMs(), r.isOk() ? "ok" : "failed", prev)));
            });

            long okCount = results.stream().filter(NodeResult::isOk).count();
            log.info("Fan-out complete {}/{} ok", okCount, results.size());

            tracePublisher.publish(TraceEvent.of("synthesis_start", requestId, conversationId,
                    new SynthesisStartData(results.size(), (int) okCount)));

            answerSynthesizer.synthesize(results, latestPrompt, request.messages(), emitter, streamId);
            emitRequestOutcome("ANSWERED");

            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart,
                            results.size(), (int) okCount)));
        } finally { span.end(); }
    }

    private void handleFollowUp(ChatRequest request, SseEmitter emitter,
                                 String latestPrompt, String conversationId,
                                 String userId,
                                 Principal jwtPrincipal,
                                 String requestId, long requestStart, Span rootSpan,
                                 String streamId) throws Exception {
        Span span = tracer.spanBuilder("chat.follow_up").startSpan();
        try {
            answerSynthesizer.synthesizeFromHistory(request.messages(), latestPrompt, emitter, streamId);
            emitRequestOutcome("ANSWERED");
            tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                    new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
        } finally { span.end(); }
    }

    private void handleChitchat(ChatRequest request, SseEmitter emitter,
                                 String conversationId, String requestId, long requestStart, String streamId) throws Exception {
        answerSynthesizer.synthesizeFromHistory(request.messages(), extractLatestUserMessage(request), emitter, streamId);
        emitRequestOutcome("ANSWERED");
        tracePublisher.publish(TraceEvent.of("request_complete", requestId, conversationId,
                new RequestCompleteData(System.currentTimeMillis() - requestStart, 0, 0)));
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
     * Builds a clarification question presenting the RM with a numbered list of
     * client relationships to choose from.
     *
     * @param em         the effective manifest (provides the question template from clarification schema)
     * @param candidates disambiguation candidates already narrowed to the RM's book (may be empty
     *                   — falls back to the full discovered list)
     * @param discovered full discovered list for this RM
     */
    private String buildClarificationQuestion(EffectiveManifest em,
                                               List<CoverageResolveResult.ResolveCandidate> candidates,
                                               List<CoverageResource> discovered) {
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
