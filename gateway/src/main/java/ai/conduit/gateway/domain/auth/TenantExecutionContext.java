package ai.conduit.gateway.domain.auth;

/**
 * The single, immutable carrier of a request's tenant scope (Axiom Story A2).
 *
 * <p>Read ONCE on the servlet thread — from the verified JWT via {@link TenantContextResolver},
 * validated against the {@link ProvisionedTenantDirectory} — and then threaded EXPLICITLY through
 * the request path (chat → routing → coverage → invocation → Redis → audit). Downstream production
 * code receives this value as a parameter; it must never recover the tenant from a static holder.
 * A filter-local {@link RequestContext} holder exists only so the controller can capture this value
 * on the servlet thread before the virtual-thread boundary, and it is cleared when the request ends.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code tenantId} — the canonical tenant this request operates in (JWT {@code tenant_id}).</li>
 *   <li>{@code actorTenantId} — the tenant of the acting principal; equals {@code tenantId} for a
 *       direct call. Distinct only for a (future) verified delegation token.</li>
 *   <li>{@code activePolicyVersion} — the tenant's active policy/bundle version as captured from the
 *       provisioned snapshot at resolution time, so every hop of one request sees the same version
 *       even if the background snapshot swaps mid-flight.</li>
 * </ul>
 */
public record TenantExecutionContext(
        String tenantId,
        String actorTenantId,
        String activePolicyVersion
) {
    public TenantExecutionContext {
        if (tenantId != null) tenantId = tenantId.trim();
        if (actorTenantId == null || actorTenantId.isBlank()) actorTenantId = tenantId;
    }

    /**
     * A fully-resolved context carries both a tenant and the policy version captured for it.
     * The invocation checkpoint ({@code GovernedInvoker}) refuses any hop whose context is not
     * resolved, so a half-populated or absent tenant fails closed before authz/invoke.
     */
    public boolean isResolved() {
        return tenantId != null && !tenantId.isBlank()
                && activePolicyVersion != null && !activePolicyVersion.isBlank();
    }

    public static TenantExecutionContext of(String tenantId, String actorTenantId,
                                            String activePolicyVersion) {
        return new TenantExecutionContext(tenantId, actorTenantId, activePolicyVersion);
    }
}
