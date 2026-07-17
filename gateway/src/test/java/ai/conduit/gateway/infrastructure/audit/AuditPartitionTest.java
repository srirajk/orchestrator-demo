package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestCompleteData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData;
import ai.conduit.gateway.infrastructure.telemetry.event.TenantContextData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Axiom A6.1 — <b>every</b> audit write path produces a tenant-partitioned record; there is NO
 * {@code tenant=unknown} / {@code tenant=default} fallback partition. Red if any assembler branch omits
 * the tenant key or the sink invents a fallback partition for an un-resolved record.
 */
class AuditPartitionTest {

    private final AuditRecordAssembler assembler = new AuditRecordAssembler("test-a6");

    /** The sink's key builder is pure (no S3) — {@code objectLockMode=none} so nothing else initializes. */
    private final ObjectStoreAuditSink sink = new ObjectStoreAuditSink(
            "conduit-audit", "audit", "", "us-east-1", true, "", "", 2555, "none", 10_000, 5_000);

    private static TraceEvent event(String type, String reqId, String convId, Object data) {
        return new TraceEvent(type, reqId, convId, 1_700_000_000_000L, data);
    }

    private static final Instant AT = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void everyRecordHasTenantPartitionFromTheExplicitTenantContext() {
        List<TraceEvent> events = List.of(
                event("request_start", "req-1", "conv-9", new RequestStartData("u-1", "acme", "hi")),
                event("tenant_context", "req-1", "conv-9",
                        new TenantContextData("acme", "v-2026-07")),
                event("request_complete", "req-1", "conv-9", new RequestCompleteData(12, 2, 2)));

        List<AuditRecord> records = assembler.assembleAll(events, AT);

        assertThat(records).hasSize(1);
        AuditRecord r = records.get(0);
        assertThat(r.hasResolvedPartition()).isTrue();
        assertThat(r.partitionTenantId()).isEqualTo("acme");
        assertThat(r.subjectTenantId()).isEqualTo("acme");
        assertThat(r.actorTenantId()).isEqualTo("acme");
        assertThat(r.activePolicyVersion()).isEqualTo("v-2026-07");
        // The object key carries the partition segment — never "unknown".
        assertThat(sink.objectKey(r)).contains("/tenant=acme/").doesNotContain("tenant=unknown");
    }

    @Test
    void theDefaultTenantDemoPathStillWritesAValidPartition() {
        // The single-tenant demo: A2's default TenantExecutionContext resolves a real tenant ("default"),
        // carried on request_start even without a tenant_context event. The record must partition under
        // "default", NOT be rejected — removing the unknown fallback must not break the demo audit path.
        List<TraceEvent> demo = List.of(
                event("request_start", "req-demo", "conv-1", new RequestStartData("rm_jane", "default", "how are my holdings?")),
                event("request_complete", "req-demo", "conv-1", new RequestCompleteData(900, 1, 1)));

        AuditRecord r = assembler.assemble(demo, AT);

        assertThat(r.hasResolvedPartition()).as("demo record IS partitioned").isTrue();
        assertThat(r.partitionTenantId()).isEqualTo("default");
        assertThat(sink.objectKey(r))
                .as("demo record writes under tenant=default, a valid partition")
                .contains("/tenant=default/")
                .doesNotContain("tenant=unknown");
    }

    @Test
    void anUnresolvedRecordIsRejectedNeverWrittenToASharedPartition() {
        // A request with NO resolved tenant (neither tenant_context nor a request_start tenant) yields a
        // blank partition. The sink REJECTS it — there is no shared/unknown partition to fall back to.
        List<TraceEvent> noTenant = List.of(
                event("request_start", "req-x", "conv-x", new RequestStartData("u", "prompt")), // 2-arg → tenant null
                event("request_complete", "req-x", "conv-x", new RequestCompleteData(10, 0, 0)));

        AuditRecord r = assembler.assemble(noTenant, AT);

        assertThat(r.hasResolvedPartition()).isFalse();
        assertThatThrownBy(() -> sink.objectKey(r))
                .isInstanceOf(UnpartitionedAuditRecordException.class)
                .hasMessageContaining("no resolved tenant partition");
    }

    @Test
    void aDelegatedCrossTenantOpProducesTwoPartitionedViewsSharingADelegationId() {
        List<TraceEvent> events = List.of(
                event("request_start", "req-2", "conv-2", new RequestStartData("actor-user", "actor-co", "act for B")),
                event("tenant_context", "req-2", "conv-2",
                        new TenantContextData("subject-co", "actor-co", "v-9", "deleg-123")),
                event("request_complete", "req-2", "conv-2", new RequestCompleteData(30, 1, 1)));

        List<AuditRecord> records = assembler.assembleAll(events, AT);

        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.hasResolvedPartition()).isTrue();
            assertThat(r.delegationId()).isEqualTo("deleg-123");
        });
        // one lands in the subject partition, the other in the actor partition — distinct keys.
        List<String> partitions = records.stream().map(AuditRecord::partitionTenantId).sorted().toList();
        assertThat(partitions).containsExactly("actor-co", "subject-co");
        List<String> keys = records.stream().map(sink::objectKey).toList();
        assertThat(keys).anyMatch(k -> k.contains("/tenant=actor-co/") && k.contains("-actor.json"));
        assertThat(keys).anyMatch(k -> k.contains("/tenant=subject-co/") && k.contains("-subject.json"));
    }
}
