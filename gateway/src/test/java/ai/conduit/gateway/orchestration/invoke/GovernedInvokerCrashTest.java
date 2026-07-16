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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * F1 §a harness test 5 — a throwing authorizer fails CLOSED: DENIED, the agent is never invoked, and
 * the audit verdict event is still emitted. This is the fail-closed guarantee for a PDP that errors
 * (e.g. the Cerbos container stopped) — the checkpoint denies rather than admitting.
 */
class GovernedInvokerCrashTest {

    @Test
    void authorizerThrowingFailsClosed() {
        ScriptedAdapter adapter = ScriptedAdapter.fastOk();
        AgentHarness harness = InvokerTestSupport.harness(adapter);
        TraceEventPublisher publisher = mock(TraceEventPublisher.class);
        InvocationAuthorizer throwing = (ctx, node) -> {
            throw new IllegalStateException("PDP unreachable");
        };
        GovernedInvoker invoker = new GovernedInvoker(throwing, harness, publisher);

        PlanNode node = InvokerTestSupport.node("a1");
        InvocationContext ctx = InvokerTestSupport.granting("p1", "req-5", "a1");

        NodeResult result = invoker.invoke(ctx, node, null);

        assertThat(result.status()).isEqualTo(NodeResult.Status.DENIED);
        assertThat(adapter.ledger()).as("a throwing authorizer must never dispatch the agent").isEmpty();

        ArgumentCaptor<TraceEvent> captor = ArgumentCaptor.forClass(TraceEvent.class);
        verify(publisher, atLeastOnce()).publish(captor.capture());
        List<String> types = captor.getAllValues().stream().map(TraceEvent::type).toList();
        assertThat(types).contains("agent_invocation");   // the verdict audit event survives the throw
    }
}
