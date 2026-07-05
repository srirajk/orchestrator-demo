package ai.conduit.chat.auth;

import ai.conduit.chat.web.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessTokenServiceTest {

    @Mock
    private OAuth2AuthorizedClientManager authorizedClientManager;

    @Mock
    private OAuth2AuthorizedClientRepository authorizedClientRepository;

    @AfterEach
    void clearThreadLocals() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void currentAccessTokenConvertsRefreshFailureToUnauthorizedAndClearsStaleClient() {
        AccessTokenService service = new AccessTokenService(authorizedClientManager, authorizedClientRepository);
        Authentication authentication = new TestingAuthenticationToken("rm_carlos", "n/a");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setSession(new MockHttpSession(null, "session-1"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, "refresh token invalid", null);
        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenThrow(new ClientAuthorizationException(error, "conduit-chat"));

        assertThatThrownBy(service::currentAccessToken)
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("OIDC session expired; re-authentication required")
                .hasCauseInstanceOf(ClientAuthorizationException.class);

        verify(authorizedClientRepository).removeAuthorizedClient(
                "conduit-chat",
                authentication,
                request,
                response
        );
    }

    @Test
    void currentAccessTokenRejectsNullAuthorizedClientAsUnauthorized() {
        AccessTokenService service = new AccessTokenService(authorizedClientManager, authorizedClientRepository);
        Authentication authentication = new TestingAuthenticationToken("rm_jane", "n/a");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setSession(new MockHttpSession(null, "session-2"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(null);

        assertThatThrownBy(service::currentAccessToken)
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("No OIDC access token available; re-authentication required");
    }

    @Test
    void currentAccessTokenPassesServletRequestAndResponseSoRefreshCanBeSaved() {
        AccessTokenService service = new AccessTokenService(authorizedClientManager, authorizedClientRepository);
        Authentication authentication = new TestingAuthenticationToken("rm_jane", "n/a");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setSession(new MockHttpSession(null, "session-3"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        OAuth2AccessToken accessToken = mock(OAuth2AccessToken.class);
        when(accessToken.getTokenValue()).thenReturn("access-token");
        when(client.getAccessToken()).thenReturn(accessToken);
        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(client);

        assertThat(service.currentAccessToken()).isEqualTo("access-token");

        ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
        verify(authorizedClientManager).authorize(captor.capture());
        assertThat(captor.getValue().getAttributes())
                .containsEntry(HttpServletRequest.class.getName(), request)
                .containsEntry(HttpServletResponse.class.getName(), response);
    }
}
