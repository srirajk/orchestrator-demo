package ai.conduit.gateway.api.v1.chat;

import ai.conduit.gateway.api.v1.chat.dto.ChatRequest;
import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.RequestContext;
import ai.conduit.gateway.domain.chat.ChatService;
import ai.conduit.gateway.infrastructure.identity.IdentityExtractor;
import ai.conduit.gateway.infrastructure.telemetry.MdcPropagation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/v1")
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ChatService chatService;
    private final IdentityExtractor identityExtractor;
    private final ObjectMapper mapper;

    // Application-scoped virtual-thread executor for offloading the chat pipeline.
    // CLAUDE.md §3 mandates virtual threads ON — offloading via CompletableFuture without
    // an explicit executor would run on ForkJoinPool.commonPool() (a small fixed pool of
    // platform threads), starving under concurrent long-lived agent calls. Each pipeline
    // run gets its own cheap virtual thread instead.
    private final ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatCompletionsController(ChatService chatService, IdentityExtractor identityExtractor,
                                     ObjectMapper mapper) {
        this.chatService = chatService;
        this.identityExtractor = identityExtractor;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        pipelineExecutor.shutdown();
    }

    /**
     * Reads the caller's verified bearer token off {@link SecurityContextHolder} — MUST be called
     * on the servlet thread, before offloading to {@link #pipelineExecutor}. {@code
     * SecurityContextHolder} is thread-local; Spring Security's {@code BearerTokenAuthenticationFilter}
     * populates it upstream of this controller, but nothing propagates it onto the virtual threads
     * the pipeline (and the DAG executor further downstream) run on. Returns null for an
     * unauthenticated/anonymous caller — agent invocation for such a caller fails closed downstream
     * (HttpAdapter/McpAdapter refuse to call an agent without a token; mock agents fail closed too).
     */
    private String extractBearerToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }
        return null;
    }

    @PostMapping(value = "/chat/completions")
    public Object chatCompletions(
            @RequestBody ChatRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String userId = identityExtractor.extractUserId(httpRequest);
        Principal principal = RequestContext.getPrincipal(); // null if no JWT
        // F-IDENTITY: capture the caller's verified bearer token HERE, on the servlet thread,
        // as request-scoped DATA — SecurityContextHolder is thread-local and is NOT propagated
        // to the pipelineExecutor's virtual threads below (nor to DagPlanExecutor's further down
        // the call chain). Threaded explicitly through ChatService → FlatPlanExecutor/
        // DagPlanExecutor → AgentHarness → HttpAdapter/McpAdapter so every agent hop carries it.
        String callerToken = extractBearerToken();
        // Same reasoning as callerToken above: capture the servlet thread's MDC (requestId/
        // conversationId/userId) so it can be re-applied on the pipelineExecutor virtual thread and
        // threaded onward — otherwise every downstream log line, including the agent-hop identity
        // log, prints an empty [rid= cid= uid=].
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        Context otelContext = Context.current();
        String conversationId = httpRequest.getHeader("X-Conversation-Id");

        // OpenAI spec: `stream` omitted or false → a single chat.completion JSON object;
        // true → SSE stream of chat.completion.chunk events. LibreChat sends stream:true.
        boolean stream = Boolean.TRUE.equals(request.stream());

        log.debug("Chat completions: model={}, messages={}, stream={}, userId={}, conversationId={}",
                request.model(),
                request.messages() != null ? request.messages().size() : 0,
                stream, userId, conversationId);

        if (!stream) {
            return nonStreaming(request, userId, principal, conversationId, callerToken, mdcContext);
        }

        // ── Streaming path (unchanged) ────────────────────────────────────────────
        // Force SSE content type regardless of Accept header (LibreChat agents client
        // may send Accept: application/json which Spring would 406 with produces constraint)
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(120_000L);
        if (chatService.isTitleRequest(request)) {
            log.debug("Detected auto-title request — short-circuiting");
            CompletableFuture.runAsync(() -> {
                try (Scope ignored = otelContext.makeCurrent()) {
                    chatService.streamTitle(emitter);
                }
            }, pipelineExecutor);
        } else {
            CompletableFuture.runAsync(() -> {
                try (Scope ignored = otelContext.makeCurrent()) {
                    MdcPropagation.run(mdcContext, () ->
                            chatService.handleChat(request, emitter, userId, principal, conversationId, callerToken));
                }
            }, pipelineExecutor);
        }
        return emitter;
    }

    /**
     * Non-streaming ({@code stream:false}) — runs the same pipeline into a buffering emitter,
     * then assembles the OpenAI single-object response:
     * {@code {object:"chat.completion", choices:[{message:{...}, finish_reason}], usage}}.
     */
    private ResponseEntity<String> nonStreaming(ChatRequest request, String userId,
                                                Principal principal, String conversationId, String callerToken,
                                                Map<String, String> mdcContext) {
        BufferingSseEmitter buf = new BufferingSseEmitter();
        Context otelContext = Context.current();
        if (chatService.isTitleRequest(request)) {
            CompletableFuture.runAsync(() -> {
                try (Scope ignored = otelContext.makeCurrent()) {
                    chatService.streamTitle(buf);
                }
            }, pipelineExecutor);
        } else {
            CompletableFuture.runAsync(() -> {
                try (Scope ignored = otelContext.makeCurrent()) {
                    MdcPropagation.run(mdcContext, () ->
                            chatService.handleChat(request, buf, userId, principal, conversationId, callerToken));
                }
            }, pipelineExecutor);
        }
        boolean finished = buf.await(150_000L);
        if (!finished) log.warn("Non-streaming request did not complete within 150s — returning partial");

        String id = "chatcmpl-unknown";
        long created = System.currentTimeMillis() / 1000L;
        String model = request.model() != null ? request.model() : "conduit-assistant";
        StringBuilder content = new StringBuilder();
        String finishReason = "stop";
        boolean gotMeta = false;
        for (String payload : buf.payloads) {
            if ("[DONE]".equals(payload)) continue;
            try {
                JsonNode chunk = mapper.readTree(payload);
                if (!gotMeta) {
                    id = chunk.path("id").asText(id);
                    created = chunk.path("created").asLong(created);
                    model = chunk.path("model").asText(model);
                    gotMeta = true;
                }
                JsonNode delta = chunk.path("choices").path(0).path("delta");
                if (delta.has("content")) content.append(delta.path("content").asText());
                JsonNode fr = chunk.path("choices").path(0).path("finish_reason");
                if (fr.isTextual()) finishReason = fr.asText();
            } catch (Exception ex) {
                log.debug("Non-streaming: skipped unparseable chunk: {}", ex.getMessage());
            }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion");
        root.put("created", created);
        root.put("model", model);
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content.toString());
        choice.put("finish_reason", finishReason);
        // usage: token counts aren't threaded to the controller; emit zeros so the field exists
        // (OpenAI-compatible SDKs expect a usage object on non-streaming responses).
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", 0);
        usage.put("total_tokens", 0);

        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(root));
        } catch (Exception e) {
            log.error("Failed to serialize non-streaming response", e);
            return ResponseEntity.internalServerError().body("{\"error\":\"serialization_failed\"}");
        }
    }

    /**
     * An {@link SseEmitter} that captures the chunk payloads instead of writing them to an HTTP
     * response — used for the non-streaming path so we can reuse the exact streaming pipeline
     * (routing → entitlement → synthesis) and then assemble a single JSON object.
     */
    static final class BufferingSseEmitter extends SseEmitter {
        final List<String> payloads = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch = new CountDownLatch(1);

        BufferingSseEmitter() { super(150_000L); }

        @Override
        public void send(SseEventBuilder builder) {
            for (DataWithMediaType entry : builder.build()) {
                String s = String.valueOf(entry.getData()).strip();
                if (!s.isEmpty() && !"data:".equals(s)) payloads.add(s);
            }
        }

        @Override public void complete() { latch.countDown(); }
        @Override public void completeWithError(Throwable ex) { latch.countDown(); }

        boolean await(long ms) {
            try { return latch.await(ms, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }
}
