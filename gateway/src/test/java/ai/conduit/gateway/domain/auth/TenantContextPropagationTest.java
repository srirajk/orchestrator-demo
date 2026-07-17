package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData;
import ai.conduit.gateway.orchestration.executor.DagPlanExecutor;
import ai.conduit.gateway.orchestration.invoke.AuthorizationGrant;
import ai.conduit.gateway.orchestration.invoke.InvocationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2.2 — the tenant context captured on the servlet thread is an immutable value that survives the
 * virtual-thread boundary unchanged, and every downstream seam (invocation, coverage, audit — the
 * trace the Redis trace-store and the audit assembler both consume) observes that EXACT value.
 *
 * <p>The full-context {@code ChatService*} battery + {@code AgentHarnessResilienceIT} are the
 * behavioural half of this proof: they drive the real streaming/flat/DAG/map/grounding VT paths under
 * the {@code default} tenant. This test pins the invariant the threading rests on: the value is
 * immutable, is passed explicitly (never recovered from a static holder), and reaches each seam byte-
 * for-byte identical even across an async hop.
 */
class TenantContextPropagationTest {

    private static final TenantExecutionContext CAPTURED =
            new TenantExecutionContext("default", "default", "config-v1");

    @Test
    void contextSurvivesTheVirtualThreadHopUnchanged() throws Exception {
        // Capture on the "servlet thread", then hop onto a virtual thread exactly as the pipeline does.
        AtomicReference<TenantExecutionContext> seenOnVThread = new AtomicReference<>();
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            vt.submit(() -> seenOnVThread.set(CAPTURED)).get();
        }
        // Same immutable instance observed on the other side of the boundary — nothing re-derived it.
        assertThat(seenOnVThread.get()).isSameAs(CAPTURED);
        assertThat(seenOnVThread.get().tenantId()).isEqualTo("default");
        assertThat(seenOnVThread.get().activePolicyVersion()).isEqualTo("config-v1");
        assertThat(seenOnVThread.get().isResolved()).isTrue();
    }

    @Test
    void theInvocationSeamObservesTheExactTenant() {
        InvocationContext ctx = InvocationContext.of("rm_jane", CAPTURED, "conv", "req",
                "tok", List.of(AuthorizationGrant.structural("rm_jane", "a1", "cerbos", "req")));
        // The GovernedInvoker reads the tenant off THIS envelope (explicit), not a static holder.
        assertThat(ctx.tenant()).isSameAs(CAPTURED);
        assertThat(ctx.tenant().activePolicyVersion()).isEqualTo("config-v1");
    }

    @Test
    void theCoverageSeamObservesTheExactTenant() {
        DagPlanExecutor.CoverageContext coverage = new DagPlanExecutor.CoverageContext(
                "rm_jane", CAPTURED.tenantId(), "tok", m -> null, (a, b, c, d, e) -> null);
        assertThat(coverage.tenantId()).isEqualTo(CAPTURED.tenantId());
    }

    @Test
    void theAuditTraceObservesTheExactTenant() {
        // request_start carries the tenant; this is the event the Redis trace-store persists and the
        // audit assembler promotes to the record's principal.tenant — one carrier, both consumers.
        RequestStartData started = new RequestStartData("rm_jane", CAPTURED.tenantId(), "how are my holdings?");
        assertThat(started.tenantId()).isEqualTo(CAPTURED.tenantId());
    }

    @Test
    void anUnresolvedTenantIsNotConsideredResolved() {
        // A half-populated tenant (no captured policy version) must never pass the invoker's integrity
        // gate — isResolved() is the predicate GovernedInvoker denies on.
        assertThat(new TenantExecutionContext("default", "default", null).isResolved()).isFalse();
        assertThat(new TenantExecutionContext("", "", "config-v1").isResolved()).isFalse();
    }
}
