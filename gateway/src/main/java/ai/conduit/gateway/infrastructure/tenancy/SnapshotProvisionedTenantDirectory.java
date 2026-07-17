package ai.conduit.gateway.infrastructure.tenancy;

import ai.conduit.gateway.domain.auth.ProvisionedTenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory, atomically-swapped implementation of {@link ProvisionedTenantDirectory} (Axiom A2).
 *
 * <p>Holds a single {@link TenantSnapshotSource.TenantSnapshot} behind an {@link AtomicReference}. The
 * request path only reads it ({@link #find}); the background {@link TenantDirectorySnapshotClient}
 * installs a newer version with {@link #install}. A read is never blocked by a swap and always sees a
 * whole, consistent snapshot — never a half-applied one.
 *
 * <p>Before the first {@link #install}, {@link #hasSnapshot()} is false and every {@link #find} is
 * empty, so the resolver fails closed and the readiness probe stays down.
 */
@Component
public class SnapshotProvisionedTenantDirectory implements ProvisionedTenantDirectory {

    private static final Logger log = LoggerFactory.getLogger(SnapshotProvisionedTenantDirectory.class);

    private final AtomicReference<TenantSnapshotSource.TenantSnapshot> current = new AtomicReference<>();

    /** Atomically replace the installed snapshot, ignoring an out-of-order (older) version. */
    public void install(TenantSnapshotSource.TenantSnapshot snapshot) {
        if (snapshot == null) return;
        TenantSnapshotSource.TenantSnapshot prev = current.get();
        if (prev != null && snapshot.version() < prev.version()) {
            log.debug("Ignoring stale tenant snapshot v{} (installed v{})", snapshot.version(), prev.version());
            return;
        }
        current.set(snapshot);
        log.info("Installed provisioned-tenant snapshot v{} ({} tenants)",
                snapshot.version(), snapshot.tenantPolicyVersions().size());
    }

    @Override
    public Optional<ProvisionedTenant> find(String tenantId) {
        TenantSnapshotSource.TenantSnapshot snapshot = current.get();
        if (snapshot == null || tenantId == null) return Optional.empty();
        String policyVersion = snapshot.tenantPolicyVersions().get(tenantId);
        return policyVersion == null ? Optional.empty()
                : Optional.of(new ProvisionedTenant(tenantId, policyVersion));
    }

    @Override
    public boolean hasSnapshot() {
        return current.get() != null;
    }

    @Override
    public long snapshotVersion() {
        TenantSnapshotSource.TenantSnapshot snapshot = current.get();
        return snapshot == null ? 0L : snapshot.version();
    }
}
