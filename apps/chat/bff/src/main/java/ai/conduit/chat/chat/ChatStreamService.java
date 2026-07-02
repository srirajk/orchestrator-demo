package ai.conduit.chat.chat;

import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.conversation.ConversationService;
import ai.conduit.chat.message.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

/**
 * Pipes the gateway's SSE stream through to the client byte-for-byte while
 * accumulating the assistant's content for persistence.
 *
 * <p>SSE handling matches the OpenAI shape: lines of {@code data: {json}} whose
 * {@code choices[0].delta.content} deltas are concatenated, terminated by
 * {@code data: [DONE]}. Raw bytes are forwarded verbatim (byte-exact passthrough);
 * only a parallel line-parse is used to accumulate the text. Line splitting is done
 * on the {@code \n} byte (0x0A never occurs inside a UTF-8 multibyte sequence), so
 * multibyte characters split across network chunks are never corrupted.
 *
 * <p>Runs on a virtual thread (the caller wraps it in a {@code StreamingResponseBody},
 * which Spring executes on the virtual-thread async executor).
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private static final String DATA_PREFIX = "data: ";
    private static final String DONE_TOKEN = "[DONE]";
    private static final int CHUNK_SIZE = 8192;

    private final MessageService messageService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final ExecutorService backgroundExecutor;

    public ChatStreamService(MessageService messageService,
                             ConversationService conversationService,
                             ObjectMapper objectMapper,
                             ExecutorService backgroundExecutor) {
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.backgroundExecutor = backgroundExecutor;
    }

    /**
     * Consumes {@code stream}, forwarding bytes to {@code out} and accumulating the
     * assistant reply, then persists the assistant message and updates the conversation.
     *
     * @param stream       open gateway response (owned/closed here).
     * @param out          client response body.
     * @param conversation the (owned) conversation, loaded before streaming.
     * @param userId       owner id.
     * @param userMessage  the user's message this turn (used for auto-title).
     */
    public void pipe(GatewayStream stream,
                     OutputStream out,
                     Conversation conversation,
                     String userId,
                     String userMessage) {
        StringBuilder assistant = new StringBuilder();
        boolean clientGone = false;

        try (stream; InputStream in = stream.body()) {
            ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (!clientGone) {
                    try {
                        out.write(buffer, 0, read);
                        out.flush();
                    } catch (IOException clientClosed) {
                        // Client aborted (e.g. pressed stop). Stop forwarding but keep draining
                        // the gateway so we can persist whatever was produced.
                        clientGone = true;
                        log.debug("[stream] client disconnected mid-stream: {}", clientClosed.getMessage());
                    }
                }
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    if (b == '\n') {
                        accumulate(lineBuffer.toString(StandardCharsets.UTF_8), assistant);
                        lineBuffer.reset();
                    } else {
                        lineBuffer.write(b);
                    }
                }
            }
            if (lineBuffer.size() > 0) {
                accumulate(lineBuffer.toString(StandardCharsets.UTF_8), assistant);
            }
        } catch (IOException ex) {
            // Error reading from the gateway. The client may already have partial content;
            // persist what we have below rather than losing the turn.
            log.warn("[stream] error reading gateway stream: {}", ex.getMessage());
        }

        persist(conversation, userId, assistant.toString(), userMessage);
    }

    private void accumulate(String rawLine, StringBuilder assistant) {
        String line = stripTrailingCr(rawLine);
        if (!line.startsWith(DATA_PREFIX)) {
            return;
        }
        String data = line.substring(DATA_PREFIX.length());
        if (DONE_TOKEN.equals(data)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            if (content.isTextual()) {
                assistant.append(content.asText());
            }
        } catch (Exception ignored) {
            // Malformed/partial SSE chunk — ignore and keep streaming, as the Node BFF does.
        }
    }

    /**
     * Persists the turn <em>off the request thread</em>. On a client-abort (browser closes
     * mid-stream) the request's virtual thread is interrupted; a Mongo save on an interrupted
     * thread fails its lock acquisition with "Interrupted waiting for lock". Handing the save to
     * a fresh, non-interrupted background thread lets the assistant message still persist, and
     * turns a genuine abort into a clean no-op instead of an ERROR-level stack-trace spam.
     */
    private void persist(Conversation conversation, String userId, String assistantContent, String userMessage) {
        backgroundExecutor.execute(() -> savePersistently(conversation, userId, assistantContent, userMessage));
    }

    private void savePersistently(Conversation conversation, String userId, String assistantContent, String userMessage) {
        try {
            if (assistantContent != null && !assistantContent.isEmpty()) {
                messageService.append(conversation.getId(), userId, MessageService.ROLE_ASSISTANT, assistantContent);
            }
            conversationService.touchAndMaybeTitle(conversation, userMessage);
        } catch (Exception ex) {
            if (isInterruption(ex)) {
                // Client aborted mid-stream and the save was interrupted before it could complete.
                // Expected and benign — not an error. Restore the interrupt flag and move on.
                Thread.currentThread().interrupt();
                log.debug("[stream] post-stream persistence interrupted (client abort) for {}: {}",
                        conversation.getId(), ex.getMessage());
            } else {
                // The stream has already been delivered; a genuine persistence error must not
                // surface to the client. Log and continue, mirroring the reference Node BFF.
                log.error("[stream] post-stream persistence failed for {}: {}",
                        conversation.getId(), ex.getMessage(), ex);
            }
        }
    }

    /**
     * True when a throwable chain is rooted in thread interruption / lock-wait interruption, or
     * the current thread's interrupt flag is set. Covers {@link InterruptedException} however it
     * is wrapped (e.g. Mongo's "Interrupted waiting for lock" {@code MongoInterruptedException}).
     */
    private static boolean isInterruption(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.toLowerCase().contains("interrupted")) {
                return true;
            }
        }
        return Thread.currentThread().isInterrupted();
    }

    private static String stripTrailingCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
