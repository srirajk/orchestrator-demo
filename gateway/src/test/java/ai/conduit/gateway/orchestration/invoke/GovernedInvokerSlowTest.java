package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.testsupport.ScriptedAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 §a harness test 6 — the harness SLA timeout is still enforced THROUGH the invoker: a stub agent
 * that hangs past its SLA yields TIMEOUT (not a hang), the checkpoint audits it, and repeated runs
 * prove the bulkhead permits are restored each time (no leak introduced by the indirection).
 */
class GovernedInvokerSlowTest {

    @Test
    void slaTimeoutStillEnforcedThroughInvoker() {
        int slaMs = 150;
        ScriptedAdapter adapter = ScriptedAdapter.builder().hang("slow").build();  // sleeps ~forever
        AgentHarness harness = InvokerTestSupport.harness(adapter);
        GovernedInvoker invoker = new GovernedInvoker(new GrantVerifyingAuthorizer(), harness, null);

        // First run proves the SLA is enforced THROUGH the invoker: the hang is cut at the SLA.
        NodeResult first = invoker.invoke(InvokerTestSupport.granting("p1", "req", "slow"),
                InvokerTestSupport.node("slow", slaMs), null);
        assertThat(first.status()).isEqualTo(NodeResult.Status.TIMEOUT);
        assertThat(first.latencyMs()).isLessThan(2_000L);   // latency ≈ SLA, not the 1h hang

        // 20 more sequential reruns: each returns a PROMPT terminal (TIMEOUT, or BREAKER_OPEN once the
        // harness circuit trips on the repeated slow calls) — never a hang. A leaked executing permit
        // would stall the bulkhead and blow the wall-clock budget below.
        long start = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            NodeResult r = invoker.invoke(InvokerTestSupport.granting("p1", "req", "slow"),
                    InvokerTestSupport.node("slow", slaMs), null);
            assertThat(r.status()).isIn(NodeResult.Status.TIMEOUT, NodeResult.Status.BREAKER_OPEN);
        }
        long elapsed = System.currentTimeMillis() - start;

        // 20 × ~150ms ≈ 3s; allow generous slack. If permits leaked the bulkhead would serialize on a
        // dwindling permit count and this would balloon well past the bound.
        assertThat(elapsed).as("permits restored each run — no bulkhead stall").isLessThan(15_000L);
    }
}
