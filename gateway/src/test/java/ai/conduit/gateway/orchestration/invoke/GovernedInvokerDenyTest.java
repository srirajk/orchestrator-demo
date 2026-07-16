package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.testsupport.ScriptedAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;

/**
 * F1 §a harness tests 2 &amp; 3 — the DENY mechanism provably blocks invocation and never reaches the
 * adapter, and a missing grant fails closed.
 */
class GovernedInvokerDenyTest {

    /** Test 2 — an always-DENY authorizer: zero adapter interactions, DENIED, check_denied + agent_invocation. */
    @Test
    void denyVerdictNeverReachesAdapter() {
        ScriptedAdapter adapter = ScriptedAdapter.fastOk();          // spy: records every invoke in its ledger
        AgentHarness harness = InvokerTestSupport.harness(adapter);
        TraceEventPublisher publisher = mock(TraceEventPublisher.class);
        InvocationAuthorizer alwaysDeny = (ctx, node) ->
                InvocationAuthorizer.AuthorizationDecision.deny("policy", "test");
        GovernedInvoker invoker = new GovernedInvoker(alwaysDeny, harness, publisher);

        PlanNode node = InvokerTestSupport.node("a1");
        // Even WITH a grant present, the (stubbed) authorizer denies — proves the checkpoint gates dispatch.
        InvocationContext ctx = InvokerTestSupport.granting("p1", "req-1", "a1");

        NodeResult result = invoker.invoke(ctx, node, null);

        assertThat(result.status()).isEqualTo(NodeResult.Status.DENIED);
        assertThat(adapter.ledger()).as("adapter must never be touched on a deny").isEmpty();

        List<String> types = capturedTypes(publisher);
        assertThat(types).contains("check_denied");
        assertThat(types).contains("agent_invocation");
    }

    /** Test 3 — an empty-grants context under the REAL fail-closed authorizer denies; adapter untouched. */
    @Test
    void missingGrantIsDeniedFailClosed() {
        ScriptedAdapter adapter = ScriptedAdapter.fastOk();
        AgentHarness harness = InvokerTestSupport.harness(adapter);
        TraceEventPublisher publisher = mock(TraceEventPublisher.class);
        GovernedInvoker invoker = new GovernedInvoker(new GrantVerifyingAuthorizer(), harness, publisher);

        PlanNode node = InvokerTestSupport.node("a1");
        InvocationContext empty = InvocationContext.of("p1", "conv", "req-2", null, List.of());

        NodeResult result = invoker.invoke(empty, node, null);

        assertThat(result.status()).isEqualTo(NodeResult.Status.DENIED);
        assertThat(adapter.ledger()).isEmpty();
    }

    private static List<String> capturedTypes(TraceEventPublisher publisher) {
        ArgumentCaptor<TraceEvent> captor = ArgumentCaptor.forClass(TraceEvent.class);
        verify(publisher, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues().stream().map(TraceEvent::type).toList();
    }
}
