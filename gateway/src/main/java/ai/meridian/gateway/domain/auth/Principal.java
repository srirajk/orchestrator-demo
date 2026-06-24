package ai.meridian.gateway.domain.auth;

import com.nimbusds.jwt.JWTClaimsSet;

import java.util.List;

/**
 * A caller's identity + entitlement attributes used for every authorization check.
 *
 * <p>Populated by {@link PrincipalStore} from Redis per request. The identity seam
 * (Phase 1): userId comes from the {@code X-User-Id} header forwarded by LibreChat
 * (or a stubbed value for the demo).
 *
 * <p>Phase 8 (M15): when a verified RS256 JWT is present, {@link #fromJwtClaims(JWTClaimsSet)}
 * builds the principal directly from the token's claims, bypassing the Redis lookup and
 * ensuring the entitlement data matches what the issuer attested to.
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

    /**
     * Build a {@link Principal} from a verified RS256 JWT's claims.
     *
     * <p>Expected custom claims:
     * <ul>
     *   <li>{@code roles}     — {@code List<String>} (defaults to {@code ["relationship_manager"]})</li>
     *   <li>{@code book}      — {@code List<String>} of relationship IDs (defaults to empty)</li>
     *   <li>{@code clearance} — {@code Number} (defaults to 2)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static Principal fromJwtClaims(JWTClaimsSet claims) {
        String sub = claims.getSubject();

        List<String> roles;
        Object rawRoles = claims.getClaim("roles");
        if (rawRoles instanceof List<?> list) {
            roles = (List<String>) list;
        } else {
            roles = List.of("relationship_manager");
        }

        List<String> book;
        Object rawBook = claims.getClaim("book");
        if (rawBook instanceof List<?> list) {
            book = (List<String>) list;
        } else {
            book = List.of();
        }

        int clearance;
        Object rawClearance = claims.getClaim("clearance");
        if (rawClearance instanceof Number n) {
            clearance = n.intValue();
        } else {
            clearance = 2;
        }

        return new Principal(sub, roles, book, clearance);
    }
}
