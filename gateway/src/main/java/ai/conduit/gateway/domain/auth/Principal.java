package ai.conduit.gateway.domain.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * A caller's verified identity + structural attributes used for authorization checks.
 *
 * <p>JWT carries identity and structural claims only: {@code sub}, {@code roles},
 * {@code segments}, {@code clearance}, {@code tenant_id}. No {@code book} claim.
 * Book-of-business is enforced at runtime by the domain coverage service (DISCOVER/CHECK),
 * never embedded in the token or cached in gateway Redis.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code tenantId} — tenant scope for coverage checks (from JWT {@code tenant_id})</li>
 *   <li>{@code roles} — coarse RBAC roles (relationship_manager, domain_admin, platform_admin)</li>
 *   <li>{@code clearance} — numeric data-classification clearance level (1–5)</li>
 *   <li>{@code adminDomains} — org domains this caller may administer (domain_admin only)</li>
 *   <li>{@code segments} — business segments ("wealth", "servicing")</li>
 *   <li>{@code domains} — org domain memberships (e.g. "wealth-private-banking")</li>
 * </ul>
 */
public record Principal(
        String       id,
        String       tenantId,
        List<String> roles,
        int          clearance,
        List<String> adminDomains,
        List<String> segments,
        List<String> domains
) {
    /** Fallback for unauthenticated / anonymous callers — no data access. */
    public static Principal anonymous() {
        return new Principal("anonymous", "default", List.of("relationship_manager"),
                2, List.of(), List.of(), List.of());
    }

    /** Build a {@link Principal} from a Spring Security {@link Jwt}. Primary factory. */
    public static Principal fromSpringJwt(Jwt jwt) {
        String sub = jwt.getSubject();

        String tenantId = jwt.getClaimAsString("tenant_id");
        if (tenantId == null) tenantId = "default";

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) roles = List.of("relationship_manager");

        Object rawClearance = jwt.getClaim("clearance");
        int clearance = rawClearance instanceof Number n ? n.intValue() : 2;

        List<String> adminDomains = jwt.getClaimAsStringList("admin_domains");
        if (adminDomains == null) adminDomains = List.of();

        List<String> segments = jwt.getClaimAsStringList("segments");
        if (segments == null) segments = List.of();

        List<String> domains = jwt.getClaimAsStringList("domains");
        if (domains == null) domains = List.of();

        return new Principal(sub, tenantId, roles, clearance, adminDomains, segments, domains);
    }

    /** Build a {@link Principal} from a Nimbus {@link JWTClaimsSet} (unit tests / legacy). */
    @SuppressWarnings("unchecked")
    public static Principal fromJwtClaims(JWTClaimsSet claims) {
        String sub = claims.getSubject();

        Object rawTenantId = claims.getClaim("tenant_id");
        String tenantId = rawTenantId instanceof String s ? s : "default";

        Object rawRoles = claims.getClaim("roles");
        List<String> roles = rawRoles instanceof List<?> l ? (List<String>) l : List.of("relationship_manager");

        Object rawClearance = claims.getClaim("clearance");
        int clearance = rawClearance instanceof Number n ? n.intValue() : 2;

        Object rawAdmin = claims.getClaim("admin_domains");
        List<String> adminDomains = rawAdmin instanceof List<?> l ? (List<String>) l : List.of();

        Object rawSegments = claims.getClaim("segments");
        List<String> segments = rawSegments instanceof List<?> l ? (List<String>) l : List.of();

        Object rawDomains = claims.getClaim("domains");
        List<String> domains = rawDomains instanceof List<?> l ? (List<String>) l : List.of();

        return new Principal(sub, tenantId, roles, clearance, adminDomains, segments, domains);
    }
}
