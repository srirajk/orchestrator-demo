package ai.conduit.gateway.infrastructure.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single structured event emitted by the request pipeline to the glass-box panel.
 *
 * <p>Events are published via {@link TraceEventPublisher} and streamed to all connected
 * glass-box clients over {@code /trace/stream} SSE. They are also persisted to Redis by
 * {@link RedisTraceStorageAdapter} so historical requests can be replayed.
 *
 * <p>Event types and their {@code data} shapes:
 * <ul>
 *   <li>{@code request_start}     — {@link ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData}</li>
 *   <li>{@code intent_classified} — {@link ai.conduit.gateway.infrastructure.telemetry.event.IntentClassifiedData}</li>
 *   <li>{@code agents_resolved}   — {@link ai.conduit.gateway.infrastructure.telemetry.event.AgentsResolvedData}</li>
 *   <li>{@code entitlement_check} — {@link ai.conduit.gateway.infrastructure.telemetry.event.EntitlementCheckData}</li>
 *   <li>{@code check_denied}      — {@link ai.conduit.gateway.infrastructure.telemetry.event.CheckDeniedData}</li>
 *   <li>{@code agent_start}       — {@link ai.conduit.gateway.infrastructure.telemetry.event.AgentStartData}</li>
 *   <li>{@code agent_complete}    — {@link ai.conduit.gateway.infrastructure.telemetry.event.AgentCompleteData}</li>
 *   <li>{@code synthesis_start}   — {@link ai.conduit.gateway.infrastructure.telemetry.event.SynthesisStartData}</li>
 *   <li>{@code request_complete}  — {@link ai.conduit.gateway.infrastructure.telemetry.event.RequestCompleteData}</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEvent(
        String type,
        String requestId,
        String conversationId,   // null when not associated with a conversation
        long   timestamp,
        Object data
) {
    /** Factory — no conversationId (backwards-compatible). */
    public static TraceEvent of(String type, String requestId, Object data) {
        return new TraceEvent(type, requestId, null, System.currentTimeMillis(), data);
    }

    /** Factory — with conversationId for cross-request history. */
    public static TraceEvent of(String type, String requestId, String conversationId, Object data) {
        return new TraceEvent(type, requestId, conversationId, System.currentTimeMillis(), data);
    }
}
