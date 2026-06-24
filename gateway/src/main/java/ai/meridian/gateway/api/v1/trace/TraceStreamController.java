package ai.meridian.gateway.api.v1.trace;

import ai.meridian.gateway.infrastructure.telemetry.TraceEventPublisher;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    public TraceStreamController(TraceEventPublisher publisher) {
        this.publisher = publisher;
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

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "subscribers", publisher.subscriberCount());
    }
}
