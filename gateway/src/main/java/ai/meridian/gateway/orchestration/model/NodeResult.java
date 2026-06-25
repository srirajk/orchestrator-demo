package ai.meridian.gateway.orchestration.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The outcome of executing a single {@link PlanNode} through the agent harness.
 *
 * <p>The status field categorises the outcome; callers should always check
 * {@link #isOk()} before reading {@code data}.  A NodeResult is always produced —
 * the harness never propagates an exception to the executor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeResult(
        String nodeId,
        String agentId,
        String protocol,
        Status status,
        JsonNode data,        // non-null only when status == OK
        long latencyMs,
        String errorMessage   // non-null only when status != OK
) {

    public enum Status {
        /** Agent call succeeded and returned usable data. */
        OK,
        /** Agent returned an error or threw an unexpected exception. */
        FAILED,
        /** Agent did not respond within its SLA timeout. */
        TIMEOUT,
        /** Circuit breaker is open — the agent is currently excluded from calls. */
        BREAKER_OPEN
    }

    /** Convenience: true when the call completed successfully with data. */
    @JsonIgnore
    public boolean isOk() {
        return status == Status.OK;
    }
}
