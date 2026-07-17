package ai.conduit.chat.chat;

import ai.conduit.chat.auth.AccessTokenService;
import ai.conduit.chat.auth.CurrentUser;
import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.conversation.ConversationService;
import ai.conduit.chat.memory.ChatMessage;
import ai.conduit.chat.memory.ContextAssembler;
import ai.conduit.chat.message.Message;
import ai.conduit.chat.message.MessageService;
import ai.conduit.chat.web.BadRequestException;
import ai.conduit.chat.web.GatewayException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * The structured-clarification RESUME endpoint: {@code POST /api/clarify/resolve}.
 *
 * <p>The SPA calls this when the user answers an outstanding clarification form. It authenticates on the
 * same session/JWT path as chat, verifies conversation ownership, folds the answer into a normal user
 * turn, then re-drives the gateway's chat path with the form's single-use {@code nonce} (+ chosen
 * selection). The gateway consumes the descriptor and re-runs the WHOLE pipeline — grounding, routing,
 * entitlement CHECK, invoke, synth — so the answer is re-authorized and streamed back exactly like a
 * normal chat turn. The form is never trusted as authorization; free text is untrusted DATA (rule 4c).
 *
 * <p>Mirrors {@link ChatController}'s fail-fast + orphan-rollback discipline: a non-2xx gateway response
 * is detected before any bytes are streamed, and the just-persisted user turn is rolled back if the
 * gateway rejects it.
 */
@RestController
@RequestMapping("/api/clarify")
public class ClarifyController {

    private final CurrentUser currentUser;
    private final AccessTokenService accessTokenService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final ContextAssembler contextAssembler;
    private final GatewayClient gatewayClient;
    private final ChatStreamService chatStreamService;

    public ClarifyController(CurrentUser currentUser,
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

    @PostMapping("/resolve")
    public ResponseEntity<StreamingResponseBody> resolve(@Valid @RequestBody ClarifyResolveRequest request) {
        // Validate: a resume must carry an actual answer (a chip selection or free text). The nonce +
        // conversationId are @NotBlank; this is the "at least one answer" cross-field rule.
        if (!request.hasAnswer()) {
            throw new BadRequestException("A selection or free-text answer is required.");
        }

        String userId = currentUser.id();
        String accessToken = accessTokenService.currentAccessToken();

        // Verify ownership (→ 404 if not owned/absent) — same gate as a chat turn.
        Conversation conversation = conversationService.getOwnedOrThrow(request.conversationId(), userId);

        String folded = request.foldedContent();

        // 1. Persist the folded answer as the user turn (part of the recent window for context assembly).
        Message userMessage = messageService.append(
                conversation.getId(), userId, MessageService.ROLE_USER, folded);

        // 2. Assemble client-owned context (recent window + optional facts-free summary).
        List<ChatMessage> context = contextAssembler.assemble(conversation, userId);

        // 3. Open the gateway resume stream and fail fast on a non-2xx status (before any bytes). Roll back
        //    the just-persisted user turn on rejection so a failed resume leaves no orphan.
        GatewayStream stream;
        try {
            stream = gatewayClient.openChatStream(accessToken, conversation.getId(), context,
                    request.nonce(), request.selectionHeader());
        } catch (RuntimeException ex) {
            messageService.delete(userMessage);
            throw ex;
        }
        if (!stream.successful()) {
            int status = stream.status();
            stream.close();
            messageService.delete(userMessage);
            HttpStatus resolved = HttpStatus.resolve(status);
            if (resolved == null) {
                throw new GatewayException("Gateway returned " + status);
            }
            return ResponseEntity.status(resolved).build();
        }

        // 4. Stream the resumed answer through to the client exactly like a normal chat turn.
        try {
            StreamingResponseBody body = out ->
                    chatStreamService.pipe(stream, out, conversation, userId, folded);
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
