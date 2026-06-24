package ai.meridian.gateway.infrastructure.telemetry;

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
