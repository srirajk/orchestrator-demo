package ai.conduit.chat.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Auth entry/exit points that the SPA calls directly.
 *
 * <ul>
 *   <li>{@code GET /api/auth/login} initiates the OIDC PKCE flow by forwarding to
 *       Spring's authorization endpoint. The SPA navigates here on any 401.</li>
 *   <li>{@code POST /api/auth/logout} invalidates the session and clears the cookie.</li>
 * </ul>
 *
 * The OIDC callback itself ({@code /api/auth/callback}) is handled by Spring
 * Security's redirection endpoint, not by a controller.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String AUTHORIZATION_REQUEST_URI = "/oauth2/authorization/conduit-chat";
    private static final String SESSION_COOKIE_NAME = "SESSION";

    @GetMapping("/login")
    public void login(HttpServletResponse response) throws IOException {
        response.sendRedirect(AUTHORIZATION_REQUEST_URI);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return Map.of("ok", true);
    }
}
