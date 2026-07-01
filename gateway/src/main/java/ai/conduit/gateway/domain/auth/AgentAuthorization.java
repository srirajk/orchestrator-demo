package ai.conduit.gateway.domain.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fine-grained (resource-level) authorization check for agent registration.
 *
 * <p>The URL-level rule in {@link ai.conduit.gateway.config.SecurityConfig} ensures that only
 * {@code domain_admin} or {@code platform_admin} roles can reach the admin plane.
 * This bean adds the domain-scope constraint: a {@code domain_admin} may only register
 * agents whose {@code domain} field is one of their own {@code admin_domains}.
 *
 * <p>{@code platform_admin} bypasses the domain scope — they manage all domains.
 */
@Component
public class AgentAuthorization {

    private static final Logger log = LoggerFactory.getLogger(AgentAuthorization.class);

    /**
     * Asserts that {@code auth} is allowed to register/modify an agent in {@code agentDomain}.
     *
     * @throws AccessDeniedException if the caller lacks the required scope
     */
    public void assertCanManageAgent(Authentication auth, String agentDomain) {
        if (auth == null) {
            throw new AccessDeniedException("no authentication present");
        }

        List<String> roles = extractRoles(auth);

        if (roles.contains("platform_admin")) {
            log.debug("AgentAuthorization: platform_admin bypasses domain scope for domain={}", agentDomain);
            return;
        }

        if (roles.contains("domain_admin")) {
            List<String> adminDomains = extractAdminDomains(auth);
            // agentDomain must not be null — a null-domain agent requires platform_admin scope.
            if (agentDomain != null && adminDomains.contains(agentDomain)) {
                log.debug("AgentAuthorization: domain_admin allowed for domain={} adminDomains={}",
                        agentDomain, adminDomains);
                return;
            }
            log.warn("AgentAuthorization: domain_admin {} denied for domain={} (adminDomains={})",
                    extractSub(auth), agentDomain, adminDomains);
            throw new AccessDeniedException(
                    "domain_admin may only manage agents in their own domains; " +
                    "domain=" + agentDomain + " not in admin_domains=" + adminDomains);
        }

        throw new AccessDeniedException("agent management requires domain_admin or platform_admin role");
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = (Jwt) jwtAuth.getCredentials();
            List<String> roles = jwt.getClaimAsStringList("roles");
            return roles != null ? roles : List.of();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractAdminDomains(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = (Jwt) jwtAuth.getCredentials();
            List<String> ad = jwt.getClaimAsStringList("admin_domains");
            return ad != null ? ad : List.of();
        }
        return List.of();
    }

    private String extractSub(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return ((Jwt) jwtAuth.getCredentials()).getSubject();
        }
        return auth.getName();
    }
}
