package ai.conduit.gateway.registry.tenancy;

import ai.conduit.gateway.infrastructure.tenancy.TenantBulkheads;
import io.github.resilience4j.bulkhead.Bulkhead;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4.5 — per-tenant bulkhead isolation. One tenant saturating its own concurrency slice must not
 * consume another tenant's permits: a noisy neighbour is bounded to its own bulkhead. Plus the
 * bounded-cardinality guards (max-tenants cap + deprovision removal) that keep the per-tenant
 * registry from growing without limit.
 */
class PerTenantBulkheadTest {

    /** A saturated tenant must not delay another tenant's permit acquisition beyond this. */
    private static final long SLA_MS = 50;

    @Test
    void oneTenantLoadDoesNotStarveAnother() {
        TenantBulkheads bulkheads = new TenantBulkheads(2, 64, 16, 0);

        // Saturate tenant A: take both of A's permits.
        Bulkhead a = bulkheads.forTenant("tenant-a");
        assertThat(a.tryAcquirePermission()).isTrue();
        assertThat(a.tryAcquirePermission()).isTrue();
        assertThat(a.tryAcquirePermission()).as("A is saturated").isFalse();

        // Tenant B acquires its own permit immediately, well within SLA — A's load never touched B.
        Bulkhead b = bulkheads.forTenant("tenant-b");
        long start = System.nanoTime();
        boolean acquired = b.tryAcquirePermission();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(acquired).as("B is unaffected by A's saturation").isTrue();
        assertThat(elapsedMs).as("B acquires within SLA").isLessThan(SLA_MS);

        a.releasePermission();
        a.releasePermission();
        b.releasePermission();
    }

    @Test
    void perTenantCardinalityIsBounded() {
        TenantBulkheads bulkheads = new TenantBulkheads(1, 64, 2, 0);

        bulkheads.forTenant("t1");
        bulkheads.forTenant("t2");
        assertThat(bulkheads.activeTenants()).containsExactlyInAnyOrder("t1", "t2");

        // A third distinct tenant would exceed the cap — refused, so a fabricated-id flood cannot grow
        // the registry without bound.
        assertThatThrownBy(() -> bulkheads.forTenant("t3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cap reached");
    }

    @Test
    void deprovisionRemovesTheBulkheadAndFreesCapacity() {
        TenantBulkheads bulkheads = new TenantBulkheads(1, 64, 2, 0);
        bulkheads.forTenant("t1");
        bulkheads.forTenant("t2");

        bulkheads.deprovision("t1");
        assertThat(bulkheads.activeTenants()).containsExactly("t2");

        // The freed slot can be reused — cardinality tracks the live set, not lifetime history.
        assertThatCode(() -> bulkheads.forTenant("t3")).doesNotThrowAnyException();

        // Reconcile drops every bulkhead whose tenant is no longer active.
        bulkheads.reconcile(Set.of("t3"));
        assertThat(bulkheads.activeTenants()).containsExactly("t3");
    }
}
