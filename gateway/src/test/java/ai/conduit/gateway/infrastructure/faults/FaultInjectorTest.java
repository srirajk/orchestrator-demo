package ai.conduit.gateway.infrastructure.faults;

import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.testsupport.HarnessTestSupport;
import ai.conduit.gateway.testsupport.ScriptedAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the fault seam (F5 spec §3c, harness items 4 & 5): the startup guard denies an armed
 * injector without a marker, the armed injector's own trigger actually fires (positive detection
 * path), a fault at {@code harness.before-invoke} maps to {@code NodeResult.FAILED} without breaking
 * the harness "execute never throws" contract, and the disabled (no-op) binding leaves the path
 * unchanged — asserted via the ScriptedAdapter ledger, not an unprovable byte-identity claim.
 */
class FaultInjectorTest {

    private static final String BEFORE_INVOKE = "harness.before-invoke";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(FaultConfig.class);

    // ── Startup guard ─────────────────────────────────────────────────────────────────────────────

    @Test
    void faults_enabled_without_test_marker_refuses_startup() {
        runner.withPropertyValues("conduit.test.faults.enabled=true")   // no marker, no test/perf profile
                .run(ctx -> assertThat(ctx)
                        .as("arming faults without a test/perf marker must refuse to start")
                        .hasFailed());
    }

    @Test
    void faults_enabled_with_marker_binds_the_throwing_injector() {
        runner.withPropertyValues(
                        "conduit.test.faults.enabled=true",
                        "conduit.test.faults.marker=test",
                        "conduit.test.faults.points=" + BEFORE_INVOKE)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(FaultInjector.class)).isInstanceOf(ThrowingFaultInjector.class);
                });
    }

    @Test
    void disabled_binds_the_noop_injector() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(FaultInjector.class)).isInstanceOf(NoopFaultInjector.class);
        });
    }

    // ── Positive detection path: the kit's own trigger works ───────────────────────────────────────

    @Test
    void enabled_fault_actually_fires() {
        ThrowingFaultInjector armed = new ThrowingFaultInjector(Set.of(BEFORE_INVOKE));

        assertThatThrownBy(() -> armed.at(BEFORE_INVOKE))
                .isInstanceOf(FaultInjectedException.class)
                .hasMessageContaining(BEFORE_INVOKE);
        // An unarmed point is inert.
        assertThatCode(() -> armed.at("some.other.point")).doesNotThrowAnyException();
    }

    // ── Contract pin: a fault at before-invoke becomes FAILED, execute() does not throw ────────────

    @Test
    void fault_at_before_invoke_yields_FAILED_not_throw() {
        ScriptedAdapter adapter = ScriptedAdapter.builder().ok("agent-x", null).build();
        FaultInjector armed = new ThrowingFaultInjector(Set.of(BEFORE_INVOKE));
        AgentHarness harness = HarnessTestSupport.harness(adapter, armed, 1_000, 8, 32);

        NodeResult r = harness.execute(HarnessTestSupport.node("agent-x", "http", 1_000, null));

        assertThat(r.status()).isEqualTo(NodeResult.Status.FAILED);
        // The fault fires BEFORE the adapter call, so the agent was never invoked.
        assertThat(adapter.ledger()).isEmpty();
    }

    // ── Disabled equivalence: noop leaves the path unchanged (item 5, restated honestly) ──────────

    @Test
    void disabled_injector_completes_and_invokes_noop() {
        ScriptedAdapter adapter = ScriptedAdapter.builder().ok("agent-y", null).build();
        NoopFaultInjector noop = new NoopFaultInjector();
        AgentHarness harness = HarnessTestSupport.harness(adapter, noop, 1_000, 8, 32);

        // at() returns normally...
        assertThatCode(() -> noop.at(BEFORE_INVOKE)).doesNotThrowAnyException();

        // ...and the harness path produces the same OK result as a no-fault build: the adapter WAS
        // invoked exactly once (ledger equality is the observable proof, not byte-identical flow).
        NodeResult r = harness.execute(HarnessTestSupport.node("agent-y", "http", 1_000, null));

        assertThat(r.status()).isEqualTo(NodeResult.Status.OK);
        assertThat(adapter.ledger()).hasSize(1);
        assertThat(adapter.ledger().get(0).agentId()).isEqualTo("agent-y");
    }
}
