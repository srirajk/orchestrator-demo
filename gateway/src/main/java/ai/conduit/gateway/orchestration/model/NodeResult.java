package ai.conduit.gateway.orchestration.model;

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
        BREAKER_OPEN,
        /** Declared node condition evaluated false; node was not applicable, not failed. */
        SKIPPED_CONDITION_FALSE,
        /** Declared node condition failed at runtime; visible failure. */
        CONDITION_ERROR,
        /** Caller identity expired or was absent before this hop was dispatched. */
        AUTH_EXPIRED,
        /**
         * The governed-invocation checkpoint refused this hop: no fresh matching authorization
         * grant (fail-closed) or an on-path PDP deny. Distinct from a Cerbos-pruned agent — a
         * pruned agent produces NO NodeResult, whereas a DENIED one flows to results, gets an
         * {@code agent_complete} frame, and reaches synthesis as missing-data. Treated as a failure
         * ({@link #isFailure()} is already true for any non-OK, non-clean-skip status).
         */
        DENIED
    }

    /** Convenience: true when the call completed successfully with data. */
    @JsonIgnore
    public boolean isOk() {
        return status == Status.OK;
    }

    @JsonIgnore
    public boolean isCleanSkip() {
        return status == Status.SKIPPED_CONDITION_FALSE;
    }

    @JsonIgnore
    public boolean isFailure() {
        return !isOk() && !isCleanSkip();
    }
}
