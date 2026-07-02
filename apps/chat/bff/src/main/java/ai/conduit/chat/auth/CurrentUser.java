package ai.conduit.chat.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the authenticated principal from the security context and maps the OIDC
 * claims onto the app's user model.
 *
 * <p>The stable per-user key is the OIDC {@code sub}, used to scope every repository
 * query for per-user isolation. Roles are read from the {@code roles} claim when the
 * IdP includes it (empty otherwise), matching the reference Node BFF.
 */
@Component
public class CurrentUser {

    /** @return the current user's stable id ({@code sub}). */
    public String id() {
        return oidcUser().getSubject();
    }

    /** @return the current user's public profile for {@code GET /api/me}. */
    public UserDto toDto() {
        OidcUser user = oidcUser();
        String sub = user.getSubject();
        String username = firstNonBlank(
                user.getPreferredUsername(),
                user.getFullName(),
                sub);
        String email = user.getEmail() != null ? user.getEmail() : "";
        return new UserDto(sub, username, email, roles(user));
    }

    private OidcUser oidcUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OidcUser oidc) {
            return oidc;
        }
        // The security filter chain guarantees authentication on /api/**; this is defensive.
        throw new IllegalStateException("No authenticated OIDC principal in context");
    }

    @SuppressWarnings("unchecked")
    private static List<String> roles(OidcUser user) {
        Object claim = user.getClaims().get("roles");
        if (claim instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
