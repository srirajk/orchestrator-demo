package ai.conduit.gateway.infrastructure.tenancy;

import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Per-tenant concurrency isolation for the shared routing dependencies (Axiom A4).
 *
 * <p>The routing path calls out to shared, finite resources — the embedding sidecar above all. Under
 * one shared pool of permits a single tenant issuing a burst of routing queries can hold every permit
 * and stall every other tenant: a noisy-neighbour denial of service that no per-request timeout
 * prevents. The answer is a bulkhead <b>per tenant</b>, so a tenant can only ever consume its own
 * slice of concurrency, plus one <b>global</b> bulkhead that caps total in-flight routing work so the
 * sum of the per-tenant slices can never overcommit the sidecar.
 *
 * <h2>Bounded cardinality</h2>
 * A per-tenant registry keyed by an unbounded tenant string is itself an attack surface: a stream of
 * fabricated tenant ids would grow the registry without limit. Two guards: the tenant segment is the
 * A3-canonical, provisioned value (never a raw caller string — enforced by {@link TenantKeyspace}),
 * and the registry is capped at {@code max-tenants}. On deprovision the entry is
 * {@linkplain #deprovision(String) removed}, and {@link #reconcile(Set)} drops every bulkhead whose
 * tenant is no longer active — so cardinality tracks the live tenant set, not its lifetime history.
 *
 * <p>Not on the {@code registry} profile: this is request-path infrastructure the ingestor never uses.
 */
@Component
@Profile("!registry")
public class TenantBulkheads {

    private static final Logger log = LoggerFactory.getLogger(TenantBulkheads.class);

    static final String GLOBAL = "routing-global";

    private final int maxConcurrentPerTenant;
    private final int maxTenants;
    private final Duration acquireWait;

    private final BulkheadRegistry registry;
    private final Bulkhead global;
    /** Explicit membership set so cardinality is O(1) and independent of registry internals. */
    private final ConcurrentMap<String, Bulkhead> perTenant = new ConcurrentHashMap<>();

    public TenantBulkheads(
            @Value("${conduit.tenancy.bulkhead.max-concurrent-per-tenant:8}") int maxConcurrentPerTenant,
            @Value("${conduit.tenancy.bulkhead.global-max-concurrent:64}") int globalMaxConcurrent,
            @Value("${conduit.tenancy.bulkhead.max-tenants:256}") int maxTenants,
            @Value("${conduit.tenancy.bulkhead.acquire-wait-ms:0}") long acquireWaitMs) {
        this.maxConcurrentPerTenant = maxConcurrentPerTenant;
        this.maxTenants = maxTenants;
        this.acquireWait = Duration.ofMillis(Math.max(0, acquireWaitMs));
        this.registry = BulkheadRegistry.ofDefaults();
        this.global = Bulkhead.of(GLOBAL, BulkheadConfig.custom()
                .maxConcurrentCalls(globalMaxConcurrent)
                .maxWaitDuration(this.acquireWait)
                .build());
        log.info("Tenant bulkheads: {} permits/tenant, {} global, cap {} tenants",
                maxConcurrentPerTenant, globalMaxConcurrent, maxTenants);
    }

    /** The shared global bulkhead capping total in-flight routing work across all tenants. */
    public Bulkhead global() {
        return global;
    }

    /**
     * This tenant's bulkhead, created on first use and reused thereafter. Refuses to create a new one
     * once {@code max-tenants} distinct tenants are live — a fabricated-id flood cannot grow the
     * registry without bound.
     */
    public Bulkhead forTenant(String tenant) {
        Bulkhead existing = perTenant.get(tenant);
        if (existing != null) {
            return existing;
        }
        if (perTenant.size() >= maxTenants) {
            throw new IllegalStateException("Per-tenant bulkhead cap reached (" + maxTenants
                    + " tenants); refusing to allocate a bulkhead for '" + tenant + "'");
        }
        return perTenant.computeIfAbsent(tenant, t -> {
            Bulkhead b = registry.bulkhead(bulkheadName(t), BulkheadConfig.custom()
                    .maxConcurrentCalls(maxConcurrentPerTenant)
                    .maxWaitDuration(acquireWait)
                    .build());
            log.debug("Allocated bulkhead for tenant '{}' ({} permits)", t, maxConcurrentPerTenant);
            return b;
        });
    }

    /**
     * Run {@code work} for {@code tenant} inside both the global and the tenant's bulkhead. The global
     * permit is acquired first (a full system rejects before it touches a tenant permit), then the
     * tenant permit. A {@link BulkheadFullException} propagates so the caller can fail that call
     * without blocking — the whole point is that a saturated tenant rejects fast rather than parking.
     */
    public <T> T call(String tenant, java.util.function.Supplier<T> work) {
        Bulkhead tenantBulkhead = forTenant(tenant);
        return Bulkhead.decorateSupplier(global,
                Bulkhead.decorateSupplier(tenantBulkhead, work)).get();
    }

    /** Remove a deprovisioned tenant's bulkhead so cardinality never carries dead tenants. */
    public void deprovision(String tenant) {
        Bulkhead removed = perTenant.remove(tenant);
        if (removed != null) {
            registry.remove(bulkheadName(tenant));
            log.info("Removed bulkhead for deprovisioned tenant '{}'", tenant);
        }
    }

    /** Drop every per-tenant bulkhead whose tenant is not in the active set (deprovision reconcile). */
    public void reconcile(Set<String> activeTenants) {
        Set<String> stale = perTenant.keySet().stream()
                .filter(t -> !activeTenants.contains(t))
                .collect(Collectors.toSet());
        stale.forEach(this::deprovision);
    }

    /** The tenants that currently hold a bulkhead — for cardinality assertions and observability. */
    public Collection<String> activeTenants() {
        return Set.copyOf(perTenant.keySet());
    }

    private static String bulkheadName(String tenant) {
        return "tenant-routing-" + tenant;
    }
}
