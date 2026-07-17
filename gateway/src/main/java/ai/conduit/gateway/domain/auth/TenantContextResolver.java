package ai.conduit.gateway.domain.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The single seam that reads the {@code tenant_id} claim off a verified JWT and turns it into an
 * immutable {@link TenantExecutionContext} (Axiom Story A2).
 *
 * <p>This — together with the A1 audience/tenant verifier in {@code SecurityConfig} — is the ONLY
 * production code that references the raw {@code tenant_id} claim string. Every downstream consumer
 * receives a resolved {@link TenantExecutionContext} and never touches the claim again. Enforced by
 * {@code TenantContextSeamArchTest}.
 *
 * <p>Fail-closed, BEFORE any I/O:
 * <ul>
 *   <li>no / blank tenant claim on a verified principal ⇒ {@link MissingTenantClaimException} (401)</li>
 *   <li>tenant not in the provisioned snapshot (unknown or deprovisioned) ⇒
 *       {@link TenantNotProvisionedException} (403)</li>
 *   <li>no snapshot loaded yet ⇒ {@link TenantDirectoryUnavailableException} (503, not ready)</li>
 * </ul>
 * The tenant's active policy version is captured here from the snapshot so the whole request pins one
 * version even if the background snapshot swaps mid-flight.
 */
@Component
public class TenantContextResolver {

    /** The only place these claim names are referenced outside the A1 verifier. */
    static final String TENANT_CLAIM = "tenant_id";
    static final String ACTOR_TENANT_CLAIM = "actor_tenant_id";

    private final ProvisionedTenantDirectory directory;

    public TenantContextResolver(ProvisionedTenantDirectory directory) {
        this.directory = directory;
    }

    /**
     * Resolve the immutable tenant context for one verified request. Never returns a defaulted or
     * partially-populated context — every non-happy path throws so the caller fails closed.
     */
    public TenantExecutionContext resolve(Jwt jwt) {
        String tenantId = jwt.getClaimAsString(TENANT_CLAIM);
        if (tenantId == null || tenantId.isBlank()) {
            throw new MissingTenantClaimException();
        }
        String actorTenantId = jwt.getClaimAsString(ACTOR_TENANT_CLAIM);

        if (!directory.hasSnapshot()) {
            throw new TenantDirectoryUnavailableException();
        }
        var provisioned = directory.find(tenantId)
                .orElseThrow(() -> new TenantNotProvisionedException(tenantId));

        return new TenantExecutionContext(tenantId, actorTenantId, provisioned.activePolicyVersion());
    }

    /** A verified principal carried no usable {@code tenant_id} claim → 401. */
    public static final class MissingTenantClaimException extends RuntimeException {
        public MissingTenantClaimException() {
            super("missing mandatory tenant_id claim");
        }
    }

    /** The asserted tenant is not in the provisioned set (unknown / deprovisioned) → 403. */
    public static final class TenantNotProvisionedException extends RuntimeException {
        private final String tenantId;
        public TenantNotProvisionedException(String tenantId) {
            super("tenant '" + tenantId + "' is not provisioned");
            this.tenantId = tenantId;
        }
        public String tenantId() { return tenantId; }
    }

    /** No provisioning snapshot has loaded yet — the gateway is not ready → 503. */
    public static final class TenantDirectoryUnavailableException extends RuntimeException {
        public TenantDirectoryUnavailableException() {
            super("tenant provisioning snapshot not yet available");
        }
    }
}
