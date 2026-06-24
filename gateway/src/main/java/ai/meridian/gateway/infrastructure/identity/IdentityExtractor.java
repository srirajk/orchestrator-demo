package ai.meridian.gateway.infrastructure.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Extracts the caller's user ID from the inbound HTTP request.
 *
 * <p>LibreChat forwards the authenticated user's identity as the {@code X-User-Id} header
 * when the gateway is configured as a custom endpoint. For the demo, the header can be
 * set manually (e.g. {@code X-User-Id: rm_jane}) to test different permission scenarios.
 *
 * <p>Phase 2 upgrade path: validate a Bearer JWT in {@code Authorization}, verify signature,
 * extract sub-claim. The rest of the pipeline only sees the userId string.
 */
@Component
public class IdentityExtractor {

    public String extractUserId(HttpServletRequest request) {
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
