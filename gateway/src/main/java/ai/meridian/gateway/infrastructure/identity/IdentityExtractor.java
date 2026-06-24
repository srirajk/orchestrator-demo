package ai.meridian.gateway.infrastructure.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Extracts the caller's user ID from the inbound HTTP request.
 *
 * <p>Phase 10: reads the authenticated principal from Spring Security's {@link SecurityContextHolder}
 * (populated by BearerTokenAuthenticationFilter before the controller runs). Falls back to the
 * {@code X-User-Id} header for trusted-internal-hop requests (LibreChat → gateway without a JWT).
 */
@Component
public class IdentityExtractor {

    public String extractUserId(HttpServletRequest request) {
        // Phase 10: JWT-verified sub from Spring Security takes precedence
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = (Jwt) jwtAuth.getCredentials();
            String sub = jwt.getSubject();
            if (sub != null && !sub.isBlank()) return sub;
        }

        // Trusted internal hop (LibreChat → gateway with X-User-Id header)
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return userId.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-User");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.trim();
        }
        return "anonymous";
    }
}

