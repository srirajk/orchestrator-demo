package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;

import java.util.List;

/**
 * The immutable audit record for one request — the regulator-facing system of record.
 *
 * <p>One object per request, written to WORM object storage under a <em>tenant-partitioned</em> key.
 * Top-level fields are promoted dimensions a query engine filters on without unnesting; {@code events}
 * carries the full decision trace verbatim. See {@code docs/specs/AUDIT-RECORD-SPEC.md}.
 *
 * <p>Axiom Story A6 — tenant partitioning is a first-class, mandatory dimension:
 * <ul>
 *   <li>{@code subjectTenantId} — the execution/subject tenant (the tenant whose data was operated on).</li>
 *   <li>{@code actorTenantId} — the acting principal's tenant; equals {@code subjectTenantId} for a
 *       direct call, distinct only for a verified cross-tenant delegation.</li>
 *   <li>{@code partitionTenantId} — the tenant whose audit partition this record belongs in. It is the
 *       record's OWN key, never a query-time guess: the sink writes under exactly this partition and
 *       the examiner export filters by exactly this key. A record whose partition is unresolved is
 *       <em>rejected / dead-lettered</em> by the sink — it never enters a shared or {@code unknown}
 *       partition.</li>
 *   <li>{@code view} — {@code null} for a normal record; {@code "actor"} / {@code "subject"} for the two
 *       minimally-redacted views a delegated cross-tenant op writes to the actor and subject partitions.</li>
 *   <li>{@code delegationId} — non-null only for a delegated cross-tenant op; the shared id linking the
 *       actor-view and subject-view of the SAME operation across their two partitions.</li>
 *   <li>{@code activePolicyVersion} — the immutable policy/bundle version the request ran under.</li>
 *   <li>{@code cerbosCallIds} — every Cerbos decision {@code cerbosCallId} captured on this request, the
 *       join key back to the durable decision log (C5 completes the bundle join).</li>
 * </ul>
 *
 * <p>The envelope is the expensive-to-change part, because the objects are immutable — a field added
 * later is absent from every record already written. {@code schemaVersion} exists to make an
 * eventual breaking change legible, not to invite churn.
 */
public record AuditRecord(
        String schemaVersion,
        String transactionId,     // = requestId / correlation id — the lookup key
        String conversationId,
        String occurredAt,        // ISO-8601 UTC
        Principal principal,
        String actorTenantId,       // acting principal's tenant (A2)
        String subjectTenantId,     // execution/subject tenant (A2) — the data being operated on
        String partitionTenantId,   // the tenant partition this record is written to — never "unknown"
        String view,                // null | "actor" | "subject" (delegated cross-tenant views)
        String delegationId,        // non-null only for a delegated cross-tenant op
        String activePolicyVersion, // immutable bundle key the request ran under
        List<String> cerbosCallIds, // decision-log join keys captured on this request
        String outcome,           // ANSWERED | FAILED | CLARIFY | UNKNOWN
        Counts counts,
        String gatewayVersion,
        List<TraceEvent> events,  // the full ordered decision trace, verbatim
        String contentSha256      // sha256 over the canonical-JSON events, for tamper-evidence
) {
    public record Principal(String userId, String tenantId) {}

    public record Counts(int agentsOk, int agentsFailed, int entitlementDenials) {}

    /** True when this record carries a resolved partition key — the precondition for a durable write. */
    public boolean hasResolvedPartition() {
        return partitionTenantId != null && !partitionTenantId.isBlank();
    }
}
