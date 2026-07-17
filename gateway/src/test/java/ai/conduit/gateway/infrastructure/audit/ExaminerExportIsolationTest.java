package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestCompleteData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData;
import ai.conduit.gateway.infrastructure.telemetry.event.TenantContextData;
import ai.conduit.gateway.testsupport.MinioContainerTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Axiom A6.2 / A6.3 — an examiner export for tenant A contains ZERO tenant-B records, and a
 * platform-level cross-tenant op is visible to BOTH scopes without leaking the other tenant's payload.
 *
 * <p>End-to-end against a throwaway MinIO: records are written by the real {@link ObjectStoreAuditSink}
 * and read back by the real {@link ExaminerAuditExportService}, so the isolation is proven over the
 * actual object-store key layout, not a mock.
 */
class ExaminerExportIsolationTest extends MinioContainerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuditRecordAssembler assembler = new AuditRecordAssembler("test-a6");
    private static final Instant AT = Instant.parse("2026-07-16T12:00:00Z");

    private static TraceEvent event(String type, String reqId, Object data) {
        return new TraceEvent(type, reqId, "conv-" + reqId, 1_700_000_000_000L, data);
    }

    private ObjectStoreAuditSink sink(String bucket) {
        ObjectStoreAuditSink s = new ObjectStoreAuditSink(
                bucket, "audit", minioEndpoint(), "us-east-1", true, ACCESS_KEY, SECRET_KEY,
                2555, "none", 10_000, 5_000);
        s.init();
        return s;
    }

    private ExaminerAuditExportService exporter(String bucket) {
        ExaminerAuditExportService e = new ExaminerAuditExportService(
                mapper, bucket, "audit", minioEndpoint(), "us-east-1", true, ACCESS_KEY, SECRET_KEY,
                10_000, 5_000);
        e.init();
        return e;
    }

    private void createBucket(String bucket) {
        try (S3Client s3 = S3Client.builder()
                .region(Region.of("us-east-1"))
                .endpointOverride(URI.create(minioEndpoint()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build()) {
            s3.createBucket(b -> b.bucket(bucket));
        }
    }

    /** A normal single-tenant request's records for {@code tenant}. */
    private void writeRequest(ObjectStoreAuditSink sink, String tenant, String reqId) throws Exception {
        List<TraceEvent> events = List.of(
                event("request_start", reqId, new RequestStartData("u-" + tenant, tenant, "hi")),
                event("tenant_context", reqId, new TenantContextData(tenant, "v-1")),
                event("request_complete", reqId, new RequestCompleteData(10, 1, 1)));
        for (AuditRecord r : assembler.assembleAll(events, AT)) {
            sink.write(r);
        }
    }

    @Test
    void exportContainsZeroOtherTenantRecords() throws Exception {
        String bucket = "audit-iso-" + UUID.randomUUID();
        createBucket(bucket);
        ObjectStoreAuditSink sink = sink(bucket);

        writeRequest(sink, "acme", "reqA1");
        writeRequest(sink, "acme", "reqA2");
        writeRequest(sink, "globex", "reqB1");
        writeRequest(sink, "globex", "reqB2");
        writeRequest(sink, "globex", "reqB3");

        var exportA = exporter(bucket).export("acme");
        var exportB = exporter(bucket).export("globex");

        // A6.2: A's export is exactly A's two records — zero globex.
        assertThat(exportA.records()).hasSize(2);
        assertThat(exportA.records()).allSatisfy(r -> assertThat(r.partitionTenantId()).isEqualTo("acme"));
        assertThat(exportA.objectKeys()).allSatisfy(k -> assertThat(k).contains("/tenant=acme/"));
        assertThat(exportA.objectKeys()).noneMatch(k -> k.contains("/tenant=globex/"));

        // Diffed listing: the two exports' object keys are DISJOINT — no key appears in both.
        assertThat(exportA.objectKeys()).doesNotContainAnyElementsOf(exportB.objectKeys());
        assertThat(exportB.objectKeys()).hasSize(3);
        assertThat(exportB.objectKeys()).allSatisfy(k -> assertThat(k).contains("/tenant=globex/"));
    }

    @Test
    void crossTenantOpVisibleToBothScopesWithoutLeak() throws Exception {
        String bucket = "audit-cross-" + UUID.randomUUID();
        createBucket(bucket);
        ObjectStoreAuditSink sink = sink(bucket);

        String actorIdentity = "ACTOR-IDENTITY-SENTINEL";
        String subjectPayload = "SUBJECT-PAYLOAD-SENTINEL";

        // A delegated platform op: actor tenant "actor-co" acts on subject tenant "subject-co".
        List<TraceEvent> events = List.of(
                event("request_start", "reqX", new RequestStartData(actorIdentity, "actor-co", "act for subject")),
                event("tenant_context", "reqX",
                        new TenantContextData("subject-co", "actor-co", "v-9", "deleg-777")),
                event("agent_complete", "reqX", Map.of("payload", subjectPayload)),
                event("request_complete", "reqX", new RequestCompleteData(20, 1, 1)));
        for (AuditRecord r : assembler.assembleAll(events, AT)) {
            sink.write(r);
        }

        var actorExport = exporter(bucket).export("actor-co");
        var subjectExport = exporter(bucket).export("subject-co");

        // Both scopes see the op...
        assertThat(actorExport.records()).hasSize(1);
        assertThat(subjectExport.records()).hasSize(1);
        AuditRecord actorView = actorExport.records().get(0);
        AuditRecord subjectView = subjectExport.records().get(0);

        // ...linked by the SAME delegation id...
        assertThat(actorView.delegationId()).isEqualTo("deleg-777");
        assertThat(subjectView.delegationId()).isEqualTo("deleg-777");
        assertThat(actorView.view()).isEqualTo("actor");
        assertThat(subjectView.view()).isEqualTo("subject");

        // ...but neither export leaks the OTHER tenant's payload.
        String actorJson = mapper.writeValueAsString(actorView);
        String subjectJson = mapper.writeValueAsString(subjectView);

        assertThat(actorJson)
                .as("actor-partition export carries no subject-tenant business payload")
                .doesNotContain(subjectPayload);
        assertThat(subjectJson)
                .as("subject-partition export carries no actor identity")
                .doesNotContain(actorIdentity);

        // And the actor keeps its own action metadata; the subject keeps its own payload.
        assertThat(subjectJson).contains(subjectPayload);
        assertThat(actorJson).contains(actorIdentity);

        // Cross-check the listings are strictly disjoint.
        assertThat(actorExport.objectKeys()).doesNotContainAnyElementsOf(subjectExport.objectKeys());
        assertThat(actorExport.objectKeys()).allMatch(k -> k.contains("/tenant=actor-co/"));
        assertThat(subjectExport.objectKeys()).allMatch(k -> k.contains("/tenant=subject-co/"));
    }
}
