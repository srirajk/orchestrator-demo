package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.model.PlanNode;

/**
 * The authorize phase of the {@link GovernedInvoker}: decides whether a single agent hop may be
 * dispatched. Implementations are <b>fail-closed</b> — anything other than a positive, fresh,
 * matching authorization must {@link AuthorizationDecision#deny deny}.
 */
@FunctionalInterface
public interface InvocationAuthorizer {

    AuthorizationDecision authorize(InvocationContext ctx, PlanNode node);

    /** The verdict for one hop: allowed, or denied with a machine-readable reason + source. */
    record AuthorizationDecision(boolean allowed, String reason, String source) {
        public static AuthorizationDecision allow(String source) {
            return new AuthorizationDecision(true, null, source);
        }
        public static AuthorizationDecision deny(String reason, String source) {
            return new AuthorizationDecision(false, reason, source);
        }
    }
}
