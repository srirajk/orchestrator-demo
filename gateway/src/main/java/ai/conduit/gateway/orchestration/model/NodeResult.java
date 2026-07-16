package ai.conduit.gateway.orchestration.model;

import ai.conduit.gateway.adapter.PayloadHandle;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The outcome of executing a single {@link PlanNode} through the agent harness.
 *
 * <p>The status field categorises the outcome; callers should always check
 * {@link #isOk()} before reading {@code data}.  A NodeResult is always produced —
 * the harness never propagates an exception to the executor.
 *
 * <p><b>F4 provenance seam.</b> The 8th component {@code payload} is the {@link PayloadHandle} for this
 * node's output — {@link PayloadHandle.Inline} in the normal case, a content-addressed
 * {@link PayloadHandle.Ref} when the adapter spilled to the claim-check store. It is {@code @JsonIgnore}:
 * the sealed handle NEVER flows into the trace/insight serializers (audit gets an explicit flat view,
 * {@code PayloadAuditView}). {@link #data()} is unchanged — it still returns the identical in-memory
 * stamped tree, so every existing consumer is bit-for-bit unaffected. The 7-arg compat constructor
 * wraps {@code data} in an {@code Inline} with ZERO hashing and ZERO copy, so every existing
 * construction compiles and pays no CPU.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeResult(
        String nodeId,
        String agentId,
        String protocol,
        Status status,
        JsonNode data,        // non-null only when status == OK
        long latencyMs,
        String errorMessage,  // non-null only when status != OK
        @JsonIgnore PayloadHandle payload   // F4: provenance handle; never serialized
) {

    /**
     * Backward-compatible 7-arg constructor. Wraps {@code data} in an adapter-provenance
     * {@link PayloadHandle.Inline} (reference wrap — no hashing, no serialization, no copy), so all
     * existing constructions and tests compile untouched and pay zero request-path CPU.
     */
    public NodeResult(String nodeId, String agentId, String protocol, Status status,
                      JsonNode data, long latencyMs, String errorMessage) {
        this(nodeId, agentId, protocol, status, data, latencyMs, errorMessage,
                data == null ? null : new PayloadHandle.Inline(data));
    }

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
