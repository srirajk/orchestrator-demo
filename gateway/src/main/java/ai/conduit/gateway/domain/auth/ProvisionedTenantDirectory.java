package ai.conduit.gateway.domain.auth;

import java.util.Optional;

/**
 * Read-only view of the set of provisioned (active) tenants and their active policy versions.
 *
 * <p><b>No request-path directory I/O.</b> Implementations serve from an in-memory, versioned
 * snapshot that was fetched out-of-band (at startup / on a background timer) and swapped atomically —
 * the request path only reads this snapshot, it never calls the IAM tenant directory. IAM Postgres is
 * the authoritative source of tenants; the gateway holds a replica.
 *
 * <p><b>Readiness.</b> The gateway is not ready until an initial snapshot exists ({@link #hasSnapshot()}).
 * A request that arrives before the first snapshot must fail closed, not fall back to a default tenant.
 */
public interface ProvisionedTenantDirectory {

    /**
     * The active provisioning record for {@code tenantId}, or empty if the tenant is unknown or has
     * been deprovisioned. Empty ⇒ the caller must deny (fail closed); never substitute a default.
     */
    Optional<ProvisionedTenant> find(String tenantId);

    /** True once an initial snapshot has been loaded. The readiness probe gates on this. */
    boolean hasSnapshot();

    /** Monotonic version of the currently-installed snapshot (0 before the first snapshot). */
    long snapshotVersion();

    /** A single provisioned tenant and the policy/bundle version currently active for it. */
    record ProvisionedTenant(String tenantId, String activePolicyVersion) {}
}
