package ai.meridian.gateway.domain.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * A caller's identity + entitlement attributes used for every authorization check.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code roles} — coarse RBAC roles (relationship_manager, domain_admin, platform_admin)</li>
 *   <li>{@code book} — relationship IDs this caller may read (entitlement check)</li>
 *   <li>{@code clearance} — numeric data-classification clearance level (1–5)</li>
 *   <li>{@code adminDomains} — org domains this caller may administer (domain_admin only)</li>
 *   <li>{@code segments} — business segments ("wealth", "servicing") — governs which agent
 *       domains this principal can invoke (e.g. "wealth" → wealth-management agents)</li>
 *   <li>{@code domains} — org domain memberships (e.g. "wealth-private-banking") — used in
 *       domain-resource Cerbos policies</li>
 * </ul>
 */
public record Principal(
        String       id,
        List<String> roles,
        List<String> book,
        int          clearance,
        List<String> adminDomains,
        List<String> segments,    // business segments: "wealth", "servicing", …
        List<String> domains      // org domain memberships: "wealth-private-banking", …
) {
    /** Fallback for unauthenticated / anonymous callers — no data access. */
    public static Principal anonymous() {
        return new Principal("anonymous", List.of("relationship_manager"),
                List.of(), 2, List.of(), List.of(), List.of());
    }

    /**
     * Build a {@link Principal} from a Spring Security {@link Jwt} (oauth2-resource-server).
     * Primary factory after Phase 10.
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

        List<String> segments = jwt.getClaimAsStringList("segments");
        if (segments == null) segments = List.of();

        List<String> domains = jwt.getClaimAsStringList("domains");
        if (domains == null) domains = List.of();

        return new Principal(sub, roles, book, clearance, adminDomains, segments, domains);
    }

    /**
     * Build a {@link Principal} from a Nimbus {@link JWTClaimsSet} (unit tests and legacy code).
     */
    @SuppressWarnings("unchecked")
    public static Principal fromJwtClaims(JWTClaimsSet claims) {
        String sub = claims.getSubject();

        Object rawRoles = claims.getClaim("roles");
        List<String> roles = rawRoles instanceof List<?> l ? (List<String>) l : List.of("relationship_manager");

        Object rawBook = claims.getClaim("book");
        List<String> book = rawBook instanceof List<?> l ? (List<String>) l : List.of();

        Object rawClearance = claims.getClaim("clearance");
        int clearance = rawClearance instanceof Number n ? n.intValue() : 2;

        Object rawAdmin = claims.getClaim("admin_domains");
        List<String> adminDomains = rawAdmin instanceof List<?> l ? (List<String>) l : List.of();

        Object rawSegments = claims.getClaim("segments");
        List<String> segments = rawSegments instanceof List<?> l ? (List<String>) l : List.of();

        Object rawDomains = claims.getClaim("domains");
        List<String> domains = rawDomains instanceof List<?> l ? (List<String>) l : List.of();

        return new Principal(sub, roles, book, clearance, adminDomains, segments, domains);
    }
}
