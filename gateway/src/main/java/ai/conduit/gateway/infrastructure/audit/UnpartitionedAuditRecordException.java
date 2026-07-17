package ai.conduit.gateway.infrastructure.audit;

/**
 * Thrown when an {@link AuditRecord} reaches a sink without a resolved tenant partition (Axiom A6).
 *
 * <p>The gateway has NO {@code tenant=unknown} / {@code tenant=default} fallback partition: a record
 * whose {@code partitionTenantId} is unresolved is rejected here rather than written to a shared
 * bucket, so a tenant-A examiner export can never contain a mis-filed record. The async writer meters
 * the rejection ({@code conduit.audit.write.failed}) as a monitored incident — a dead-letter, never a
 * silent shared-partition write.
 */
public class UnpartitionedAuditRecordException extends RuntimeException {

    public UnpartitionedAuditRecordException(String transactionId) {
        super("Audit record txn=" + transactionId + " has no resolved tenant partition — "
                + "rejected (no shared/unknown partition exists). This is an A6 invariant violation "
                + "upstream: the tenant must be resolved from the TenantExecutionContext before audit.");
    }
}
