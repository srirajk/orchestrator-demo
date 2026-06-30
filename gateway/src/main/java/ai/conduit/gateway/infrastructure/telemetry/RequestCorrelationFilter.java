package ai.conduit.gateway.infrastructure.telemetry;

import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Sets MDC context keys on every inbound request so all log lines carry:
 *   requestId      — a fresh UUID per request (correlation across log lines for one call)
 *   conversationId — forwarded by LibreChat; ties gateway logs to the chat turn
 *   userId         — JWT sub (when present) or X-User-Id header fallback
 *
 * MDC is cleared after the request completes so there is no thread-local leakage
 * across requests — important with virtual threads (one vthread per request, but
 * MDC values persist until explicitly cleared).
 *
 * Phase 10: reads the authenticated principal from {@link SecurityContextHolder}
 * (set by Spring Security's BearerTokenAuthenticationFilter before this filter runs)
 * and stores it in {@link RequestContext} so the controller can capture it on the
 * servlet thread before handing off to the async pipeline.
 */
@Component
@Order(1)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String MDC_REQUEST_ID       = "requestId";
    private static final String MDC_CONVERSATION_ID  = "conversationId";
    private static final String MDC_USER_ID          = "userId";

    // Headers forwarded by LibreChat (configurable in librechat.yaml)
    private static final String HEADER_CONVERSATION_ID = "X-Conversation-Id";
    private static final String HEADER_USER_ID         = "X-User-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String requestId      = UUID.randomUUID().toString();
        String conversationId = coalesce(request.getHeader(HEADER_CONVERSATION_ID), "");

        // Phase 10: read authenticated principal from Spring Security (runs before this filter).
        // BearerTokenAuthenticationFilter sets the JwtAuthenticationToken for valid Bearer JWTs.
        String userId;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = (Jwt) jwtAuth.getCredentials();
            userId = jwt.getSubject() != null ? jwt.getSubject() : "anonymous";
            RequestContext.setPrincipal(Principal.fromSpringJwt(jwt));
        } else {
            userId = coalesce(request.getHeader(HEADER_USER_ID), "anonymous");
        }

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CONVERSATION_ID, conversationId);
        MDC.put(MDC_USER_ID, userId);

        // Echo back so callers can correlate their logs with ours
        response.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
            RequestContext.clear();
        }
    }

    private static String coalesce(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
