package ai.meridian.gateway.api.v1.chat;

import ai.meridian.gateway.api.v1.chat.dto.ChatRequest;
import ai.meridian.gateway.domain.auth.Principal;
import ai.meridian.gateway.domain.auth.RequestContext;
import ai.meridian.gateway.domain.chat.ChatService;
import ai.meridian.gateway.infrastructure.identity.IdentityExtractor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/v1")
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ChatService chatService;
    private final IdentityExtractor identityExtractor;

    public ChatCompletionsController(ChatService chatService, IdentityExtractor identityExtractor) {
        this.chatService = chatService;
        this.identityExtractor = identityExtractor;
    }

    @PostMapping(value = "/chat/completions")
    public SseEmitter chatCompletions(
            @RequestBody ChatRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        // Force SSE content type regardless of Accept header (LibreChat agents client
        // may send Accept: application/json which Spring would 406 with produces constraint)
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        String userId = identityExtractor.extractUserId(httpRequest);

        // Capture servlet-thread context BEFORE crossing the async boundary.
        // ThreadLocals (RequestContext, MDC) are not inherited by CompletableFuture threads.
        Principal principal = RequestContext.getPrincipal(); // null if no JWT
        String conversationId = httpRequest.getHeader("X-Conversation-Id");

        log.debug("Chat completions: model={}, messages={}, stream={}, userId={}, conversationId={}",
                request.model(),
                request.messages() != null ? request.messages().size() : 0,
                request.stream(), userId, conversationId);

        SseEmitter emitter = new SseEmitter(120_000L);

        // With virtual threads enabled, each CompletableFuture.runAsync runs on
        // a fresh virtual thread — no thread-pool starvation under high concurrency.
        if (chatService.isTitleRequest(request)) {
            log.debug("Detected auto-title request — short-circuiting");
            CompletableFuture.runAsync(() -> chatService.streamTitle(emitter));
        } else {
            CompletableFuture.runAsync(() ->
                    chatService.handleChat(request, emitter, userId, principal, conversationId));
        }

        return emitter;
    }
}
