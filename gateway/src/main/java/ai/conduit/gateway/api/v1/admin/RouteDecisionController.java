package ai.conduit.gateway.api.v1.admin;

import ai.conduit.gateway.api.v1.chat.dto.ChatRequest;
import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.RequestContext;
import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.domain.chat.ChatService;
import ai.conduit.gateway.domain.chat.RouteDecision;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Piece-6 production-path routing DECISION endpoint (routing spec V2).
 *
 * <p>{@code POST /debug/route} runs the REAL shared pre-routing preparation for the posted conversation
 * — {@code RoutePreparer → resolveContextual → buildRequestedPlan → per-group structural authz} — via
 * {@link ChatService#decideRoute} and returns the full {@link RouteDecision} projection WITHOUT invoking
 * any agent. It is the release-gate surface the goal-pick harness drives with each row's own persona
 * token, so its decision is scored against the exact production routing the chat path would take (the
 * response is stamped {@link RouteDecision#PRODUCTION_PATH}).
 *
 * <p><b>Not a prod attack surface.</b> The bean only exists when {@code
 * conduit.debug.route-decision.enabled=true} (off unless a dev/demo profile turns it on), and the
 * endpoint requires an AUTHENTICATED caller: identity + coverage are taken from the caller's OWN verified
 * bearer token (a persona sees only its own coverage, RESOLVE stays principal-agnostic). It reads only —
 * it invokes no agent and mutates nothing.
 *
 * <p><b>World-B.</b> This class authors no domain literal: it forwards an opaque request DTO and returns
 * an opaque diagnostic. Unlike the admin-gated {@code /debug/resolve}, this path is authorized as
 * {@code authenticated()} (see {@code SecurityConfig}) so a non-admin persona token is accepted.
 */
@RestController
@RequestMapping("/debug")
@ConditionalOnProperty(name = "conduit.debug.route-decision.enabled", havingValue = "true")
public class RouteDecisionController {

    private final ChatService chatService;

    public RouteDecisionController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/route")
    public ResponseEntity<RouteDecision> route(@RequestBody ChatRequest request) {
        Principal principal = RequestContext.getPrincipal();
        // A2: the tenant context, captured on the servlet thread and threaded explicitly into the
        // decision (the filter already validated it fail-closed before this controller ran).
        TenantExecutionContext tenant = RequestContext.getTenant();
        String callerToken = bearerToken();
        // Defence in depth: the security rule already requires a valid token, but never run the
        // decision for an unauthenticated caller — coverage/entitlement must key on a real principal.
        if (principal == null || callerToken == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(chatService.decideRoute(request, principal, tenant, callerToken));
    }

    /** The caller's verified bearer token off the servlet-thread {@link SecurityContextHolder}. */
    private static String bearerToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }
        return null;
    }
}
