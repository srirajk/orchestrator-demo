package ai.conduit.chat.auth;

import ai.conduit.chat.web.GatewayException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.stereotype.Service;

/**
 * Retrieves the signed-in user's OIDC <b>access token</b> to forward to the Conduit
 * gateway as a Bearer credential. This is the whole point of the BFF: the gateway
 * enforces <em>that user's</em> entitlements against the token (which carries
 * {@code aud=conduit-gateway}). The id token is never forwarded.
 */
@Service
public class AccessTokenService {

    private static final String REGISTRATION_ID = "conduit-chat";

    private final OAuth2AuthorizedClientService authorizedClientService;

    public AccessTokenService(OAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    /**
     * @return the current user's OIDC access token value.
     * @throws GatewayException if no authorized client / access token is available
     *                          (e.g. the token store was lost across a restart) —
     *                          surfaced rather than silently sending an unauthenticated call.
     */
    public String currentAccessToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new GatewayException("No authenticated principal for access-token lookup");
        }
        OAuth2AuthorizedClient client =
                authorizedClientService.loadAuthorizedClient(REGISTRATION_ID, authentication.getName());
        if (client == null || client.getAccessToken() == null) {
            throw new GatewayException("No OIDC access token available; re-authentication required");
        }
        return client.getAccessToken().getTokenValue();
    }
}
