package ai.meridian.gateway.domain.auth;

import java.util.List;

/**
 * A caller's identity + entitlement attributes used for every authorization check.
 *
 * <p>Populated by {@link PrincipalStore} from Redis per request. The identity seam
 * (Phase 1): userId comes from the {@code X-User-Id} header forwarded by LibreChat
 * (or a stubbed value for the demo).
 */
public record Principal(
        String id,
        List<String> roles,
        List<String> book,    // relationship IDs this RM is permitted to view
        int clearance
) {
    /** Fallback for unauthenticated / anonymous callers — read-only access to demo data. */
    public static Principal anonymous() {
        return new Principal("anonymous", List.of("relationship_manager"),
                List.of("REL-00042", "REL-00099"), 2);
    }
}
