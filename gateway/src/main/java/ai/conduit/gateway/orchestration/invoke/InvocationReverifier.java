package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.model.PlanNode;

/**
 * A per-request callback that re-derives the structural authorization verdict for one agent hop by
 * calling the Cerbos PDP on the request path (via the F3-timed client), used ONLY when the invocation
 * checkpoint finds no fresh matching grant. It is supplied by {@code ChatService} (which holds the
 * principal + the entitlement bean), so the {@code orchestration.invoke} package stays decoupled from
 * {@code domain.auth}. The green path never invokes it — a fresh grant is always present — so it costs
 * zero extra PDP round-trips there.
 */
@FunctionalInterface
public interface InvocationReverifier {
    /** {@code true} iff Cerbos permits this hop right now. Fail-closed callers treat a throw as deny. */
    boolean reverify(PlanNode node);
}
