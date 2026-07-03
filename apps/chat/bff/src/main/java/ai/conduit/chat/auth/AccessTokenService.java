package ai.conduit.chat.auth;

import ai.conduit.chat.web.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Retrieves the signed-in user's OIDC <b>access token</b> to forward to the Conduit
 * gateway as a Bearer credential. This is the whole point of the BFF: the gateway
 * enforces <em>that user's</em> entitlements against the token (which carries
 * {@code aud=conduit-gateway}). The id token is never forwarded.
 *
 * <p>The token is resolved through an {@link OAuth2AuthorizedClientManager} configured with a
 * {@code refresh_token} provider, so an <b>expired</b> access token is transparently refreshed
 * using the stored refresh token (and the refreshed client is written back to the
 * session-backed {@code OAuth2AuthorizedClientRepository}) instead of forwarding a dead token
 * and forcing a full re-login. Resolution is done on the request thread, where the request +
 * session context (and the servlet response needed to persist a refreshed token) are available.
 *
 * <p>If there is no authenticated principal, no request context, or no authorized client / access
 * token at all (e.g. the session was lost and no refresh token is available), an
 * {@link UnauthorizedException} (→ 401) is thrown — never a {@link ai.conduit.chat.web.GatewayException}
 * (502) — so the SPA's 401 handler drives a clean re-login rather than surfacing a gateway error.
 */
@Service
public class AccessTokenService {

    private static final String REGISTRATION_ID = "conduit-chat";

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public AccessTokenService(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    /**
     * @return the current user's OIDC access token value, refreshing it first if it has expired
     *         and a refresh token is available.
     * @throws UnauthorizedException if there is no authenticated principal, no request context, or
     *                               no authorized client / access token available (→ HTTP 401).
     */
    public String currentAccessToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new UnauthorizedException("No authenticated principal for access-token lookup");
        }
        ServletRequestAttributes attributes = currentAttributes();
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();

        // Resolve (and, if expired, refresh) through the manager. Passing the servlet request +
        // response lets the manager persist a refreshed authorized client back to the session store.
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(REGISTRATION_ID)
                .principal(authentication)
                .attributes(attrs -> {
                    attrs.put(HttpServletRequest.class.getName(), request);
                    if (response != null) {
                        attrs.put(HttpServletResponse.class.getName(), response);
                    }
                })
                .build();

        OAuth2AuthorizedClient client;
        try {
            client = authorizedClientManager.authorize(authorizeRequest);
        } catch (OAuth2AuthorizationException ex) {
            throw new UnauthorizedException("OIDC session expired; re-authentication required", ex);
        }
        if (client == null || client.getAccessToken() == null) {
            throw new UnauthorizedException("No OIDC access token available; re-authentication required");
        }
        return client.getAccessToken().getTokenValue();
    }

    private static ServletRequestAttributes currentAttributes() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes;
        }
        throw new UnauthorizedException("No request context available for access-token lookup");
    }
}
