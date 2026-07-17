package ai.conduit.gateway.infrastructure.telemetry;

import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.RequestContext;
import ai.conduit.gateway.domain.auth.TenantContextResolver;
import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   userId         — verified JWT sub when present, else "anonymous"
 *
 * MDC is cleared after the request completes so there is no thread-local leakage
 * across requests — important with virtual threads (one vthread per request, but
 * MDC values persist until explicitly cleared).
 *
 * <p>Phase 10 / A2: reads the authenticated principal from {@link SecurityContextHolder}
 * (set by Spring Security's BearerTokenAuthenticationFilter before this filter runs), resolves the
 * request's {@link TenantExecutionContext} adjacent to it via {@link TenantContextResolver}, and
 * stores BOTH in {@link RequestContext} so the controller can capture them on the servlet thread
 * before handing off to the async pipeline.
 *
 * <p><b>Fail closed before the controller (before any Redis/Cerbos/coverage/LLM I/O):</b> a verified
 * principal whose token carries no usable {@code tenant_id} is rejected 401; a token asserting a tenant
 * that is not in the provisioned snapshot is rejected 403; a request that arrives before the initial
 * snapshot loads is rejected 503. The single-tenant demo always carries {@code tenant_id=default}, which
 * is always provisioned, so this path only fires for a genuinely missing/unknown tenant.
 */
@Component
@Order(1)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    private static final String MDC_REQUEST_ID       = "requestId";
    private static final String MDC_CONVERSATION_ID  = "conversationId";
    private static final String MDC_USER_ID          = "userId";
    private static final String MDC_TENANT_ID        = "tenantId";

    // Conversation correlation header forwarded by the chat client (configurable in librechat.yaml)
    private static final String HEADER_CONVERSATION_ID = "X-Conversation-Id";

    private final TenantContextResolver tenantContextResolver;

    public RequestCorrelationFilter(TenantContextResolver tenantContextResolver) {
        this.tenantContextResolver = tenantContextResolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String requestId      = UUID.randomUUID().toString();
        String conversationId = coalesce(request.getHeader(HEADER_CONVERSATION_ID), "");

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CONVERSATION_ID, conversationId);
        // Echo back so callers can correlate their logs with ours
        response.setHeader("X-Request-Id", requestId);

        // Read the authenticated principal from Spring Security (runs before this filter).
        // BearerTokenAuthenticationFilter sets the JwtAuthenticationToken for valid Bearer JWTs.
        // Identity comes ONLY from the verified JWT — the X-User-Id trusted-hop was removed
        // (it allowed identity spoofing). No JWT ⇒ anonymous (no data access downstream).
        String userId = "anonymous";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = (Jwt) jwtAuth.getCredentials();
            userId = jwt.getSubject() != null ? jwt.getSubject() : "anonymous";
            MDC.put(MDC_USER_ID, userId);
            RequestContext.setPrincipal(Principal.fromSpringJwt(jwt));

            // A2: resolve the tenant ONCE, here, adjacent to the principal — before the controller and
            // before any outbound I/O. A missing/unknown/unavailable tenant fails closed right here.
            try {
                TenantExecutionContext tenant = tenantContextResolver.resolve(jwt);
                RequestContext.setTenant(tenant);
                MDC.put(MDC_TENANT_ID, tenant.tenantId());
            } catch (TenantContextResolver.MissingTenantClaimException e) {
                writeError(response, 401, "invalid_token", e.getMessage());
                return;
            } catch (TenantContextResolver.TenantNotProvisionedException e) {
                log.warn("Denying request for unprovisioned tenant (user={}): {}", userId, e.getMessage());
                writeError(response, 403, "tenant_not_provisioned", e.getMessage());
                return;
            } catch (TenantContextResolver.TenantDirectoryUnavailableException e) {
                writeError(response, 503, "tenant_directory_unavailable", e.getMessage());
                return;
            }
        } else {
            MDC.put(MDC_USER_ID, userId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
            RequestContext.clear();
        }
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        // Cleared here because the try/finally that normally clears MDC/RequestContext is not entered
        // on this fail-closed short-circuit.
        try {
            response.setStatus(status);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\""
                    + message.replace("\"", "'") + "\"}}");
        } finally {
            MDC.clear();
            RequestContext.clear();
        }
    }

    private static String coalesce(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
