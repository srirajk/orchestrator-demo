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

    /** True iff a fresh grant covers (principal, agentId, bound resource) — the shared verification core. */
    protected boolean freshGrantPresent(InvocationContext ctx, PlanNode node) {
        if (ctx == null || node == null || node.agent() == null) return false;
        String principalId = ctx.principalId();
        String agentId = node.agent().agentId();
        String boundResource = ctx.boundResourceId(node.nodeId());
        long now = System.currentTimeMillis();
        for (AuthorizationGrant g : ctx.grants()) {
            if (g.isFresh(now, grantTtlMillis) && g.covers(principalId, agentId, boundResource)) {
                return true;
            }
        }
        return false;
    }
}
