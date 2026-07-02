package ai.conduit.gateway.infrastructure.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Extracts the caller's user ID from the inbound HTTP request.
 *
 * <p>Identity is derived <strong>exclusively</strong> from the verified RS256 JWT sub
 * (populated by Spring Security's BearerTokenAuthenticationFilter before the controller runs).
 * The legacy {@code X-User-Id} trusted-internal-hop was removed — it let any caller assert any
 * identity without verification (a known auth hole). Now that the chat authenticates via Axiom
 * OIDC end-to-end, an unauthenticated request resolves to {@code anonymous} (no data access).
 */
@Component
public class IdentityExtractor {

    public String extractUserId(HttpServletRequest request) {
        // JWT-verified sub from Spring Security is the ONLY source of identity.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = (Jwt) jwtAuth.getCredentials();
            String sub = jwt.getSubject();
            if (sub != null && !sub.isBlank()) return sub;
        }
        return "anonymous";
    }
}

