package ai.meridian.gateway.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory pub/sub bus for glass-box trace events.
 *
 * <p>Each connected glass-box client gets its own {@link SseEmitter}. On every
 * {@link #publish(TraceEvent)} call, the event is serialized to JSON and sent to all
 * live subscribers; dead emitters are pruned immediately.
 *
 * <p>Thread-safe: the subscriber map is a {@link ConcurrentHashMap}; individual
 * {@code SseEmitter} sends are guarded by per-emitter exception handling so a
 * slow/broken client never blocks the pipeline virtual thread.
 */
@Component
public class TraceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TraceEventPublisher.class);

    private final ConcurrentHashMap<String, SseEmitter> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final TraceStorageAdapter storage;

    public TraceEventPublisher(ObjectMapper mapper, TraceStorageAdapter storage) {
        this.mapper  = mapper;
        this.storage = storage;
    }

    /**
     * Subscribe a new glass-box client. Returns an {@link SseEmitter} that will
     * receive all subsequent events until disconnection or server restart.
     */
    public SseEmitter subscribe(String clientId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        subscribers.put(clientId, emitter);
        log.debug("Glass-box subscriber connected: {} (total={})", clientId, subscribers.size());
        return emitter;
    }

    /** Remove a subscriber on disconnect or timeout. */
    public void unsubscribe(String clientId) {
        subscribers.remove(clientId);
        log.debug("Glass-box subscriber disconnected: {} (total={})", clientId, subscribers.size());
    }

    /** Returns the current subscriber count. */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Publish {@code event} to all connected glass-box clients and persist it to Redis.
     * Never throws — dead subscribers are silently pruned; storage errors are logged.
     */
    public void publish(TraceEvent event) {
        // Persist regardless of whether any SSE clients are connected
        storage.save(event);

        if (subscribers.isEmpty()) return;

        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("TraceEventPublisher: could not serialize event type={}: {}", event.type(), e.getMessage());
            return;
        }

        subscribers.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (Exception e) {
                log.debug("Glass-box client {} disconnected during publish — removing", clientId);
                subscribers.remove(clientId);
                emitter.completeWithError(e);
            }
        });
    }

    /** Convenience: publish with a freshly generated request ID. */
    public static String newRequestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
