package ai.conduit.chat.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * Auth entry/exit points that the SPA calls directly.
 *
 * <ul>
 *   <li>{@code GET /api/auth/login} initiates the OIDC PKCE flow by forwarding to
 *       Spring's authorization endpoint. The SPA navigates here on any 401.</li>
 *   <li>{@code GET|POST /api/auth/logout} always invalidates the session and clears the
 *       cookie, then (GET) redirects to the login flow for a clean re-login, or (POST)
 *       returns {@code {"loggedOut":true}} for the SPA. It never 500s, even if the
 *       session/token is already absent or invalid.</li>
 * </ul>
 *
 * The OIDC callback itself ({@code /api/auth/callback}) is handled by Spring
 * Security's redirection endpoint, not by a controller.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String AUTHORIZATION_REQUEST_URI = "/oauth2/authorization/conduit-chat";
    private static final String LOGIN_URI = "/api/auth/login";
    private static final String SESSION_COOKIE_NAME = "SESSION";
    private static final String REGISTRATION_ID = "conduit-chat";
    private static final String END_SESSION_ENDPOINT = "end_session_endpoint";
    public static final String LOGIN_RETURN_TO_SESSION_ATTRIBUTE = "conduit.chat.loginReturnTo";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final String postLogoutRedirectUri;

    public AuthController(
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${conduit.chat.auth.post-logout-redirect-uri:http://localhost:8099/api/auth/login}")
            String postLogoutRedirectUri) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.postLogoutRedirectUri = postLogoutRedirectUri;
    }

    @GetMapping("/login")
    public void login(@RequestParam(name = "returnTo", required = false) String returnTo,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        if (isSafeSpaPath(returnTo)) {
            request.getSession(true).setAttribute(LOGIN_RETURN_TO_SESSION_ATTRIBUTE, returnTo);
        } else {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(LOGIN_RETURN_TO_SESSION_ATTRIBUTE);
            }
        }
        response.sendRedirect(AUTHORIZATION_REQUEST_URI);
    }

    public static boolean isSafeSpaPath(String path) {
        return path != null
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.startsWith("/api/")
                && !path.startsWith("/oauth2/")
                && !path.startsWith("/login/");
    }

    /**
     * Logout accepts both GET (the sidebar sign-out link) and POST (SPA fetch). It always
     * succeeds: the session is invalidated best-effort and the session cookie is cleared even
     * when there is no session or the token is already invalid — so a stale/expired session can
     * always be cleanly logged out without a 500.
     *
     * <p>GET → 302 redirect to {@code /api/auth/login} (clean re-login). POST → JSON
     * {@code {"loggedOut":true}} for the SPA.
     */
    @RequestMapping(value = "/logout", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Boolean>> logout(HttpServletRequest request, HttpServletResponse response) {
        URI redirect = RequestMethod.GET.name().equalsIgnoreCase(request.getMethod())
                ? oidcLogoutRedirectUri()
                : null;

        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // Session was already invalidated concurrently — nothing to do.
            }
        }
        SecurityContextHolder.clearContext();

        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        if (RequestMethod.GET.name().equalsIgnoreCase(request.getMethod())) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(redirect != null ? redirect : URI.create(LOGIN_URI))
                    .build();
        }
        return ResponseEntity.ok(Map.of("loggedOut", true));
    }

    private URI oidcLogoutRedirectUri() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof OidcUser oidcUser) || oidcUser.getIdToken() == null) {
            return null;
        }

        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        if (registration == null) {
            return null;
        }

        Object endpoint = registration.getProviderDetails()
                .getConfigurationMetadata()
                .get(END_SESSION_ENDPOINT);
        if (!(endpoint instanceof String endSessionEndpoint) || endSessionEndpoint.isBlank()) {
            return null;
        }

        return UriComponentsBuilder.fromUriString(endSessionEndpoint)
                .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue())
                .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
                .build()
                .encode()
                .toUri();
    }
}
