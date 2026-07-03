package com.openwolf.iam.auth;

import com.openwolf.iam.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtClaimsCustomizerTest {

    @Test
    void accessTokenAddsConfiguredGatewayAudience() {
        CapturingEnricher enricher = new CapturingEnricher(
                Map.of(
                        "roles", List.of("relationship_manager"),
                        "segments", List.of("wealth"),
                        "admin_domains", List.of(),
                        "clearance", 2,
                        "tenant_id", "default"),
                Map.of());

        CapturingAuditService audit = new CapturingAuditService();
        OAuth2TokenCustomizer<JwtEncodingContext> customizer =
                new JwtClaimsCustomizer(enricher, audit, "conduit-gateway,secondary-api", "conduit-chat")
                        .jwtTokenCustomizer();
        JwtEncodingContext context = contextFor(OAuth2TokenType.ACCESS_TOKEN, "test-client");

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getAudience()).containsExactly("conduit-gateway", "secondary-api");
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("relationship_manager");
        assertThat(claims.getClaimAsStringList("segments")).containsExactly("wealth");
        assertThat(claims.getClaimAsStringList("admin_domains")).isEmpty();
        assertThat((Integer) claims.getClaim("clearance")).isEqualTo(2);
        assertThat(enricher.enrichSubject).isEqualTo("rm_jane");
        assertThat(enricher.enrichIdTokenSubject).isNull();
        // Non-chat client → no chat_access audit event.
        assertThat(audit.actions).isEmpty();
    }

    @Test
    void chatAccessAuditEmittedForConduitChatLogin() {
        CapturingEnricher enricher = new CapturingEnricher(
                Map.of("roles", List.of("chat_user"), "tenant_id", "default"), Map.of());
        CapturingAuditService audit = new CapturingAuditService();

        OAuth2TokenCustomizer<JwtEncodingContext> customizer =
                new JwtClaimsCustomizer(enricher, audit, "conduit-gateway", "conduit-chat")
                        .jwtTokenCustomizer();
        // A fresh authorization_code login for the conduit-chat client → one chat_access event.
        customizer.customize(contextFor(OAuth2TokenType.ACCESS_TOKEN, "conduit-chat"));

        assertThat(audit.actions).containsExactly("chat_access");
        assertThat(audit.actorIds).containsExactly("rm_jane");
        assertThat(audit.clientIds).containsExactly("conduit-chat");
    }

    @Test
    void idTokenDoesNotReceiveGatewayAudience() {
        CapturingEnricher enricher = new CapturingEnricher(
                Map.of(),
                Map.of(
                        "email", "rm.jane@meridian.local",
                        "name", "Jane Kowalski"));

        OAuth2TokenCustomizer<JwtEncodingContext> customizer =
                new JwtClaimsCustomizer(enricher, new CapturingAuditService(), "conduit-gateway", "conduit-chat")
                        .jwtTokenCustomizer();
        JwtEncodingContext context = contextFor(new OAuth2TokenType(OidcParameterNames.ID_TOKEN), "conduit-chat");

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getAudience()).isNullOrEmpty();
        assertThat(claims.getClaimAsString("email")).isEqualTo("rm.jane@meridian.local");
        assertThat(enricher.enrichSubject).isNull();
        assertThat(enricher.enrichIdTokenSubject).isEqualTo("rm_jane");
    }

    private JwtEncodingContext contextFor(OAuth2TokenType tokenType, String clientId) {
        return JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder())
                .registeredClient(RegisteredClient.withId("test-client-id")
                        .clientId(clientId)
                        .clientSecret("secret")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("http://localhost/callback")
                        .scope(OidcScopes.OPENID)
                        .build())
                .principal(new UsernamePasswordAuthenticationToken("rm_jane", "password"))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of(OidcScopes.OPENID))
                .tokenType(tokenType)
                .build();
    }

    /** Captures {@code logAccess} calls so the chat_access audit emit can be asserted. */
    private static final class CapturingAuditService extends AuditService {
        private final List<String> actions = new java.util.ArrayList<>();
        private final List<String> actorIds = new java.util.ArrayList<>();
        private final List<String> clientIds = new java.util.ArrayList<>();

        private CapturingAuditService() {
            super(null, null);
        }

        @Override
        public void logAccess(String actorId, String clientId, String action,
                              String resourceType, String resourceId, String sourceIp) {
            actorIds.add(actorId);
            clientIds.add(clientId);
            actions.add(action);
        }
    }

    private static final class CapturingEnricher extends OidcClaimEnricher {
        private final Map<String, Object> accessClaims;
        private final Map<String, Object> idClaims;
        private String enrichSubject;
        private String enrichIdTokenSubject;

        private CapturingEnricher(Map<String, Object> accessClaims, Map<String, Object> idClaims) {
            super(null, null, "default");
            this.accessClaims = accessClaims;
            this.idClaims = idClaims;
        }

        @Override
        public Map<String, Object> enrich(String subject) {
            this.enrichSubject = subject;
            return accessClaims;
        }

        @Override
        public Map<String, Object> enrichIdToken(String subject) {
            this.enrichIdTokenSubject = subject;
            return idClaims;
        }
    }
}
