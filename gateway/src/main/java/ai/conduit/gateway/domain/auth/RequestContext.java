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
 */
public final class RequestContext {

    private static final ThreadLocal<Principal> PRINCIPAL = new ThreadLocal<>();

    private RequestContext() {}

    public static void setPrincipal(Principal p) {
        PRINCIPAL.set(p);
    }

    public static Principal getPrincipal() {
        return PRINCIPAL.get();
    }

    public static void clear() {
        PRINCIPAL.remove();
    }
}
