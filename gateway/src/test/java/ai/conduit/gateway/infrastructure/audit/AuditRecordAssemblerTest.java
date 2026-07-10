package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.event.EntitlementCheckData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestCompleteData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecordAssemblerTest {

    private final AuditRecordAssembler assembler = new AuditRecordAssembler("test-1.2.3");

    private static TraceEvent event(String type, String reqId, String convId, Object data) {
        return new TraceEvent(type, reqId, convId, 1_700_000_000_000L, data);
    }

    private List<TraceEvent> aRequest() {
        return List.of(
                event("request_start", "req-1", "conv-9", new RequestStartData("rm_jane", "how are my holdings?")),
                event("entitlement_check", "req-1", "conv-9",
                        new EntitlementCheckData("REL-00188", "rm_jane", false, "not-in-book", "coverage")),
                event("request_complete", "req-1", "conv-9", new RequestCompleteData(1234, 3, 2)));
    }

    @Test
    void promotesTheDimensionsAnAuditorFiltersOn() {
        AuditRecord r = assembler.assemble(aRequest(), Instant.parse("2026-07-10T12:00:00Z"));

        assertThat(r.transactionId()).isEqualTo("req-1");
        assertThat(r.conversationId()).isEqualTo("conv-9");
        assertThat(r.principal().userId()).isEqualTo("rm_jane");
        assertThat(r.occurredAt()).isEqualTo("2026-07-10T12:00:00Z");
        assertThat(r.gatewayVersion()).isEqualTo("test-1.2.3");
    }

    @Test
    void derivesCountsFromTheTrace() {
        AuditRecord r = assembler.assemble(aRequest(), Instant.now());

        assertThat(r.counts().agentsOk()).isEqualTo(2);
        assertThat(r.counts().agentsFailed()).isEqualTo(1);   // agentCount 3 - successCount 2
        assertThat(r.counts().entitlementDenials()).isEqualTo(1);
    }

    @Test
    void derivesTheOutcomeFromTheTrace() {
        // a request with a succeeding agent → ANSWERED
        assertThat(assembler.assemble(aRequest(), Instant.now()).outcome()).isEqualTo("ANSWERED");

        // a fully-denied request (a denial, no agent succeeded) → DENIED
        List<TraceEvent> denied = List.of(
                event("request_start", "r", "c", new RequestStartData("rm_carlos", "whitman?")),
                event("check_denied", "r", "c", new java.util.HashMap<>()),
                event("request_complete", "r", "c", new RequestCompleteData(10, 0, 0)));
        assertThat(assembler.assemble(denied, Instant.now()).outcome()).isEqualTo("DENIED");

        // agents ran, none returned data → FAILED
        List<TraceEvent> failed = List.of(
                event("request_start", "r", "c", new RequestStartData("u", "p")),
                event("request_complete", "r", "c", new RequestCompleteData(10, 3, 0)));
        assertThat(assembler.assemble(failed, Instant.now()).outcome()).isEqualTo("FAILED");
    }

    @Test
    void carriesTheFullTraceAsThePayload() {
        List<TraceEvent> events = aRequest();
        AuditRecord r = assembler.assemble(events, Instant.now());
        assertThat(r.events()).isEqualTo(events);   // verbatim, nothing dropped
    }

    @Test
    void theContentHashIsPresentAndDeterministicForTheSameTrace() {
        List<TraceEvent> events = aRequest();
        String h1 = assembler.assemble(events, Instant.parse("2026-07-10T12:00:00Z")).contentSha256();
        String h2 = assembler.assemble(events, Instant.parse("2026-07-11T09:30:00Z")).contentSha256();

        assertThat(h1).hasSize(64);                 // sha-256 hex
        assertThat(h2)
                .as("the hash covers the trace, not the wall clock — same events, same hash")
                .isEqualTo(h1);
    }

    @Test
    void aDifferentTraceHashesDifferently() {
        String base = assembler.assemble(aRequest(), Instant.now()).contentSha256();
        List<TraceEvent> tampered = List.of(
                event("request_start", "req-1", "conv-9", new RequestStartData("rm_jane", "DIFFERENT prompt")),
                event("request_complete", "req-1", "conv-9", new RequestCompleteData(1, 1, 1)));
        String other = assembler.assemble(tampered, Instant.now()).contentSha256();

        assertThat(other).isNotEqualTo(base);
    }
}
