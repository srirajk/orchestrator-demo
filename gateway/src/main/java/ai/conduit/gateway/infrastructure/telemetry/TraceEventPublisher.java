package ai.conduit.gateway.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

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

    /** Heartbeat cadence. The glass-box client reconnects if it sees silence; a periodic
     *  SSE comment keeps an idle connection alive so it doesn't miss the next request's burst. */
    private static final long HEARTBEAT_SECONDS = 15L;

    private record Subscriber(SseEmitter emitter, Predicate<TraceEvent> filter) {}

    private final ConcurrentHashMap<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final TraceStorageAdapter storage;
    private ScheduledExecutorService heartbeat;

    public TraceEventPublisher(ObjectMapper mapper, TraceStorageAdapter storage) {
        this.mapper  = mapper;
        this.storage = storage;
    }

    @PostConstruct
    void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "glassbox-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeat != null) heartbeat.shutdownNow();
    }

    /** Send an SSE comment (`:hb`) to every subscriber so idle connections stay live. */
    private void sendHeartbeat() {
        if (subscribers.isEmpty()) return;
        subscribers.forEach((clientId, subscriber) -> {
            try {
                subscriber.emitter().send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                subscribers.remove(clientId);
            }
        });
    }

    /**
     * Subscribe a new glass-box client. Returns an {@link SseEmitter} that will
     * receive all subsequent events until disconnection or server restart.
     */
    public SseEmitter subscribe(String clientId) {
        return subscribe(clientId, event -> true);
    }

    /**
     * Subscribe a new glass-box client with a server-side event filter. Workbench clients
     * pass their conversationId here so a broadcast trace stream cannot leak another
     * conversation's frames into the rail.
     */
    public SseEmitter subscribe(String clientId, Predicate<TraceEvent> filter) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        subscribers.put(clientId, new Subscriber(emitter, filter));
        // Flush an immediate comment so the browser's EventSource fires `onopen` right away
        // (otherwise it sits in CONNECTING until the first real event and the client may
        // give up and reconnect, churning past the next request's events).
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception e) {
            subscribers.remove(clientId);
            emitter.completeWithError(e);
        }
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

        subscribers.forEach((clientId, subscriber) -> {
            if (!subscriber.filter().test(event)) return;
            try {
                subscriber.emitter().send(SseEmitter.event().data(json));
            } catch (Exception e) {
                log.debug("Glass-box client {} disconnected during publish — removing", clientId);
                subscribers.remove(clientId);
                subscriber.emitter().completeWithError(e);
            }
        });
    }

    /** Convenience: publish with a freshly generated request ID. */
    public static String newRequestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
