package ai.conduit.chat.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void oauthClientAuthorizationFailureMapsToUnauthorized() {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, "refresh token invalid", null);

        ResponseEntity<ErrorResponse> response = handler.handleOAuth2Authorization(
                new ClientAuthorizationException(error, "conduit-chat"),
                new MockHttpServletResponse()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("Unauthorized");
        assertThat(response.getBody().message()).isEqualTo("OIDC session expired; re-authentication required");
    }

    @Test
    void oauthClientAuthorizationFailureAfterCommitDoesNotWriteAnotherBody() throws Exception {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, "refresh token invalid", null);
        MockHttpServletResponse committed = new MockHttpServletResponse();
        committed.getWriter().write("already streaming");
        committed.flushBuffer();

        ResponseEntity<ErrorResponse> response = handler.handleOAuth2Authorization(
                new ClientAuthorizationException(error, "conduit-chat"),
                committed
        );

        assertThat(response).isNull();
    }
}
