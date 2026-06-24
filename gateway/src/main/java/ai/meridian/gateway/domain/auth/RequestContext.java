package ai.meridian.gateway.domain.auth;

/**
 * Per-request context stored in a ThreadLocal.
 *
 * With virtual threads (one vthread per request) this is safe as long as the
 * ThreadLocal is cleared after the request completes — see {@link
 * ai.meridian.gateway.infrastructure.telemetry.RequestCorrelationFilter}.
 *
 * When a valid RS256 JWT is present, the verified {@link Principal} is stored
 * here by {@code RequestCorrelationFilter} so that downstream services (e.g.
 * {@link PrincipalStore}) can return the JWT-derived identity instead of
 * performing a Redis lookup.
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
