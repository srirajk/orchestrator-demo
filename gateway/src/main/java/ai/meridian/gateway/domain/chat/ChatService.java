package ai.meridian.gateway.domain.chat;

import ai.meridian.gateway.api.v1.chat.dto.ChatRequest;
import ai.meridian.gateway.api.v1.chat.dto.Message;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the full request pipeline:
 *   Resolver → InputSynthesizer → FlatPlanExecutor → AnswerSynthesizer
 *
 * Also handles the two short-circuit paths:
 *   - LibreChat auto-title requests (suppressed)
 *   - Resolver fallback / clarification (asks user to rephrase)
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String MODEL_ID = "meridian-assistant";

    private static final List<String> TITLE_TRIGGERS = List.of(
            "generate a concise",
            "generate a short",
            "provide a title",
            "name this conversation",
            "title for this"
    );

    private final ObjectMapper      mapper;
    private final AgentResolver     resolver;
    private final InputSynthesizer  inputSynthesizer;
    private final FlatPlanExecutor  executor;
    private final AnswerSynthesizer answerSynthesizer;

    public ChatService(ObjectMapper mapper,
                       AgentResolver resolver,
                       InputSynthesizer inputSynthesizer,
                       FlatPlanExecutor executor,
                       AnswerSynthesizer answerSynthesizer) {
        this.mapper            = mapper;
        this.resolver          = resolver;
        this.inputSynthesizer  = inputSynthesizer;
        this.executor          = executor;
        this.answerSynthesizer = answerSynthesizer;
    }

    // ── LibreChat title short-circuit ─────────────────────────────────────────

    public boolean isTitleRequest(ChatRequest request) {
        if (request.messages() == null) return false;
        return request.messages().stream()
                .filter(m -> m.content() != null)
                .map(m -> m.content().toLowerCase())
                .anyMatch(c -> TITLE_TRIGGERS.stream().anyMatch(c::contains));
    }

    public void streamTitle(SseEmitter emitter) {
        String id = newId();
        long ts = epochSeconds();
        try {
            emitter.send(SseEmitter.event().data(roleDelta(id, ts)));
            emitter.send(SseEmitter.event().data(contentDelta(id, ts, "Meridian AI")));
            emitter.send(SseEmitter.event().data(stopDelta(id, ts)));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            log.warn("Title stream interrupted", e);
            emitter.completeWithError(e);
        }
    }

    // ── Main pipeline ─────────────────────────────────────────────────────────

    public void handleChat(ChatRequest request, SseEmitter emitter) {
        String prompt = extractLatestUserMessage(request);
        log.info("handleChat prompt='{}'", prompt.length() > 120 ? prompt.substring(0, 120) + "…" : prompt);

        try {
            // Stage 1 — Resolve which agents to call
            ResolverResult resolved = resolver.resolve(prompt);

            if (resolved.fallback() || resolved.selected().isEmpty()) {
                log.warn("Resolver fallback — no agents matched, returning clarification");
                streamText(emitter, "I wasn't sure which services to consult for that question. " +
                        "Could you rephrase it? For example, try mentioning a specific relationship name, " +
                        "fund, or what type of data you need (holdings, performance, settlements, etc.).");
                return;
            }

            List<AgentManifest> selectedManifests = resolved.selected().stream()
                    .map(c -> c.manifest())
                    .collect(Collectors.toList());

            log.info("Resolver selected {} agents: {}", selectedManifests.size(),
                    selectedManifests.stream().map(AgentManifest::agentId).collect(Collectors.joining(", ")));

            // Stage 2 — Synthesize per-agent inputs (Extract→Resolve→Bind)
            SynthesisResult synthesis = inputSynthesizer.synthesize(prompt, selectedManifests);

            if (synthesis.needsClarification()) {
                log.info("Input synthesis needs clarification: {}", synthesis.clarificationMessage());
                streamText(emitter, synthesis.clarificationMessage());
                return;
            }

            if (synthesis.inputs().isEmpty()) {
                log.warn("Input synthesis produced no bindable inputs — all agents dropped");
                streamText(emitter,
                        "I couldn't determine the specific relationship or fund you're asking about. " +
                        "Please include the client name (e.g. 'Whitman Family Office') or relationship ID.");
                return;
            }

            if (!synthesis.droppedAgents().isEmpty()) {
                log.info("Dropped {} agents due to missing required fields: {}",
                        synthesis.droppedAgents().size(), synthesis.droppedAgents());
            }

            // Stage 3 — Build Plan and execute (parallel fan-out with Resilience4j harness)
            List<PlanNode> nodes = synthesis.inputs().entrySet().stream()
                    .map(e -> {
                        AgentManifest m = selectedManifests.stream()
                                .filter(a -> a.agentId().equals(e.getKey()))
                                .findFirst()
                                .orElseThrow();
                        return new PlanNode(e.getKey(), m, e.getValue(), List.of());
                    })
                    .collect(Collectors.toList());

            Plan plan = new Plan(nodes);
            log.info("Executing plan with {} nodes", nodes.size());

            List<NodeResult> results = executor.execute(plan);

            long okCount = results.stream().filter(NodeResult::isOk).count();
            log.info("Fan-out complete: {}/{} agents succeeded", okCount, results.size());

            // Stage 4 — Stream the synthesized answer from Z.AI GLM
            answerSynthesizer.synthesize(results, prompt, emitter);

        } catch (Exception e) {
            log.error("Chat pipeline failed", e);
            try {
                streamText(emitter, "An internal error occurred processing your request. Please try again.");
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractLatestUserMessage(ChatRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) return "";
        // Take the last user message
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Message m = request.messages().get(i);
            if ("user".equals(m.role()) && m.content() != null) {
                return m.content();
            }
        }
        return request.messages().get(request.messages().size() - 1).content();
    }

    private void streamText(SseEmitter emitter, String text) throws Exception {
        String id = newId();
        long ts = epochSeconds();
        emitter.send(SseEmitter.event().data(roleDelta(id, ts)));
        for (String word : text.split("(?<=\\s)|(?=\\s)")) {
            emitter.send(SseEmitter.event().data(contentDelta(id, ts, word)));
        }
        emitter.send(SseEmitter.event().data(stopDelta(id, ts)));
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
    }

    // ── SSE chunk builders ────────────────────────────────────────────────────

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
    private interface DeltaWriter {
        void write(ObjectNode delta) throws Exception;
    }

    private String chunk(String id, long ts, DeltaWriter w, String finishReason) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", ts);
        root.put("model", MODEL_ID);

        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);

        ObjectNode delta = choice.putObject("delta");
        w.write(delta);

        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        return mapper.writeValueAsString(root);
    }

    private static String newId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static long epochSeconds() {
        return System.currentTimeMillis() / 1_000L;
    }
}
