package ai.conduit.gateway.insights;

import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.RequestContext;
import ai.conduit.gateway.insights.model.Board;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Conduit Insights API — native, admin-gated analytics inside the gateway.
 *
 * <pre>GET /v1/insights/boards/{boardId}  → {"panels":[{id,title,type,unit,status,value|series|rows}]}</pre>
 *
 * <p>Cleanly bounded module reusing the gateway's JWT auth, virtual threads and config
 * (INSIGHTS-SPEC §Architecture). Authorization runs through {@link InsightsAuthorizer} (the same
 * Cerbos/ABAC PDP as chat): a {@code chat_user} → 403, an admin → 200. Panels for the board fan
 * out concurrently on virtual threads inside {@link InsightsExecutor}, deadline-bounded and
 * partial-tolerant.
 */
@RestController
@RequestMapping("/v1/insights")
public class InsightsController {

    private static final Logger log = LoggerFactory.getLogger(InsightsController.class);

    private final BoardCatalog catalog;
    private final InsightsExecutor executor;
    private final InsightsAuthorizer authorizer;

    public InsightsController(BoardCatalog catalog, InsightsExecutor executor, InsightsAuthorizer authorizer) {
        this.catalog = catalog;
        this.executor = executor;
        this.authorizer = authorizer;
    }

    @GetMapping("/boards/{boardId}")
    public ResponseEntity<?> board(@PathVariable int boardId,
                                   @RequestParam(name = "range", required = false) String rangeParam,
                                   Authentication auth) {
        Principal principal = resolvePrincipal(auth);
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        if (!authorizer.canRead(principal)) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden", "reason", "insights requires admin"));
        }
        if (!catalog.exists(boardId)) {
            return ResponseEntity.status(404).body(Map.of("error", "not_found", "reason", "no board " + boardId));
        }
        // Fail-safe: absent/blank/unknown range → the 24h default (never a 400).
        Range range = Range.from(rangeParam);
        List<PanelSpec> specs = catalog.panelsFor(boardId, range);
        Board board = executor.render(boardId, specs);
        log.debug("Insights board {} (range={}) served to principal={}", boardId, range.key(), principal.id());
        return ResponseEntity.ok(board);
    }

    /**
     * Identity comes only from the verified JWT: prefer the {@link Principal} the correlation
     * filter placed in {@link RequestContext}; fall back to the {@link Authentication} for robustness.
     */
    private Principal resolvePrincipal(Authentication auth) {
        Principal p = RequestContext.getPrincipal();
        if (p != null) return p;
        if (auth instanceof JwtAuthenticationToken jwtAuth && jwtAuth.getCredentials() instanceof Jwt jwt) {
            return Principal.fromSpringJwt(jwt);
        }
        return null;
    }
}
