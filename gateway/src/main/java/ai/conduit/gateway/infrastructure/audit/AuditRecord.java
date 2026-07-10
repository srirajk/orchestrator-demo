package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;

import java.util.List;

/**
 * The immutable audit record for one request — the regulator-facing system of record.
 *
 * <p>One object per request, written to WORM object storage. Top-level fields are promoted
 * dimensions a query engine filters on without unnesting; {@code events} carries the full decision
 * trace verbatim. See {@code docs/specs/AUDIT-RECORD-SPEC.md}.
 *
 * <p>The envelope is the expensive-to-change part, because the objects are immutable — a field added
 * later is absent from every record already written. {@code schemaVersion} exists to make an
 * eventual breaking change legible, not to invite churn.
 */
public record AuditRecord(
        String schemaVersion,
        String transactionId,     // = requestId / correlation id — the lookup + partition key
        String conversationId,
        String occurredAt,        // ISO-8601 UTC
        Principal principal,
        String outcome,           // ANSWERED | FAILED | CLARIFY | UNKNOWN
        Counts counts,
        String gatewayVersion,
        List<TraceEvent> events,  // the full ordered decision trace, verbatim
        String contentSha256      // sha256 over the canonical-JSON events, for tamper-evidence
) {
    public record Principal(String userId, String tenantId) {}

    public record Counts(int agentsOk, int agentsFailed, int entitlementDenials) {}
}
