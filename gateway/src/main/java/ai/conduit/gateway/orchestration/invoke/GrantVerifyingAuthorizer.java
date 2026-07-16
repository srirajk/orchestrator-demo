package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.model.PlanNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The sub-step (a) authorizer: a hop is allowed ONLY if the context carries a fresh grant that
 * {@link AuthorizationGrant#covers covers} its ({@code principalId}, {@code agentId}, bound resource).
 * No grant — or only a stale one — denies (fail-closed). It makes NO outbound call; it merely verifies
 * a verdict already decided upstream, so the green path costs nothing extra.
 *
 * <p>Sub-step (b) swaps the wired bean to {@link CerbosInvocationAuthorizer}, which extends this
 * fail-closed check with an on-path PDP call for the missing/stale case; this class remains the pure,
 * dependency-free authorizer used by the invoker's unit tests and as the grant-verification core.
 */
@Component
public class GrantVerifyingAuthorizer implements InvocationAuthorizer {

    private final long grantTtlMillis;

    public GrantVerifyingAuthorizer(
            @Value("${conduit.invoke.grant-ttl-ms:120000}") long grantTtlMillis) {
        this.grantTtlMillis = grantTtlMillis;
    }

    /** Test constructor with an explicit TTL. */
    public GrantVerifyingAuthorizer() {
        this(120_000L);
    }

    @Override
    public AuthorizationDecision authorize(InvocationContext ctx, PlanNode node) {
        if (freshGrantPresent(ctx, node)) {
            return AuthorizationDecision.allow("grant");
        }
        return AuthorizationDecision.deny("no-fresh-grant", "grant");
    }

    /**
     * True iff a fresh grant authorizes this hop — the shared verification core, with the TOCTOU
     * closure. A structural grant (no resource scope) authorizes an <em>unbound</em> hop. When the hop
     * is bound to a coverage resource AND any resource-scoped grant exists for that agent, a matching
     * resource grant is REQUIRED (a structural grant does not stand in) — so a hop bound to a different
     * entity than the one the coverage check cleared is denied. When no resource grant exists for the
     * agent, a structural grant governs (parity with the pre-coverage path).
     */
    protected boolean freshGrantPresent(InvocationContext ctx, PlanNode node) {
        if (ctx == null || node == null || node.agent() == null) return false;
        String principalId = ctx.principalId();
        String agentId = node.agent().agentId();
        String boundResource = ctx.boundResourceId(node.nodeId());
        long now = System.currentTimeMillis();

        boolean structuralMatch = false;
        boolean resourceGrantForAgent = false;
        boolean resourceMatch = false;
        for (AuthorizationGrant g : ctx.grants()) {
            if (!g.isFresh(now, grantTtlMillis)) continue;
            if (!agentId.equals(g.agentId())
                    || !java.util.Objects.equals(principalId, g.principalId())) continue;
            if (g.resourceId() == null) {
                structuralMatch = true;
            } else {
                resourceGrantForAgent = true;
                if (g.resourceId().equals(boundResource)) resourceMatch = true;
            }
        }
        if (boundResource != null && resourceGrantForAgent) {
            return resourceMatch;   // TOCTOU: bound hop must match a resource grant
        }
        return structuralMatch || resourceMatch;
    }
}
