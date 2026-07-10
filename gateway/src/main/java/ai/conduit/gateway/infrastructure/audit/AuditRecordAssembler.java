package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds an {@link AuditRecord} from one request's decision trace.
 *
 * <p>Pure and deterministic given the events (and a stamped {@code occurredAt} — passed in rather
 * than read from the clock, so the record is reproducible and the content hash is stable). The
 * promoted dimensions — principal, outcome, counts — are derived from the events here, so the later
 * analytical layer can filter on them without parsing every payload.
 *
 * <p>Runs on the audit drain thread, never on the request path.
 */
@Component
public class AuditRecordAssembler {

    static final String SCHEMA_VERSION = "1";

    private final ObjectMapper canonicalMapper;
    private final String gatewayVersion;

    public AuditRecordAssembler(@Value("${conduit.gateway.version:0.1.0}") String gatewayVersion) {
        this.gatewayVersion = gatewayVersion;
        // Sorted keys → a canonical, reproducible serialization, so the content hash is stable.
        this.canonicalMapper = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public AuditRecord assemble(List<TraceEvent> events, Instant occurredAt) {
        String transactionId = firstNonNull(events, TraceEvent::requestId, "unknown");
        String conversationId = firstNonNull(events, TraceEvent::conversationId, null);

        String userId = null;
        String tenantId = null;
        int agentsOk = 0;
        int agentCount = 0;
        int denials = 0;

        for (TraceEvent e : events) {
            JsonNode d = canonicalMapper.valueToTree(e.data());
            switch (e.type()) {
                case "request_start" -> {
                    if (userId == null) userId = text(d, "userId");
                }
                case "entitlement_check" -> {
                    if (userId == null) userId = text(d, "userId");
                    if (d.path("allowed").isBoolean() && !d.path("allowed").asBoolean()) denials++;
                }
                case "check_denied" -> denials++;
                case "request_complete" -> {
                    agentsOk = d.path("successCount").asInt(agentsOk);
                    agentCount = d.path("agentCount").asInt(agentCount);
                }
                default -> { /* other event types contribute only to the payload */ }
            }
            if (tenantId == null) tenantId = text(d, "tenantId");
        }

        int agentsFailed = Math.max(0, agentCount - agentsOk);

        // The outcome is emitted only as a metric tag, not a trace event, so it is derived here from
        // the events. This is a promoted convenience dimension — the authoritative record is the full
        // event payload; a query engine uses this to filter without unnesting.
        String outcome;
        if (agentsOk > 0)        outcome = "ANSWERED";
        else if (denials > 0)    outcome = "DENIED";
        else if (agentCount > 0) outcome = "FAILED";   // agents ran, none returned data
        else                     outcome = "NO_AGENTS"; // clarify / no route / nothing to run

        String contentSha256 = sha256(events);

        return new AuditRecord(
                SCHEMA_VERSION,
                transactionId,
                conversationId,
                occurredAt.toString(),
                new AuditRecord.Principal(userId, tenantId),
                outcome,
                new AuditRecord.Counts(agentsOk, agentsFailed, denials),
                gatewayVersion,
                events,
                contentSha256);
    }

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
