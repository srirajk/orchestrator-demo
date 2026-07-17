package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds one or more {@link AuditRecord}s from one request's decision trace.
 *
 * <p>Pure and deterministic given the events (and a stamped {@code occurredAt} — passed in rather
 * than read from the clock, so the record is reproducible and the content hash is stable). The
 * promoted dimensions — principal, tenant partition, outcome, counts — are derived from the events
 * here, so the later analytical layer can filter on them without parsing every payload.
 *
 * <p><b>Tenant partitioning (Axiom A6).</b> Every record carries a {@code partitionTenantId} — the
 * tenant whose audit partition it belongs in, read from the explicit {@code tenant_context} event
 * (the A2 {@link ai.conduit.gateway.domain.auth.TenantExecutionContext}), never guessed at write
 * time. A <b>direct</b> call yields ONE record in the subject partition. A <b>delegated cross-tenant</b>
 * op (actor tenant ≠ subject tenant) yields TWO minimally-redacted records sharing one
 * {@code delegationId}: an actor-view in the actor partition (its subject payload redacted) and a
 * subject-view in the subject partition (the actor's identity redacted). Neither is a shared
 * full-payload record.
 *
 * <p>Runs on the audit drain thread, never on the request path.
 */
@Component
public class AuditRecordAssembler {

    static final String SCHEMA_VERSION = "1";

    static final String VIEW_ACTOR   = "actor";
    static final String VIEW_SUBJECT = "subject";
    /** Marker text substituted for a redacted value — carries no other-tenant payload. */
    static final String REDACTED = "[REDACTED]";
    private static final String TENANT_CONTEXT_EVENT = "tenant_context";

    private final ObjectMapper canonicalMapper;
    private final String gatewayVersion;

    public AuditRecordAssembler(@Value("${conduit.gateway.version:0.1.0}") String gatewayVersion) {
        this.gatewayVersion = gatewayVersion;
        // Sorted keys → a canonical, reproducible serialization, so the content hash is stable.
        this.canonicalMapper = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * The primary record for a request — the subject/execution-tenant view (the only record for a
     * direct call). Kept for callers and tests that expect one record; the async writer uses
     * {@link #assembleAll} so a delegated cross-tenant op writes both partition views.
     */
    public AuditRecord assemble(List<TraceEvent> events, Instant occurredAt) {
        return assembleAll(events, occurredAt).get(0);
    }

    /**
     * All records this request must persist: one for a direct call, two (actor-view + subject-view)
     * for a delegated cross-tenant op. The subject/primary view is always element 0.
     */
    public List<AuditRecord> assembleAll(List<TraceEvent> events, Instant occurredAt) {
        Derived d = derive(events);

        boolean crossTenant = d.actorTenantId != null && !d.actorTenantId.isBlank()
                && d.subjectTenantId != null && !d.subjectTenantId.isBlank()
                && !d.actorTenantId.equals(d.subjectTenantId);

        if (!crossTenant) {
            // Direct call: one record, partitioned on the subject (execution) tenant, full payload.
            AuditRecord primary = build(d, occurredAt,
                    new AuditRecord.Principal(d.userId, d.subjectTenantId),
                    d.subjectTenantId, /*view*/ null, events);
            return List.of(primary);
        }

        // Delegated cross-tenant op. Two partitioned, minimally-redacted views, one shared delegation id.
        // Subject-view (element 0 / primary): the subject tenant owns the payload; the actor's identity
        // is redacted to the delegation reference so the subject examiner sees no actor-tenant identity.
        List<TraceEvent> subjectEvents = maskValue(events, d.userId);
        AuditRecord subjectView = build(d, occurredAt,
                new AuditRecord.Principal(REDACTED, d.subjectTenantId),
                d.subjectTenantId, VIEW_SUBJECT, subjectEvents);

        // Actor-view: written to the actor partition, carries the actor's own action metadata but NO
        // subject-tenant business payload — the events are replaced with a redaction marker.
        List<TraceEvent> actorEvents = List.of(new TraceEvent(
                "redacted", d.transactionId, d.conversationId, occurredAt.toEpochMilli(),
                Map.of("reason", "cross-tenant-actor-view",
                        "delegationId", d.delegationId != null ? d.delegationId : "",
                        "subjectTenantId", d.subjectTenantId)));
        AuditRecord actorView = build(d, occurredAt,
                new AuditRecord.Principal(d.userId, d.actorTenantId),
                d.actorTenantId, VIEW_ACTOR, actorEvents);

        return List.of(subjectView, actorView);
    }

    // ── record construction ─────────────────────────────────────────────────────

    private AuditRecord build(Derived d, Instant occurredAt, AuditRecord.Principal principal,
                              String partitionTenantId, String view, List<TraceEvent> viewEvents) {
        return new AuditRecord(
                SCHEMA_VERSION,
                d.transactionId,
                d.conversationId,
                occurredAt.toString(),
                principal,
                d.actorTenantId,
                d.subjectTenantId,
                partitionTenantId,
                view,
                d.delegationId,
                d.activePolicyVersion,
                List.copyOf(d.cerbosCallIds),
                d.outcome,
                new AuditRecord.Counts(d.agentsOk, d.agentsFailed, d.denials),
                gatewayVersion,
                viewEvents,
                sha256(viewEvents));
    }

    // ── derivation ──────────────────────────────────────────────────────────────

    /** The promoted dimensions distilled from the trace, before per-view redaction. */
    private static final class Derived {
        String transactionId;
        String conversationId;
        String userId;
        String subjectTenantId;
        String actorTenantId;
        String activePolicyVersion;
        String delegationId;
        final Set<String> cerbosCallIds = new LinkedHashSet<>();
        int agentsOk;
        int agentsFailed;
        int denials;
        String outcome;
    }

    private Derived derive(List<TraceEvent> events) {
        Derived d = new Derived();
        d.transactionId = firstNonNull(events, TraceEvent::requestId, "unknown");
        d.conversationId = firstNonNull(events, TraceEvent::conversationId, null);

        int agentCount = 0;
        for (TraceEvent e : events) {
            JsonNode data = canonicalMapper.valueToTree(e.data());
            switch (e.type()) {
                case "request_start" -> {
                    if (d.userId == null) d.userId = text(data, "userId");
                    // request_start carries the subject tenant for a request that predates tenant_context.
                    if (d.subjectTenantId == null) d.subjectTenantId = text(data, "tenantId");
                }
                case TENANT_CONTEXT_EVENT -> {
                    String subj = text(data, "subjectTenantId");
                    if (subj != null) d.subjectTenantId = subj;         // authoritative over request_start
                    String actor = text(data, "actorTenantId");
                    if (actor != null) d.actorTenantId = actor;
                    String ver = text(data, "activePolicyVersion");
                    if (ver != null) d.activePolicyVersion = ver;
                    String del = text(data, "delegationId");
                    if (del != null) d.delegationId = del;
                }
                case "entitlement_check" -> {
                    if (d.userId == null) d.userId = text(data, "userId");
                    if (data.path("allowed").isBoolean() && !data.path("allowed").asBoolean()) d.denials++;
                }
                case "check_denied" -> d.denials++;
                case "request_complete" -> {
                    d.agentsOk = data.path("successCount").asInt(d.agentsOk);
                    agentCount = data.path("agentCount").asInt(agentCount);
                }
                default -> { /* other event types contribute only to the payload */ }
            }
            // A Cerbos decision id can ride any authz-related event; collect it wherever it appears.
            collectCerbosCallIds(data, d.cerbosCallIds);
        }

        // actor tenant defaults to the subject tenant for a direct (non-delegated) call.
        if (d.actorTenantId == null || d.actorTenantId.isBlank()) d.actorTenantId = d.subjectTenantId;

        d.agentsFailed = Math.max(0, agentCount - d.agentsOk);

        // The outcome is emitted only as a metric tag, not a trace event, so it is derived here.
        if (d.agentsOk > 0)        d.outcome = "ANSWERED";
        else if (d.denials > 0)    d.outcome = "DENIED";
        else if (agentCount > 0)   d.outcome = "FAILED";    // agents ran, none returned data
        else                       d.outcome = "NO_AGENTS"; // clarify / no route / nothing to run

        return d;
    }

    /** Collect a {@code cerbosCallId} (single or array) from anywhere in an event's data tree. */
    private static void collectCerbosCallIds(JsonNode node, Set<String> out) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode v = node.get("cerbosCallId");
            if (v != null && v.isTextual() && !v.asText().isBlank()) out.add(v.asText());
            JsonNode arr = node.get("cerbosCallIds");
            if (arr != null && arr.isArray()) {
                for (JsonNode x : arr) if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText());
            }
            node.forEach(child -> collectCerbosCallIds(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectCerbosCallIds(child, out));
        }
    }

    // ── redaction ─────────────────────────────────────────────────────────────

    /**
     * Return a copy of {@code events} with every textual value equal to {@code secret} replaced by
     * {@link #REDACTED}. Used for the subject-view so the actor principal's identity does not leak
     * into the subject tenant's partition. A blank secret is a no-op (nothing to redact).
     */
    private List<TraceEvent> maskValue(List<TraceEvent> events, String secret) {
        if (secret == null || secret.isBlank()) return events;
        List<TraceEvent> out = new ArrayList<>(events.size());
        for (TraceEvent e : events) {
            JsonNode data = canonicalMapper.valueToTree(e.data());
            JsonNode masked = maskNode(data, secret);
            out.add(new TraceEvent(e.type(), e.requestId(), e.conversationId(), e.timestamp(), masked));
        }
        return out;
    }

    private JsonNode maskNode(JsonNode node, String secret) {
        if (node == null || node.isNull()) return node;
        if (node.isTextual()) {
            return secret.equals(node.asText()) ? new TextNode(REDACTED) : node;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.fieldNames().forEachRemaining(f -> obj.set(f, maskNode(obj.get(f), secret)));
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) arr.set(i, maskNode(arr.get(i), secret));
            return arr;
        }
        return node;
    }

    // ── hashing ──────────────────────────────────────────────────────────────

    private String sha256(List<TraceEvent> events) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(events);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (Exception e) {
            // A record must always be produced; a hash failure degrades tamper-evidence, not capture.
            return "";
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }

    private static <T> String firstNonNull(List<TraceEvent> events,
                                           java.util.function.Function<TraceEvent, String> f,
                                           String fallback) {
        for (TraceEvent e : events) {
            String v = f.apply(e);
            if (v != null && !v.isBlank()) return v;
        }
        return fallback;
    }
}
