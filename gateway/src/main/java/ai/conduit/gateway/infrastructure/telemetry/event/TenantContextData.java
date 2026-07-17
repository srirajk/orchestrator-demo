package ai.conduit.gateway.infrastructure.telemetry.event;

/**
 * {@code tenant_context} trace payload (Axiom Story A6). Carries the request's resolved tenant scope
 * — captured from the explicit {@link ai.conduit.gateway.domain.auth.TenantExecutionContext} (A2) on
 * the servlet thread — so the audit record, which is assembled purely from the trace, partitions on
 * the EXACT tenant the request executed under rather than guessing at write time.
 *
 * <p>Fields carry no domain knowledge — they are opaque tenant/version identifiers (World B):
 * <ul>
 *   <li>{@code subjectTenantId} — the execution/subject tenant (the tenant whose data is operated on);
 *       this is the audit partition key for a direct call.</li>
 *   <li>{@code actorTenantId} — the acting principal's tenant. Equal to {@code subjectTenantId} for a
 *       direct call; distinct only for a verified cross-tenant delegation.</li>
 *   <li>{@code activePolicyVersion} — the immutable policy/bundle version captured at resolution time;
 *       the join key a later stage (C5) uses to tie a Cerbos decision back to the bundle it ran.</li>
 *   <li>{@code delegationId} — non-null only for a delegated cross-tenant op; the shared id that links
 *       the actor-partition and subject-partition views of the SAME operation.</li>
 * </ul>
 */
public record TenantContextData(
        String subjectTenantId,
        String actorTenantId,
        String activePolicyVersion,
        String delegationId
) {
    /** Direct (non-delegated) call: actor tenant equals subject tenant, no delegation id. */
    public TenantContextData(String subjectTenantId, String activePolicyVersion) {
        this(subjectTenantId, subjectTenantId, activePolicyVersion, null);
    }
}
