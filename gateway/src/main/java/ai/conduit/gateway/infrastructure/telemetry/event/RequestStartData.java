package ai.conduit.gateway.infrastructure.telemetry.event;

/**
 * {@code request_start} trace payload. Carries the tenant (A2) so the audit record — assembled from
 * the trace — observes the exact tenant the request executed under. The two-arg constructor is kept
 * for existing tests/tools that predate the tenant field (tenant then null).
 */
public record RequestStartData(String userId, String tenantId, String prompt) {
    public RequestStartData(String userId, String prompt) {
        this(userId, null, prompt);
    }
}
