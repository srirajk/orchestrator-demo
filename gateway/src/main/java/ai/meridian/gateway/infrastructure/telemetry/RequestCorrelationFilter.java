package ai.meridian.gateway.infrastructure.telemetry;

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
        String userId         = coalesce(request.getHeader(HEADER_USER_ID), "anonymous");

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CONVERSATION_ID, conversationId);
        MDC.put(MDC_USER_ID, userId);

        // Echo back so callers can correlate their logs with ours
        response.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static String coalesce(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
