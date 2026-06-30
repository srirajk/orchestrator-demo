package com.openwolf.iam.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Enriches OIDC access tokens with mandated claims ({@code tenant_id}, {@code roles},
 * {@code permissions}, {@code segments}, {@code classification}).
 *
 * <p>The actual claim-building lives in {@link OidcClaimEnricher}, a separate
 * {@code @Transactional} bean — this customizer runs as a lambda during token encoding with
 * no active persistence session, so the lazy {@code Principal.roles} collection must be loaded
 * via that transactional call (otherwise the authorization_code / SSO flow throws
 * {@code LazyInitializationException}).
 */
@Configuration
public class JwtClaimsCustomizer {

    private static final Logger log = LoggerFactory.getLogger(JwtClaimsCustomizer.class);

    private final OidcClaimEnricher enricher;

    public JwtClaimsCustomizer(OidcClaimEnricher enricher) {
        this.enricher = enricher;
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            String subject = context.getPrincipal().getName();
            if (subject == null) {
                log.warn("JWT customizer: no subject in principal — skipping enrichment");
                return;
            }
            String tokenValue = context.getTokenType().getValue();
            if (OAuth2TokenType.ACCESS_TOKEN.getValue().equals(tokenValue)) {
                // Access token: authorization claims (roles, permissions, segments, …)
                enricher.enrich(subject).forEach((k, v) -> context.getClaims().claim(k, v));
            } else if (OidcParameterNames.ID_TOKEN.equals(tokenValue)) {
                // ID token: OIDC identity claims (email, name, preferred_username) so the
                // userinfo endpoint and SSO consumers (LibreChat) can create the user. Without
                // these the id_token / userinfo carries only `sub` and LibreChat login fails.
                enricher.enrichIdToken(subject).forEach((k, v) -> context.getClaims().claim(k, v));
            }
        };
    }
}
