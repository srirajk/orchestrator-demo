package ai.meridian.gateway.infrastructure.identity;

import ai.meridian.gateway.domain.auth.JwtAuthFilter;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Extracts the caller's user ID from the inbound HTTP request.
 *
 * <p>LibreChat forwards the authenticated user's identity as the {@code X-User-Id} header
 * when the gateway is configured as a custom endpoint. For the demo, the header can be
 * set manually (e.g. {@code X-User-Id: rm_jane}) to test different permission scenarios.
 *
 * <p>Phase 8 (M15): when {@link JwtAuthFilter} has verified a Bearer JWT, the verified
 * {@code sub} claim is used as the user ID — the header is ignored in that case, preventing
 * callers from spoofing their identity via the {@code X-User-Id} header.
 */
@Component
public class IdentityExtractor {

    public String extractUserId(HttpServletRequest request) {
        // Phase 8: JWT-verified sub takes precedence over any header
        Boolean jwtVerified = (Boolean) request.getAttribute(JwtAuthFilter.ATTR_JWT_VERIFIED);
        if (Boolean.TRUE.equals(jwtVerified)) {
            JWTClaimsSet claims = (JWTClaimsSet) request.getAttribute(JwtAuthFilter.ATTR_JWT_CLAIMS);
            if (claims != null && claims.getSubject() != null && !claims.getSubject().isBlank()) {
                return claims.getSubject();
            }
        }

        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return userId.trim();
        }
        // Also check X-Forwarded-User for reverse-proxy setups
        String forwarded = request.getHeader("X-Forwarded-User");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.trim();
        }
        return "anonymous";
    }
}
