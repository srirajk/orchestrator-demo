package ai.conduit.gateway.infrastructure.tenancy;

import java.util.Map;

/**
 * Out-of-band source of the provisioned-tenant snapshot (Axiom Story A2, contract #4).
 *
 * <p>Never called on the request path. A service-authenticated client polls this at startup and on a
 * background timer; the result is atomically installed into the
 * {@link ai.conduit.gateway.domain.auth.ProvisionedTenantDirectory}. IAM Postgres is authoritative;
 * this is how the gateway obtains its read replica.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link ConfigBackedTenantSnapshotSource} — the demo/dev/test source, seeded from config and
 *       always containing the {@code default} tenant so single-tenant requests pass.</li>
 *   <li>a future IAM-HTTP source (behind the {@code multi-tenant} profile) that fetches the authenticated
 *       versioned snapshot from IAM's tenant-directory API.</li>
 * </ul>
 */
public interface TenantSnapshotSource {

    /**
     * Fetch the current versioned snapshot. May throw if the authoritative source is unreachable —
     * the caller keeps the previously-installed snapshot and (if none was ever installed) stays not-ready.
     */
    TenantSnapshot fetch();

    /**
     * An immutable, versioned set of provisioned tenants: {@code tenantId → activePolicyVersion}.
     * The {@code version} is monotonic per source so a stale fetch can be ignored.
     */
    record TenantSnapshot(long version, Map<String, String> tenantPolicyVersions) {
        public TenantSnapshot {
            tenantPolicyVersions = Map.copyOf(tenantPolicyVersions);
        }
    }
}
