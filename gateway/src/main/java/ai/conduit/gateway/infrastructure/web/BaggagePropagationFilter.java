package ai.conduit.gateway.infrastructure.web;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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

/**
 * Propagates convId and userId as W3C Baggage on every inbound request.
 *
 * <p>By attaching both values to the OTel {@link Baggage} and activating the enriched
 * {@link Context}, the OTel SDK automatically injects a
 * {@code baggage: convId=abc,userId=rm_jane} header alongside {@code traceparent} on
 * every outbound HTTP call made by {@link ai.conduit.gateway.adapter.http.HttpAdapter}.
 * Langfuse and downstream mock agents receive both identifiers without any additional
 * code in the adapters.
 *
 * <p>Order 2 — runs after:
 * <ul>
 *   <li>Spring Security's {@code FilterChainProxy} (order -100), which validates the
 *       Bearer JWT and populates the {@link SecurityContextHolder}.</li>
 *   <li>{@link ai.conduit.gateway.infrastructure.telemetry.RequestCorrelationFilter}
 *       (order 1), which populates MDC with {@code conversationId} and {@code userId}.</li>
 * </ul>
 * Running after both means we can read from a fully-populated SecurityContext and MDC,
 * keeping the fallback logic simple.
 */
@Component
@Order(2)
public class BaggagePropagationFilter extends OncePerRequestFilter {

    private static final String HEADER_CONVERSATION_ID = "X-Conversation-Id";

    // OTel Baggage / MDC keys used by this filter (short form; Promtail extracts these)
    private static final String BAGGAGE_CONV_ID = "convId";
    private static final String BAGGAGE_USER_ID = "userId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        // ── 1. Resolve convId ─────────────────────────────────────────────────
        // Primary: X-Conversation-Id header forwarded by LibreChat.
        // Fallback: MDC value set by RequestCorrelationFilter (key "conversationId").
        String convId = request.getHeader(HEADER_CONVERSATION_ID);
        if (convId == null || convId.isBlank()) {
            String mdcConv = MDC.get("conversationId");
            convId = (mdcConv != null && !mdcConv.isBlank()) ? mdcConv : "";
        }

        // ── 2. Resolve userId ────────────────────────────────────────────────
        // Primary: JWT sub from Spring SecurityContext (set by BearerTokenAuthenticationFilter).
        // Fallback: MDC value set by RequestCorrelationFilter (key "userId").
        String userId = resolveUserIdFromSecurityContext();
        if (userId == null || userId.isBlank()) {
            String mdcUser = MDC.get("userId");
            userId = (mdcUser != null && !mdcUser.isBlank()) ? mdcUser : "";
        }

        // ── 3. Build OTel Baggage and activate the enriched context ──────────
        // OTel's W3C Baggage propagator will serialise these as the baggage header
        // on every outbound HTTP call that runs within this context scope.
        Baggage baggage = Baggage.builder()
                .put(BAGGAGE_CONV_ID, convId)
                .put(BAGGAGE_USER_ID, userId)
                .build();

        Context enrichedCtx = Context.current().with(baggage);

        // ── 4. Bridge OTel Baggage → MDC for local log enrichment ────────────
        // Logback/Log4j2 structured appenders pick these up so every log line for
        // this request carries convId and userId without explicit argument passing.
        MDC.put(BAGGAGE_CONV_ID, convId);
        MDC.put(BAGGAGE_USER_ID, userId);

        try (Scope ignored = enrichedCtx.makeCurrent()) {
            chain.doFilter(request, response);
        } finally {
            // Remove only the keys this filter added; RequestCorrelationFilter.MDC.clear()
            // handles the rest when the outermost filter exits.
            MDC.remove(BAGGAGE_CONV_ID);
            MDC.remove(BAGGAGE_USER_ID);
        }
    }

    /**
     * Extracts the JWT subject from the active Spring Security context.
     * Returns {@code null} if no JWT authentication is present (anonymous request).
     */
    private static String resolveUserIdFromSecurityContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = (Jwt) jwtAuth.getCredentials();
            return jwt.getSubject();
        }
        // Non-JWT authentication (e.g. anonymous) — caller falls back to MDC
        if (auth != null
                && auth.getName() != null
                && !auth.getName().isBlank()
                && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return null;
    }
}
