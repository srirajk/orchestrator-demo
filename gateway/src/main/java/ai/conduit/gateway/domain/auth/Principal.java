package ai.conduit.gateway.domain.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A caller's verified identity + structural attributes used for authorization checks.
 *
 * <p>JWT carries identity and structural claims only: {@code sub}, {@code roles},
 * {@code segments}, {@code tenant_id}. No {@code book} claim, no numeric {@code clearance}.
 * Book-of-business is enforced at runtime by the domain coverage service (DISCOVER/CHECK),
 * never embedded in the token or cached in gateway Redis.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code tenantId} — tenant scope for coverage checks (from JWT {@code tenant_id})</li>
 *   <li>{@code roles} — coarse RBAC roles (chat_user, domain_admin, platform_admin)</li>
 *   <li>{@code adminDomains} — org domains this caller may administer (domain_admin only)</li>
 *   <li>{@code segments} — per-segment classification map: business segment → the data
 *       tier the caller holds in that segment (e.g. {@code {"wealth":"confidential-pii",
 *       "servicing":"confidential"}}). Replaces the flat segment list + global clearance.</li>
 *   <li>{@code domains} — org domain memberships (e.g. "wealth-private-banking")</li>
 * </ul>
 */
public record Principal(
        String              id,
        String              tenantId,
        List<String>        roles,
        List<String>        adminDomains,
        Map<String, String> segments,
        List<String>        domains
) {
    /** Fallback for unauthenticated / anonymous callers — no data access. */
    public static Principal anonymous() {
        return new Principal("anonymous", "default", List.of("chat_user"),
                List.of(), Map.of(), List.of());
    }

    /** Build a {@link Principal} from a Spring Security {@link Jwt}. Primary factory. */
    public static Principal fromSpringJwt(Jwt jwt) {
        String sub = jwt.getSubject();

        // A1: tenant_id is now mandatory at verify — SecurityConfig#jwtDecoder rejects any token
        // lacking a canonical tenant_id and a matching tenant-qualified audience, so an
        // authenticated principal always carries the real tenant here. The null-guard remains only
        // for the anonymous / non-JWT path (A2 removes the request-path default outright).
        String tenantId = jwt.getClaimAsString("tenant_id");
        if (tenantId == null) tenantId = "default";

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) roles = List.of("chat_user");

        List<String> adminDomains = jwt.getClaimAsStringList("admin_domains");
        if (adminDomains == null) adminDomains = List.of();

        Map<String, String> segments = toSegmentMap(jwt.getClaim("segments"));

        List<String> domains = jwt.getClaimAsStringList("domains");
        if (domains == null) domains = List.of();

        return new Principal(sub, tenantId, roles, adminDomains, segments, domains);
    }

    /** Build a {@link Principal} from a Nimbus {@link JWTClaimsSet} (unit tests / legacy). */
    @SuppressWarnings("unchecked")
    public static Principal fromJwtClaims(JWTClaimsSet claims) {
        String sub = claims.getSubject();

        Object rawTenantId = claims.getClaim("tenant_id");
        String tenantId = rawTenantId instanceof String s ? s : "default";

        Object rawRoles = claims.getClaim("roles");
        List<String> roles = rawRoles instanceof List<?> l ? (List<String>) l : List.of("chat_user");

        Object rawAdmin = claims.getClaim("admin_domains");
        List<String> adminDomains = rawAdmin instanceof List<?> l ? (List<String>) l : List.of();

        Map<String, String> segments = toSegmentMap(claims.getClaim("segments"));

        Object rawDomains = claims.getClaim("domains");
        List<String> domains = rawDomains instanceof List<?> l ? (List<String>) l : List.of();

        return new Principal(sub, tenantId, roles, adminDomains, segments, domains);
    }

    /**
     * Coerces a {@code segments} claim into a {@code segment → classification} map.
     * The claim is a JSON object (the per-segment map). A legacy JSON array (flat segment
     * list, no tier) is tolerated by mapping each entry to {@code "internal"} — least
     * privilege, so a stale token can never over-grant during migration.
     */
    private static Map<String, String> toSegmentMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) out.put(k.toString(), v.toString());
            });
        } else if (raw instanceof List<?> l) {
            for (Object s : l) {
                if (s != null) out.put(s.toString(), "internal");
            }
        }
        return out;
    }
}
