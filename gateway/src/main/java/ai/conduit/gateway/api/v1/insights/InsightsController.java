package ai.conduit.gateway.api.v1.insights;

import ai.conduit.gateway.domain.insights.*;
import ai.conduit.gateway.domain.insights.model.*;

import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.RequestContext;
import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceStorageAdapter;
import ai.conduit.gateway.domain.insights.LangfuseMetricsSource.CostSlice;
import ai.conduit.gateway.domain.insights.model.Board;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conduit Insights API — native, admin-gated analytics inside the gateway.
 *
 * <pre>
 * GET /v1/insights/boards/{boardId}                     → {"panels":[...]}          (ops + cost/quality)
 * GET /v1/insights/cost                                 → cost & tokens sliced by model/user/segment
 * GET /v1/insights/conversations/{conversationId}/trace → the per-conversation decision trace (Redis)
 * </pre>
 *
 * <p>Cleanly bounded module reusing the gateway's JWT auth, virtual threads and config
 * (INSIGHTS-SPEC §Architecture). Every endpoint runs through {@link InsightsAuthorizer} (the same
 * Cerbos/ABAC PDP as chat): a {@code chat_user} → 403, an admin → 200. Nothing here embeds a
 * domain/agent/entity literal (World B) — user/segment/model come from the trace, decision-trace
 * payloads are runtime data read back verbatim from Redis.
 */
@RestController
@RequestMapping("/v1/insights")
public class InsightsController {

    private static final Logger log = LoggerFactory.getLogger(InsightsController.class);

    private final BoardCatalog catalog;
    private final InsightsExecutor executor;
    private final InsightsAuthorizer authorizer;
    private final LangfuseMetricsSource langfuse;
    private final TraceStorageAdapter traceStore;
    private final int maxTraceRequests;

    public InsightsController(BoardCatalog catalog, InsightsExecutor executor,
                              InsightsAuthorizer authorizer, LangfuseMetricsSource langfuse,
                              TraceStorageAdapter traceStore,
                              @Value("${conduit.insights.max-trace-requests:50}") int maxTraceRequests) {
        this.catalog = catalog;
        this.executor = executor;
        this.authorizer = authorizer;
        this.langfuse = langfuse;
        this.traceStore = traceStore;
        this.maxTraceRequests = maxTraceRequests;
    }

    // ── Boards ───────────────────────────────────────────────────────────────────
    @GetMapping("/boards/{boardId}")
    public ResponseEntity<?> board(@PathVariable int boardId,
                                   @RequestParam(name = "range", required = false) String rangeParam,
                                   Authentication auth) {
        ResponseEntity<?> denied = guard(auth);
        if (denied != null) return denied;
        if (!catalog.exists(boardId)) {
            return ResponseEntity.status(404).body(Map.of("error", "not_found", "reason", "no board " + boardId));
        }
        // Fail-safe: absent/blank/unknown range → the 24h default (never a 400).
        Range range = Range.from(rangeParam);
        List<PanelSpec> specs = catalog.panelsFor(boardId, range);
        Board board = executor.render(boardId, specs);
        log.debug("Insights board {} (range={}) served", boardId, range.key());
        return ResponseEntity.ok(board);
    }

    // ── Cost & unit economics, sliced by model / user / segment ──────────────────
    @GetMapping("/cost")
    public ResponseEntity<?> cost(@RequestParam(name = "range", required = false) String rangeParam,
                                  Authentication auth) {
        ResponseEntity<?> denied = guard(auth);
        if (denied != null) return denied;

        Range range = Range.from(rangeParam);
        int days = range.langfuseDays();

        CostSlice totals = langfuse.costTotals(days);
        long questions = totals.count();
        double costPerQuestion  = questions > 0 ? totals.costUsd() / questions : 0.0;
        double tokensPerQuestion = questions > 0 ? totals.tokens()  / questions : 0.0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("range", range.key());
        body.put("currency", "USD");
        body.put("totalCostUsd", round(totals.costUsd(), 6));
        body.put("costEstimated", totals.estimated());
        body.put("totalTokens", (long) totals.tokens());
        body.put("questions", questions);
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("costPerQuestionUsd", round(costPerQuestion, 6));
        unit.put("tokensPerQuestion", round(tokensPerQuestion, 1));
        body.put("unitEconomics", unit);
        body.put("byModel", slices(langfuse.costByModel(days)));
        body.put("byUser", slices(langfuse.costByUser(days)));
        body.put("bySegment", slices(langfuse.costBySegment(days)));
        return ResponseEntity.ok(body);
    }

    // ── Per-conversation decision trace (glass-box replay from Redis) ────────────
    @GetMapping("/conversations/{conversationId}/trace")
    public ResponseEntity<?> conversationTrace(@PathVariable String conversationId,
                                               @RequestParam(name = "limit", required = false) Integer limit,
                                               Authentication auth) {
        ResponseEntity<?> denied = guard(auth);
        if (denied != null) return denied;

        int lim = (limit != null && limit > 0) ? Math.min(limit, maxTraceRequests) : maxTraceRequests;
        List<String> requestIds = traceStore.getRequestIdsByConversation(conversationId, lim);

        List<Map<String, Object>> requests = new ArrayList<>();
        for (String requestId : requestIds) {
            List<TraceEvent> events = traceStore.getByRequestId(requestId);
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("requestId", requestId);
            req.put("eventCount", events.size());
            req.put("events", events);
            requests.add(req);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("requestCount", requests.size());
        body.put("requests", requests);
        log.debug("Insights decision-trace for conversation {} → {} request(s)", conversationId, requests.size());
        return ResponseEntity.ok(body);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> slices(List<CostSlice> slices) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CostSlice s : slices) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", s.label());
            m.put("costUsd", round(s.costUsd(), 6));
            m.put("tokens", (long) s.tokens());
            m.put("count", s.count());
            m.put("estimated", s.estimated());
            out.add(m);
        }
        return out;
    }

    /** Admin gate shared by every endpoint: 401 if unauthenticated, 403 if not an insights admin. */
    private ResponseEntity<?> guard(Authentication auth) {
        Principal principal = resolvePrincipal(auth);
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        var tenant = RequestContext.getTenant();
        // Insights is protected tenant data: no resolved request tenant means no authorization check can
        // be correctly scoped, so fail closed instead of falling back to the unscoped base policy.
        boolean canRead = tenant != null && tenant.isResolved() && authorizer.canRead(principal, tenant);
        if (!canRead) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden", "reason", "insights requires admin"));
        }
        return null;
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

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
