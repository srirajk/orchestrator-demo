package ai.conduit.chat.chat;

import ai.conduit.chat.auth.AccessTokenService;
import ai.conduit.chat.auth.CurrentUser;
import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.conversation.ConversationService;
import ai.conduit.chat.memory.ChatMessage;
import ai.conduit.chat.memory.ContextAssembler;
import ai.conduit.chat.message.Message;
import ai.conduit.chat.message.MessageService;
import ai.conduit.chat.web.GatewayException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * The core streaming endpoint: {@code POST /api/conversations/{id}/messages}.
 *
 * <p>Turn flow: verify ownership → persist the user message → assemble client-owned
 * context → open the gateway completion (forwarding the user's access token) → if the
 * gateway is healthy, stream its SSE through to the client byte-for-byte while
 * accumulating the reply → persist the assistant message and auto-title the conversation.
 *
 * <p>A non-2xx gateway response is detected <b>before</b> any bytes are streamed, so the
 * client receives a clean 502 instead of hanging.
 */
@RestController
@RequestMapping("/api/conversations")
public class ChatController {

    private final CurrentUser currentUser;
    private final AccessTokenService accessTokenService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final ContextAssembler contextAssembler;
    private final GatewayClient gatewayClient;
    private final ChatStreamService chatStreamService;

    public ChatController(CurrentUser currentUser,
                          AccessTokenService accessTokenService,
                          ConversationService conversationService,
                          MessageService messageService,
                          ContextAssembler contextAssembler,
                          GatewayClient gatewayClient,
                          ChatStreamService chatStreamService) {
        this.currentUser = currentUser;
        this.accessTokenService = accessTokenService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.contextAssembler = contextAssembler;
        this.gatewayClient = gatewayClient;
        this.chatStreamService = chatStreamService;
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<StreamingResponseBody> sendMessage(@PathVariable String id,
                                                             @Valid @RequestBody SendMessageRequest request) {
        String userId = currentUser.id();
        String accessToken = accessTokenService.currentAccessToken();

        // Verify ownership (→ 404 if not owned/absent).
        Conversation conversation = conversationService.getOwnedOrThrow(id, userId);

        String content = request.content().strip();

        // 1. Persist the user message (part of the recent window for context assembly).
        Message userMessage = messageService.append(id, userId, MessageService.ROLE_USER, content);

        // 2. Assemble client-owned context (recent window + optional facts-free summary).
        List<ChatMessage> context = contextAssembler.assemble(conversation, userId);

        // 3. Open the gateway stream and fail fast on a non-2xx status (before streaming).
        //    Because we detect this BEFORE writing any bytes, the response is not yet a committed
        //    text/event-stream: we return a normal (empty-body) response carrying the gateway's
        //    status so the client never sees a hung stream. A 401 (e.g. the signing key rotated
        //    and the forwarded token no longer verifies) is surfaced as a real 401 so the SPA's
        //    apiStream 401-handler redirects to /api/auth/login instead of hanging on an empty SSE.
        //    If the gateway rejects the turn we roll back the just-persisted user message so a
        //    failed send leaves no orphan and a retry does not duplicate it.
        GatewayStream stream;
        try {
            stream = gatewayClient.openChatStream(accessToken, id, context);
        } catch (RuntimeException ex) {
            messageService.delete(userMessage); // gateway unreachable → no orphan user message
            throw ex;
        }
        if (!stream.successful()) {
            int status = stream.status();
            stream.close();
            messageService.delete(userMessage); // gateway rejected the turn → roll back the orphan
            HttpStatus resolved = HttpStatus.resolve(status);
            if (resolved == null) {
                // Unknown/invalid status from the gateway → treat as a bad gateway (502).
                throw new GatewayException("Gateway returned " + status);
            }
            return ResponseEntity.status(resolved).build();
        }

        // 4. Stream through to the client while accumulating + persisting the reply. Ownership of
        //    the (successful) stream transfers to the streaming body, which always closes it
        //    (pipe() uses try-with-resources). Guard the hand-off so that if we fail to build/return
        //    the response for any reason, the stream is still closed rather than leaked.
        try {
            StreamingResponseBody body = out -> chatStreamService.pipe(stream, out, conversation, userId, content);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(body);
        } catch (RuntimeException ex) {
            stream.close();
            throw ex;
        }
    }
}
