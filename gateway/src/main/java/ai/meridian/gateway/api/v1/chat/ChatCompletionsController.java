package ai.meridian.gateway.api.v1.chat;

import ai.meridian.gateway.api.v1.chat.dto.ChatRequest;
import ai.meridian.gateway.domain.chat.ChatService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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

    public ChatCompletionsController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletions(
            @RequestBody ChatRequest request,
            HttpServletResponse response) {

        // Prevent proxy/browser buffering
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        log.debug("Chat completions: model={}, messages={}, stream={}",
                request.model(),
                request.messages() != null ? request.messages().size() : 0,
                request.stream());

        SseEmitter emitter = new SseEmitter(120_000L);

        // With virtual threads enabled, each CompletableFuture.runAsync runs on
        // a fresh virtual thread — no thread-pool starvation under high concurrency.
        if (chatService.isTitleRequest(request)) {
            log.debug("Detected auto-title request — short-circuiting");
            CompletableFuture.runAsync(() -> chatService.streamTitle(emitter));
        } else {
            CompletableFuture.runAsync(() -> chatService.handleChat(request, emitter));
        }

        return emitter;
    }
}
