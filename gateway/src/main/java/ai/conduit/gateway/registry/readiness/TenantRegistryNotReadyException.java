package ai.conduit.gateway.registry.readiness;

/**
 * Thrown when a routing operation is attempted in a tenant whose registry is not ready (Axiom A4).
 *
 * <p>The distinction from a process-level startup failure is the whole point of per-tenant readiness:
 * the gateway process is <em>up</em> and healthy tenants serve, but this one tenant's ingest was
 * empty, broken, or built by a mismatched model. Its {@link ai.conduit.gateway.domain.auth.TenantExecutionContext}
 * resolves (A2) — the tenant is known and provisioned — but routing must fail closed for it rather
 * than return an empty or foreign result. This is a per-tenant deny, not a crash.
 */
public class TenantRegistryNotReadyException extends IllegalStateException {

    private final String tenantId;

    public TenantRegistryNotReadyException(String tenantId, String reason) {
        super("tenant registry not ready for tenant '" + tenantId + "': " + reason);
        this.tenantId = tenantId;
    }

    public String tenantId() {
        return tenantId;
    }
}
