package ai.conduit.gateway.infrastructure.payload;

/**
 * The per-materialize identity + deadline envelope handed to {@link PayloadStore#materialize}.
 *
 * <p><b>Credential slot (F1 seam).</b> {@code callerToken} carries the caller's verified identity so a
 * future agent-side lazy {@code Ref} fetch can mint a per-step credential at the checkpoint. It is
 * UNUSED for the v1 gateway-internal MinIO fetch (the object store is trusted infrastructure the
 * gateway authenticates to with its own credentials) but is a required part of the signature now, so
 * the capability ships later without a signature break.
 *
 * <p><b>Deadline.</b> {@code deadlineEpochMs} bounds the whole materialize; the store honours it
 * alongside its own finite connect/read timeouts and bounded acquire wait, so a slow/lost store fails
 * the node rather than hanging past the request budget.
 *
 * @param deadlineEpochMs absolute wall-clock deadline (epoch millis); {@code <= 0} means "store default"
 * @param principalId     the caller principal (diagnostics / future per-step mint); may be null
 * @param callerToken     the caller's verified JWT (credential slot, deferred use); may be null
 */
public record MaterializeContext(long deadlineEpochMs, String principalId, String callerToken) {

    /** A context with only a deadline — the shape the gateway-internal fetch uses today. */
    public static MaterializeContext withDeadline(long deadlineEpochMs) {
        return new MaterializeContext(deadlineEpochMs, null, null);
    }

    /** Milliseconds remaining until the deadline, floored at 1; {@code Long.MAX_VALUE} when no deadline set. */
    public long remainingMs() {
        if (deadlineEpochMs <= 0) {
            return Long.MAX_VALUE;
        }
        long left = deadlineEpochMs - System.currentTimeMillis();
        return left < 1 ? 1 : left;
    }
}
