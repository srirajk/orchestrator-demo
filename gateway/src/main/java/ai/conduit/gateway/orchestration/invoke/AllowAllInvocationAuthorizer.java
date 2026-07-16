package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.model.PlanNode;

/**
 * A permissive authorizer that admits every hop. <b>Test seam only.</b> It is deliberately NOT a
 * Spring bean, so it can never be injected onto a production path — the only construction site is the
 * non-{@code @Autowired}, test-convenience {@code DagPlanExecutor} constructor, which pre-existing
 * executor unit tests use to exercise fan-out/DAG mechanics without minting grants. The
 * {@code @Autowired} production wiring always receives the fail-closed grant/Cerbos authorizer.
 */
public final class AllowAllInvocationAuthorizer implements InvocationAuthorizer {
    @Override
    public AuthorizationDecision authorize(InvocationContext ctx, PlanNode node) {
        return AuthorizationDecision.allow("test-allow-all");
    }
}
