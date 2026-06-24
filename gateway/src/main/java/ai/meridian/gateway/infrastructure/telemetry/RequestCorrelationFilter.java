package ai.meridian.gateway.infrastructure.telemetry;

import ai.meridian.gateway.domain.auth.JwtAuthFilter;
import ai.meridian.gateway.domain.auth.Principal;
import ai.meridian.gateway.domain.auth.RequestContext;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Sets MDC context keys on every inbound request so all log lines carry:
 *   requestId      — a fresh UUID per request (correlation across log lines for one call)
 *   conversationId — forwarded by LibreChat; ties gateway logs to the chat turn
 *   userId         — forwarded by LibreChat; drives entitlement checks in Phase 5
 *
 * MDC is cleared after the request completes so there is no thread-local leakage
 * across requests — important with virtual threads (one vthread per request, but
 * MDC values persist until explicitly cleared).
 *
 * Phase 8 (M15): when {@link JwtAuthFilter} (Order 0) has verified a Bearer JWT,
 * this filter builds a {@link Principal} from the verified claims and stores it in
 * {@link RequestContext} so downstream services can bypass the Redis lookup. The
 * userId MDC key is set to the JWT {@code sub} claim in that case.
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

        // Phase 8: prefer verified JWT sub over the X-User-Id header
        String userId;
        Boolean jwtVerified = (Boolean) request.getAttribute(JwtAuthFilter.ATTR_JWT_VERIFIED);
        if (Boolean.TRUE.equals(jwtVerified)) {
            JWTClaimsSet claims = (JWTClaimsSet) request.getAttribute(JwtAuthFilter.ATTR_JWT_CLAIMS);
            if (claims != null) {
                userId = claims.getSubject() != null ? claims.getSubject() : "anonymous";
                // Store the JWT-derived principal so PrincipalStore can short-circuit
                RequestContext.setPrincipal(Principal.fromJwtClaims(claims));
            } else {
                userId = coalesce(request.getHeader(HEADER_USER_ID), "anonymous");
            }
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
