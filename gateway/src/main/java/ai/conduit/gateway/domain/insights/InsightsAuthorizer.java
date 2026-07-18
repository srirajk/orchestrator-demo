package ai.conduit.gateway.domain.insights;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Governance gate for {@code /v1/insights/*} — admin-gated through the SAME Cerbos/ABAC PDP path
 * that authorizes chat (INSIGHTS-SPEC §RBAC). A {@code chat_user} is denied (→ 403); an admin
 * (role {@code conduit_admin} or {@code platform_admin}, per {@code infra/cerbos/policies/insights_resource.yaml})
 * is allowed. <strong>Additive only</strong>: this introduces a new Cerbos resource kind
 * ({@code insights}) and never alters an existing agent/relationship decision.
 *
 * <p>The role→allow mapping lives in the Cerbos policy, not here — the gateway carries no
 * embedded authorization matrix (consistent with the World-B posture).
 */
@Component
public class InsightsAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(InsightsAuthorizer.class);

    private final CerbosEntitlementAdapter cerbos;
    private final MeterRegistry meterRegistry;
    private final String resourceKind;
    private final String action;
    private final String resourceId;

    public InsightsAuthorizer(
            CerbosEntitlementAdapter cerbos,
            MeterRegistry meterRegistry,
            @Value("${conduit.insights.authz.resource-kind:insights}") String resourceKind,
            @Value("${conduit.insights.authz.action:read}") String action,
            @Value("${conduit.insights.authz.resource-id:insights}") String resourceId) {
        this.cerbos = cerbos;
        this.meterRegistry = meterRegistry;
        this.resourceKind = resourceKind;
        this.action = action;
        this.resourceId = resourceId;
    }

    /** @return true iff {@code principal} may read Insights, per the Cerbos PDP (fail-closed). */
    public boolean canRead(Principal principal) {
        return canRead(principal, null);
    }

    /** Tenant-aware gate pinned to the request's active scoped policy bundle. */
    public boolean canRead(Principal principal, TenantExecutionContext tenant) {
        if (principal == null || tenant == null || !tenant.isResolved()) return false;
        boolean allowed = cerbos.isAllowed(principal, resourceKind, action, resourceId, tenant);
        Counter.builder("conduit.insights.authz")
                .description("Insights admin-gate decisions")
                .tag("decision", allowed ? "ALLOW" : "DENY")
                .register(meterRegistry)
                .increment();
        if (!allowed) {
            log.info("Insights access DENIED for principal={} roles={}", principal.id(), principal.roles());
        }
        return allowed;
    }
}
