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
 * F1 §a harness test 10 — exactly one {@code agent_invocation} verdict event per governed hop,
 * matching the harness call count for allowed hops and still emitted for denied/timed-out hops.
 */
class GovernedInvokerAuditTest {

    @Test
    void auditCountEqualsHarnessCallCountForAllowedHops() {
        ScriptedAdapter adapter = ScriptedAdapter.fastOk();
        AgentHarness harness = InvokerTestSupport.harness(adapter);
        TraceEventPublisher publisher = mock(TraceEventPublisher.class);
        GovernedInvoker invoker = new GovernedInvoker(new GrantVerifyingAuthorizer(), harness, publisher);

        for (String id : List.of("a1", "a2", "a3")) {
            PlanNode node = InvokerTestSupport.node(id);
            InvocationContext ctx = InvokerTestSupport.granting("p1", "req-10", id);
            NodeResult r = invoker.invoke(ctx, node, null);
            assertThat(r.isOk()).isTrue();
        }

        assertThat(adapter.ledger()).hasSize(3);                       // harness dispatched exactly 3×
        long invocationEvents = countType(publisher, "agent_invocation");
        assertThat(invocationEvents).isEqualTo(3);                     // one verdict event per hop
    }

    @Test
    void deniedAndAllowedHopsBothEmitOneAuditEventEach() {
        ScriptedAdapter adapter = ScriptedAdapter.fastOk();
        AgentHarness harness = InvokerTestSupport.harness(adapter);
        TraceEventPublisher publisher = mock(TraceEventPublisher.class);
        GovernedInvoker invoker = new GovernedInvoker(new GrantVerifyingAuthorizer(), harness, publisher);

        // a1 granted (ALLOWED → harness), a2 not granted (DENIED → no harness).
        invoker.invoke(InvokerTestSupport.granting("p1", "req-10b", "a1"), InvokerTestSupport.node("a1"), null);
        invoker.invoke(InvocationContext.of("p1", "conv", "req-10b", null, List.of()),
                InvokerTestSupport.node("a2"), null);

        assertThat(adapter.ledger()).hasSize(1);                       // only the allowed hop dispatched
        assertThat(countType(publisher, "agent_invocation")).isEqualTo(2);   // both hops audited
        assertThat(countType(publisher, "check_denied")).isEqualTo(1);       // the deny explained once
    }

    private static long countType(TraceEventPublisher publisher, String type) {
        ArgumentCaptor<TraceEvent> captor = ArgumentCaptor.forClass(TraceEvent.class);
        verify(publisher, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues().stream().map(TraceEvent::type).filter(type::equals).count();
    }
}
