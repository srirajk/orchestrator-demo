package com.openwolf.iam.auth;

import com.openwolf.iam.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

/**
 * Enriches OIDC access tokens with mandated claims ({@code tenant_id}, {@code roles},
 * {@code permissions}, {@code segments} — a per-segment classification MAP —,
 * {@code classification}). The legacy numeric {@code clearance} claim is no longer emitted.
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
    private final AuditService auditService;
    private final String gatewayAudienceRaw;
    private final String chatClientId;

    public JwtClaimsCustomizer(
            OidcClaimEnricher enricher,
            AuditService auditService,
            @Value("${iam.oauth2.gateway.audience:conduit-gateway}") String gatewayAudienceRaw,
            @Value("${iam.oauth2.conduit-chat.client-id:conduit-chat}") String chatClientId) {
        this.enricher = enricher;
        this.auditService = auditService;
        this.gatewayAudienceRaw = gatewayAudienceRaw;
        this.chatClientId = chatClientId;
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
                List<String> audiences = gatewayAudiences();
                if (!audiences.isEmpty()) {
                    context.getClaims().audience(audiences);
                }
                enricher.enrich(subject).forEach((k, v) -> context.getClaims().claim(k, v));

                // AUDIT (additive around auth): record who entered Conduit Chat. We are the OIDC
                // provider, so a fresh authorization_code exchange for the chat client is the
                // authoritative "who entered" signal. Tagged by client so chat-access is distinct
                // from admin-console access. Silent renewals (refresh_token) are not re-logged.
                // This never alters the auth decision and never throws.
                auditChatAccessIfApplicable(context, subject);
            } else if (OidcParameterNames.ID_TOKEN.equals(tokenValue)) {
                // ID token: OIDC identity claims (email, name, preferred_username) so the
                // userinfo endpoint and SSO consumers (LibreChat) can create the user. Without
                // these the id_token / userinfo carries only `sub` and LibreChat login fails.
                enricher.enrichIdToken(subject).forEach((k, v) -> context.getClaims().claim(k, v));
            }
        };
    }

    private List<String> gatewayAudiences() {
        return Arrays.stream(gatewayAudienceRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * Emits a {@code chat_access} audit event when — and only when — the access token being
     * minted is a fresh {@code authorization_code} login for the Conduit Chat client. Refresh
     * grants (silent renewal) and every other client (admin console, gateway M2M, LibreChat)
     * are skipped. Best-effort and non-throwing: audit must never break token issuance.
     */
    private void auditChatAccessIfApplicable(JwtEncodingContext context, String subject) {
        try {
            if (context.getRegisteredClient() == null) return;
            String clientId = context.getRegisteredClient().getClientId();
            if (!chatClientId.equals(clientId)) return;
            // Only a brand-new login (authorization_code), not a refresh_token renewal.
            if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) return;

            auditService.logAccess(subject, clientId, "chat_access", "client_application", clientId, currentSourceIp());
        } catch (Exception ex) {
            log.warn("chat_access audit skipped for sub={}: {}", subject, ex.getMessage());
        }
    }

    /** Best-effort source IP of the current token request; null when no request context. */
    private String currentSourceIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest req = servletAttrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        }
        return null;
    }
}
