package ai.conduit.chat.chat;

import ai.conduit.chat.auth.CurrentUser;
import ai.conduit.chat.conversation.ConversationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Proxies the Conduit gateway's glass-box trace SSE stream to the SPA, scoped to a single
 * conversation the caller owns.
 *
 * <p>{@code GET /api/conversations/{id}/trace/stream} — verifies the caller owns the
 * conversation, then opens the gateway's {@code /trace/stream?conversationId=id} and pipes the
 * SSE bytes straight through. This is how the web trace rail subscribes: conversation-scoped, and
 * gated by the BFF session (per-user history isolation) rather than by forwarding a token to the
 * gateway's public trace endpoint.
 */
@RestController
@RequestMapping("/api/conversations")
public class TraceController {

    /** Copy buffer for the SSE pass-through. */
    private static final int BUFFER_SIZE = 4096;

    private final CurrentUser currentUser;
    private final ConversationService conversationService;
    private final GatewayClient gatewayClient;

    public TraceController(CurrentUser currentUser,
                           ConversationService conversationService,
                           GatewayClient gatewayClient) {
        this.currentUser = currentUser;
        this.conversationService = conversationService;
        this.gatewayClient = gatewayClient;
    }

    @GetMapping("/{id}/trace/stream")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable String id) {
        String userId = currentUser.id();
        // Ownership check → 404 if the caller does not own the conversation.
        conversationService.getOwnedOrThrow(id, userId);

        StreamingResponseBody body = out -> {
            GatewayStream stream = gatewayClient.openTraceStream(id);
            try (stream) {
                if (!stream.successful()) {
                    return; // gateway declined — end the SSE stream cleanly
                }
                pump(stream.body(), out);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /** Pumps the upstream SSE bytes to the client, flushing each chunk so frames are live. */
    private static void pump(InputStream in, OutputStream out) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception e) {
            // Client disconnect or upstream close — end the stream quietly.
        }
    }
}
