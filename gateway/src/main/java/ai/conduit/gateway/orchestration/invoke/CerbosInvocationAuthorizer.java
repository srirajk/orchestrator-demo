package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.model.PlanNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The sub-step (b) production authorizer. It first verifies a fresh matching grant (the green path —
 * the verdict was decided upstream by {@code filterAgents}, so this costs zero PDP round-trips). Only
 * when NO fresh matching grant is present does it re-derive the verdict on the request path via the
 * context's {@link InvocationReverifier} (the F3-timed Cerbos client) — a stale grant is re-verified,
 * never trusted. It is <b>fail-closed</b>: no grant and no re-verifier (or a re-verifier that throws)
 * ⇒ DENIED.
 *
 * <p>{@code @Primary} so it, not the bare {@link GrantVerifyingAuthorizer}, is injected into the
 * {@link GovernedInvoker}; the base class remains a bean and the pure grant-verification core.
 */
@Component
@Primary
public class CerbosInvocationAuthorizer extends GrantVerifyingAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(CerbosInvocationAuthorizer.class);

    public CerbosInvocationAuthorizer(
            @Value("${conduit.invoke.grant-ttl-ms:120000}") long grantTtlMillis) {
        super(grantTtlMillis);
    }

    @Override
    public AuthorizationDecision authorize(InvocationContext ctx, PlanNode node) {
        if (freshGrantPresent(ctx, node)) {
            return AuthorizationDecision.allow("grant");
        }
        // No fresh grant — do NOT trust a stale one. Re-derive the verdict on-path if we can.
        InvocationReverifier reverifier = ctx == null ? null : ctx.reverifier();
        if (reverifier == null) {
            return AuthorizationDecision.deny("no-fresh-grant", "grant");
        }
        try {
            boolean allowed = reverifier.reverify(node);
            return allowed
                    ? AuthorizationDecision.allow("cerbos")
                    : AuthorizationDecision.deny("cerbos-deny", "cerbos");
        } catch (RuntimeException e) {
            log.warn("On-path re-verification threw for node {} (agent={}) — failing closed: {}",
                    node.nodeId(), node.agent().agentId(), e.getMessage());
            return AuthorizationDecision.deny("cerbos-error", "cerbos");
        }
    }
}
