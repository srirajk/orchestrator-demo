package ai.conduit.gateway.domain.auth;

/**
 * Per-request context stored in a ThreadLocal.
 *
 * With virtual threads (one vthread per request) this is safe as long as the
 * ThreadLocal is cleared after the request completes — see {@link
 * ai.conduit.gateway.infrastructure.telemetry.RequestCorrelationFilter}.
 *
 * When a valid RS256 JWT is present, the verified {@link Principal} is stored
 * here by {@code RequestCorrelationFilter} so that downstream services can use
 * the JWT-derived identity. Identity is derived exclusively from the verified
 * JWT — the legacy {@code X-User-Id}/Redis principal-lookup path was removed.
 *
 * <p><b>A2 capture-only contract.</b> The {@link TenantExecutionContext} is resolved once in the
 * filter and parked here <i>solely</i> so the controller can read it on the servlet thread before the
 * virtual-thread boundary — exactly as it captures the {@link Principal} and MDC. Downstream production
 * code must NOT recover the tenant from this holder; it receives the {@link TenantExecutionContext}
 * as an explicit parameter. Both slots are cleared when the request ends.
 */
public final class RequestContext {

    private static final ThreadLocal<Principal> PRINCIPAL = new ThreadLocal<>();
    private static final ThreadLocal<TenantExecutionContext> TENANT = new ThreadLocal<>();

    private RequestContext() {}

    public static void setPrincipal(Principal p) {
        PRINCIPAL.set(p);
    }

    public static Principal getPrincipal() {
        return PRINCIPAL.get();
    }

    /** Filter-only: park the resolved tenant context for the controller to capture. */
    public static void setTenant(TenantExecutionContext tenant) {
        TENANT.set(tenant);
    }

    /** Controller capture seam only — downstream code must receive the context explicitly. */
    public static TenantExecutionContext getTenant() {
        return TENANT.get();
    }

    public static void clear() {
        PRINCIPAL.remove();
        TENANT.remove();
    }
}
