package ai.conduit.gateway.infrastructure.telemetry;

import java.util.List;

/**
 * Storage contract for trace events — allows the glass-box panel to replay past requests.
 *
 * <p>Events are stored per-request (by {@code requestId}) and indexed per-conversation
 * (by {@code conversationId}) so both the request detail view and the conversation history
 * view are O(1) lookups, not full-table scans.
 */
public interface TraceStorageAdapter {

    /** Persist a single trace event. Never throws — errors must be caught internally. */
    void save(TraceEvent event);

    /**
     * Persist a batch of events for one request, in order. Never throws.
     *
     * <p>This is the path the gateway actually uses: a request's events are buffered in memory and
     * flushed once, off the request thread. Implementations should collapse the batch into as few
     * round-trips as possible — the naive per-event default below is what made the write ~92 Redis
     * round-trips per request, on the hot path, through the pool that routing depends on.
     */
    default void saveAll(List<TraceEvent> events) {
        if (events != null) events.forEach(this::save);
    }

    /**
     * Returns all events stored for {@code requestId} in insertion order.
     * Returns an empty list if the requestId is unknown or expired.
     */
    List<TraceEvent> getByRequestId(String requestId);

    /**
     * Returns the {@code limit} most-recent requestIds that belong to {@code conversationId},
     * ordered newest-first.  Returns an empty list if the conversationId is unknown.
     */
    List<String> getRequestIdsByConversation(String conversationId, int limit);
}
