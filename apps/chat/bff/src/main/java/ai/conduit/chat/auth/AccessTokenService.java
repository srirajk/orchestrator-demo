package ai.conduit.chat.auth;

import ai.conduit.chat.web.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
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
 * <p>The authorized client is resolved from the request/session-scoped
 * {@link OAuth2AuthorizedClientRepository} (session-backed via Spring Session on Mongo),
 * so the token survives container restarts — not from an in-memory store. Resolution is
 * done on the request thread (the controller reads the token before handing off to the
 * streaming body), where the request + session context is available.
 */
@Service
public class AccessTokenService {

    private static final String REGISTRATION_ID = "conduit-chat";

    private final OAuth2AuthorizedClientRepository authorizedClientRepository;

    public AccessTokenService(OAuth2AuthorizedClientRepository authorizedClientRepository) {
        this.authorizedClientRepository = authorizedClientRepository;
    }

    /**
     * @return the current user's OIDC access token value.
     * @throws GatewayException if there is no authenticated principal, no request context,
     *                          or no authorized client / access token available (e.g. the
     *                          session was lost) — surfaced rather than silently sending an
     *                          unauthenticated call.
     */
    public String currentAccessToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new GatewayException("No authenticated principal for access-token lookup");
        }
        HttpServletRequest request = currentRequest();
        OAuth2AuthorizedClient client =
                authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request);
        if (client == null || client.getAccessToken() == null) {
            throw new GatewayException("No OIDC access token available; re-authentication required");
        }
        return client.getAccessToken().getTokenValue();
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        throw new GatewayException("No request context available for access-token lookup");
    }
}
