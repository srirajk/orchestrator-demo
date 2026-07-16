package ai.conduit.gateway.orchestration.harness;

import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.testsupport.HarnessTestSupport;
import ai.conduit.gateway.testsupport.ScriptedAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the REAL {@link AgentHarness} with a {@link ScriptedAdapter} into each terminal
 * {@code NodeResult} status (F5 spec harness item 6). Each case is able to fail: a regression in the
 * bulkhead/breaker/SLA guard that swallowed a crash, ignored the deadline, or let a hang run would
 * flip the asserted status.
 */
class ScriptedAdapterHarnessTest {

    @Test
    void crash_maps_to_FAILED() {
        ScriptedAdapter adapter = ScriptedAdapter.builder().crash("agent-crash", "boom-42").build();
        AgentHarness harness = HarnessTestSupport.harness(adapter, 1_000, 8, 32);

        NodeResult r = harness.execute(HarnessTestSupport.node("agent-crash", "http", 1_000, null));

        assertThat(r.status()).isEqualTo(NodeResult.Status.FAILED);
        assertThat(r.errorMessage()).contains("boom-42");
        assertThat(r.data()).isNull();
    }

    @Test
    void slow_beyond_sla_maps_to_TIMEOUT() {
        ScriptedAdapter adapter = ScriptedAdapter.builder().slow("agent-slow", 500).build();
        // SLA (100ms) < scripted delay (500ms) → the future.get(sla) times out.
        AgentHarness harness = HarnessTestSupport.harness(adapter, 100, 8, 32);

        NodeResult r = harness.execute(HarnessTestSupport.node("agent-slow", "http", 100, null));

        assertThat(r.status()).isEqualTo(NodeResult.Status.TIMEOUT);
        assertThat(r.data()).isNull();
    }

    @Test
    void hang_cut_at_deadline() {
        ScriptedAdapter adapter = ScriptedAdapter.builder().hang("agent-hang").build();
        AgentHarness harness = HarnessTestSupport.harness(adapter, 150, 8, 32);

        long start = System.currentTimeMillis();
        NodeResult r = harness.execute(HarnessTestSupport.node("agent-hang", "http", 150, null));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(r.status()).isEqualTo(NodeResult.Status.TIMEOUT);
        // Cut at the deadline, not after the adapter's 1h sleep.
        assertThat(elapsed).isLessThan(5_000);
    }
}
