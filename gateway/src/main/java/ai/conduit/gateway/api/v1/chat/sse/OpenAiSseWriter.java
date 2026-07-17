package ai.conduit.gateway.api.v1.chat.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Byte-exact OpenAI chat-completions SSE chunk builder. The single source of the {@code data:} payload
 * shape (role delta, content deltas, {@code stop} finish-reason, {@code [DONE]}) so every emitter — the
 * chat pipeline and the structured-clarification dual-plane — produces identical bytes and cannot drift.
 *
 * <p>Each returned string is the exact {@code data} value (with the leading space Spring omits) for one
 * SSE event. A CLARIFY is a NORMAL completion: it ends with {@code finish_reason:"stop"}, never
 * {@code tool_calls} — the structured form rides a separate out-of-band lane, not a forged tool-call.
 */
public final class OpenAiSseWriter {

    private OpenAiSseWriter() {}

    /** Terminal sentinel — OpenAI's stream end. */
    public static final String DONE = " [DONE]";

    /** The role delta ({@code delta.role="assistant"}, null finish_reason). */
    public static String roleDelta(ObjectMapper mapper, String modelId, String id, long ts) {
        return chunk(mapper, modelId, id, ts, delta -> delta.put("role", "assistant"), null);
    }

    /** One content delta ({@code delta.content=<token>}, null finish_reason). */
    public static String contentDelta(ObjectMapper mapper, String modelId, String id, long ts, String content) {
        return chunk(mapper, modelId, id, ts, delta -> delta.put("content", content), null);
    }

    /** The stop delta (empty delta, {@code finish_reason:"stop"}). */
    public static String stopDelta(ObjectMapper mapper, String modelId, String id, long ts) {
        return chunk(mapper, modelId, id, ts, delta -> {}, "stop");
    }

    /**
     * The full ordered frame list for a plain-text answer: role delta, one content delta per
     * whitespace-preserving token, stop delta, {@code [DONE]}. Matches the chat pipeline's streaming
     * exactly so a clarification streamed through here is byte-identical to today's path.
     */
    public static List<String> textFrames(ObjectMapper mapper, String modelId, String id, long ts, String text) {
        List<String> frames = new ArrayList<>();
        frames.add(roleDelta(mapper, modelId, id, ts));
        for (String token : text.split("(?<=\\s)|(?=\\s)")) {
            frames.add(contentDelta(mapper, modelId, id, ts, token));
        }
        frames.add(stopDelta(mapper, modelId, id, ts));
        frames.add(DONE);
        return frames;
    }

    private static String chunk(ObjectMapper mapper, String modelId, String id, long ts,
                                Consumer<ObjectNode> deltaWriter, String finishReason) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("id", id);
            root.put("object", "chat.completion.chunk");
            root.put("created", ts);
            root.put("model", modelId);
            ArrayNode choices = root.putArray("choices");
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);
            ObjectNode delta = choice.putObject("delta");
            deltaWriter.accept(delta);
            if (finishReason != null) choice.put("finish_reason", finishReason);
            else choice.putNull("finish_reason");
            // Leading space → OpenAI-exact "data: {json}" (Spring omits the space).
            return " " + mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build SSE chunk", e);
        }
    }
}
