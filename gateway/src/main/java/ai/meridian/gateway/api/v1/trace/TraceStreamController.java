package ai.meridian.gateway.api.v1.trace;

import ai.meridian.gateway.infrastructure.telemetry.TraceEvent;
import ai.meridian.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.meridian.gateway.infrastructure.telemetry.TraceStorageAdapter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Streams live glass-box trace events to connected clients.
 *
 * <p>{@code GET /trace/stream} — an SSE endpoint. The glass-box HTML panel
 * connects here and receives all pipeline events as they happen: intent
 * classification, agent routing, entitlement checks, per-agent results,
 * synthesis progress, and request completion.
 */
@RestController
@RequestMapping("/trace")
public class TraceStreamController {

    private final TraceEventPublisher publisher;
    private final TraceStorageAdapter  storage;

    public TraceStreamController(TraceEventPublisher publisher, TraceStorageAdapter storage) {
        this.publisher = publisher;
        this.storage   = storage;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("X-Accel-Buffering", "no");   // disable nginx/proxy buffering

        String clientId = "gb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SseEmitter emitter = publisher.subscribe(clientId);

        emitter.onCompletion(() -> publisher.unsubscribe(clientId));
        emitter.onTimeout(()    -> publisher.unsubscribe(clientId));
        emitter.onError(e ->      publisher.unsubscribe(clientId));

        return emitter;
    }

    /** Returns all stored events for a specific request (glass-box replay). */
    @GetMapping("/{requestId}")
    public List<TraceEvent> getByRequestId(@PathVariable String requestId) {
        return storage.getByRequestId(requestId);
    }

    /**
     * Returns the {@code limit} most-recent requestIds for a conversation, newest-first.
     * Use to build the conversation history timeline in the glass-box panel.
     */
    @GetMapping("/history")
    public Map<String, Object> history(
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "20") int limit) {
        List<String> requestIds = storage.getRequestIdsByConversation(conversationId, limit);
        return Map.of("conversationId", conversationId, "requestIds", requestIds, "count", requestIds.size());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "subscribers", publisher.subscriberCount());
    }
}
