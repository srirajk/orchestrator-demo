package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 §b — the resource-scoped grant + TOCTOU closure. A hop bound to a coverage resource must be
 * authorized by a resource-scoped grant for THAT id; a structural grant does not stand in once a
 * resource grant exists for the agent, so a hop bound to a different entity than coverage cleared is
 * denied. With no resource grant, a structural grant still governs (parity with the pre-coverage path).
 */
class GrantToctouTest {

    private final GrantVerifyingAuthorizer authz = new GrantVerifyingAuthorizer();

    @Test
    void boundHopMatchingItsResourceGrantIsAllowed() {
        PlanNode node = InvokerTestSupport.node("a1");
        InvocationContext ctx = InvocationContext.of("p1", "conv", "req", null, List.of(
                AuthorizationGrant.structural("p1", "a1", "cerbos", "req"),
                AuthorizationGrant.resourceScoped("p1", "a1", "E1", "coverage", "req")));
        ctx.bindResource("a1", "E1");   // coverage cleared E1, node bound to E1

        assertThat(authz.authorize(ctx, node).allowed()).isTrue();
    }

    @Test
    void boundHopWhoseResourceDiffersFromTheGrantIsDenied() {
        PlanNode node = InvokerTestSupport.node("a1");
        InvocationContext ctx = InvocationContext.of("p1", "conv", "req", null, List.of(
                AuthorizationGrant.structural("p1", "a1", "cerbos", "req"),
                AuthorizationGrant.resourceScoped("p1", "a1", "E1", "coverage", "req")));
        ctx.bindResource("a1", "E2");   // node bound to a DIFFERENT entity than coverage cleared

        assertThat(authz.authorize(ctx, node).allowed())
                .as("a structural grant must not stand in for a mismatched resource binding")
                .isFalse();
    }

    @Test
    void structuralGrantGovernsWhenNoResourceGrantExists() {
        PlanNode node = InvokerTestSupport.node("a1");
        InvocationContext ctx = InvocationContext.of("p1", "conv", "req", null, List.of(
                AuthorizationGrant.structural("p1", "a1", "cerbos", "req")));
        // Unbound hop (no coverage) — structural grant authorizes, exactly as the non-resource path.
        assertThat(authz.authorize(ctx, node).allowed()).isTrue();
    }

    @Test
    void mapItemInheritsEnvelopeResourceBinding() {
        // Coverage/grant decided once on the envelope 'a1'; item node 'a1[3]' inherits the binding.
        PlanNode item = new PlanNode("a1[3]", InvokerTestSupport.agent("a1"), null, List.of());
        InvocationContext ctx = InvocationContext.of("p1", "conv", "req", null, List.of(
                AuthorizationGrant.resourceScoped("p1", "a1", "E1", "coverage", "req")));
        ctx.bindResource("a1", "E1");

        assertThat(authz.authorize(ctx, item).allowed()).isTrue();
    }
}
