package ai.meridian.gateway.domain.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * A caller's identity + entitlement attributes used for every authorization check.
 *
 * <p>Phase 10: {@link #adminDomains} carries the domains a {@code domain_admin} may
 * manage (empty for RMs; null treated as empty). {@code platform_admin} ignores this
 * field — the global role is enforced at the URL layer by Spring Security.
 */
public record Principal(
        String id,
        List<String> roles,
        List<String> book,          // relationship IDs this caller may read
        int clearance,
        List<String> adminDomains   // domains this caller may administer (domain_admin only)
) {
    /** Fallback for unauthenticated / anonymous callers — empty book, no data access. */
    public static Principal anonymous() {
        return new Principal("anonymous", List.of("relationship_manager"),
                List.of(), 2, List.of());
    }

    /**
     * Build a {@link Principal} from a Spring Security {@link Jwt} (oauth2-resource-server).
     * This is the primary factory after Phase 10 (Spring Security replaces JwtAuthFilter).
     */
    @SuppressWarnings("unchecked")
    public static Principal fromSpringJwt(Jwt jwt) {
        String sub = jwt.getSubject();

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) roles = List.of("relationship_manager");

        List<String> book = jwt.getClaimAsStringList("book");
        if (book == null) book = List.of();

        Object rawClearance = jwt.getClaim("clearance");
        int clearance = rawClearance instanceof Number n ? n.intValue() : 2;

        List<String> adminDomains = jwt.getClaimAsStringList("admin_domains");
        if (adminDomains == null) adminDomains = List.of();

        return new Principal(sub, roles, book, clearance, adminDomains);
    }

    /**
     * Build a {@link Principal} from a Nimbus {@link JWTClaimsSet} (used by legacy code
     * and unit tests that construct claims directly without the Spring Security layer).
     */
    @SuppressWarnings("unchecked")
    public static Principal fromJwtClaims(JWTClaimsSet claims) {
        String sub = claims.getSubject();

        List<String> roles;
        Object rawRoles = claims.getClaim("roles");
        roles = rawRoles instanceof List<?> list ? (List<String>) list : List.of("relationship_manager");

        List<String> book;
        Object rawBook = claims.getClaim("book");
        book = rawBook instanceof List<?> list ? (List<String>) list : List.of();

        int clearance;
        Object rawClearance = claims.getClaim("clearance");
        clearance = rawClearance instanceof Number n ? n.intValue() : 2;

        List<String> adminDomains;
        Object rawAdmin = claims.getClaim("admin_domains");
        adminDomains = rawAdmin instanceof List<?> list ? (List<String>) list : List.of();

        return new Principal(sub, roles, book, clearance, adminDomains);
    }
}
