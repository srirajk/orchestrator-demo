package ai.meridian.gateway.domain.chat;

import ai.meridian.gateway.api.v1.chat.dto.ChatRequest;
import ai.meridian.gateway.api.v1.chat.dto.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * Phase-1 stub: streams a hardcoded placeholder response.
 * In later phases this delegates to the Resolver → Executor → Synthesizer pipeline.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String MODEL_ID = "meridian-assistant";

    private static final String PLACEHOLDER =
            "Hello! I am the **Meridian AI Gateway** — your enterprise AI assistant for the bank. " +
            "The routing engine is initialising. Full multi-agent orchestration is coming in Phase 2.";

    private static final List<String> TITLE_TRIGGERS = List.of(
            "generate a concise",
            "generate a short",
            "provide a title",
            "name this conversation",
            "title for this"
    );

    private final ObjectMapper mapper;

    public ChatService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * True when LibreChat sends its automatic "name this conversation" request.
     * We short-circuit it so it never reaches the agent routing pipeline.
     */
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

    public void streamPlaceholderResponse(SseEmitter emitter) {
        String id = newId();
        long ts = epochSeconds();
        try {
            emitter.send(SseEmitter.event().data(roleDelta(id, ts)));

            // word-by-word stream to exercise the SSE chunking path
            for (String word : PLACEHOLDER.split("(?<=\\s)|(?=\\s)")) {
                emitter.send(SseEmitter.event().data(contentDelta(id, ts, word)));
                Thread.sleep(30);
            }

            emitter.send(SseEmitter.event().data(stopDelta(id, ts)));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(ie);
        } catch (Exception e) {
            log.warn("Chat stream interrupted", e);
            emitter.completeWithError(e);
        }
    }

    // ── chunk builders ────────────────────────────────────────────────────────

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
        void write(ObjectNode delta);
    }

    private String chunk(String id, long ts, DeltaWriter deltaWriter, String finishReason) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", ts);
        root.put("model", MODEL_ID);

        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);

        ObjectNode delta = choice.putObject("delta");
        deltaWriter.write(delta);

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
