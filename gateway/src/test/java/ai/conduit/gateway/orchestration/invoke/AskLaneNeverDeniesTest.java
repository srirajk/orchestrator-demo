package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 §a harness test 9 — the ask lane provably never trips the DENY mechanism. ChatService mints a
 * structural grant for every agent it plans (each already survived {@code filterAgents}); this proves
 * that a context minted exactly that way authorizes every planned hop under the fail-closed authorizer,
 * while an agent that was NOT planned (no grant) is denied — so DENY has no reachable ask-lane trigger.
 *
 * <p>The full-context ChatService battery is the behavioural half of this proof: every one of those
 * flows runs through the real fail-closed checkpoint and still answers/clarifies (no DENIED result).
 */
class AskLaneNeverDeniesTest {

    /** Mirror of {@code ChatService.governedPlan}: one structural grant per planned agent. */
    private static InvocationContext mintedLike(List<PlanNode> plannedNodes) {
        List<AuthorizationGrant> grants = plannedNodes.stream()
                .map(n -> n.agent().agentId()).distinct()
                .map(id -> AuthorizationGrant.structural("p1", id, "cerbos", "req"))
                .collect(Collectors.toList());
        return InvocationContext.of("p1", "conv", "req", null, grants);
    }

    @Test
    void everyPlannedAgentIsAllowed() {
        GrantVerifyingAuthorizer authz = new GrantVerifyingAuthorizer();
        List<PlanNode> planned = List.of(
                InvokerTestSupport.node("a1"), InvokerTestSupport.node("a2"), InvokerTestSupport.node("a3"));
        InvocationContext ctx = mintedLike(planned);

        for (PlanNode node : planned) {
            assertThat(authz.authorize(ctx, node).allowed())
                    .as("planned agent %s must never be denied", node.agent().agentId())
                    .isTrue();
        }
    }

    @Test
    void anUnplannedAgentHasNoGrantAndIsDenied() {
        GrantVerifyingAuthorizer authz = new GrantVerifyingAuthorizer();
        InvocationContext ctx = mintedLike(List.of(InvokerTestSupport.node("a1")));

        // a4 was never planned (pruned by filterAgents) → no grant → denied. Proves the grant is
        // specific to the structural verdict, not a blanket allow.
        assertThat(authz.authorize(ctx, InvokerTestSupport.node("a4")).allowed()).isFalse();
    }
}
