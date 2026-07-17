package ai.conduit.gateway.infrastructure.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Axiom A1.5 — no header-identity side-channel. Identity is derived ONLY from the verified JWT
 * {@code sub}; a caller-supplied {@code X-User-Id} (or any identity header) is never honored. A
 * request bearing {@code X-User-Id: victim} with no JWT resolves to {@code anonymous}, never to the
 * victim.
 */
class XUserIdFenceTest {

    private final IdentityExtractor extractor = new IdentityExtractor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void headerIdentityNeverHonored() {
        SecurityContextHolder.clearContext(); // no authentication — anonymous request

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "victim");

        String resolved = extractor.extractUserId(request);

        assertThat(resolved).isEqualTo("anonymous");
        assertThat(resolved).isNotEqualTo("victim");
    }

    @Test
    void jwtSubjectWinsAndHeaderIsIgnored() {
        // A verified JWT is present for rm_jane; an attacker-supplied X-User-Id must be ignored.
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("rm_jane")
                .claim("tenant_id", "default")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "victim");

        assertThat(extractor.extractUserId(request)).isEqualTo("rm_jane");
    }
}
