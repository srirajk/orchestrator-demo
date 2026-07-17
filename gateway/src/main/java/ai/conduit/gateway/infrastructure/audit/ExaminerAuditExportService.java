package ai.conduit.gateway.infrastructure.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Examiner export of the immutable (WORM) audit records for ONE tenant (Axiom Story A6).
 *
 * <p>The export is <b>parameterized by tenant</b> and filters <b>strictly by the record's own
 * partition key</b> — the {@code tenant=} segment the sink baked into the object key
 * ({@link ObjectStoreAuditSink#objectKey}) — never a query-time guess over record content. A returned
 * record is additionally re-checked against its {@code partitionTenantId} (defense in depth): a record
 * that somehow disagrees with its key is dropped and logged, so a tenant-A export can never surface a
 * tenant-B record — even for a platform-level cross-tenant op, whose actor-view and subject-view were
 * written to their SEPARATE partitions.
 *
 * <p>Read side only: it never writes and never mutates a record (WORM). Present when
 * {@code conduit.audit.enabled=true}. The read is off any request path — an examiner/back-office call.
 */
@Component
@ConditionalOnProperty(name = "conduit.audit.enabled", havingValue = "true")
public class ExaminerAuditExportService {

    private static final Logger log = LoggerFactory.getLogger(ExaminerAuditExportService.class);

    private final ObjectMapper mapper;
    private final String bucket;
    private final String prefix;
    private final String endpoint;
    private final String region;
    private final boolean pathStyle;
    private final String accessKey;
    private final String secretKey;
    private final long apiCallTimeoutMs;
    private final long apiCallAttemptTimeoutMs;

    private S3Client s3;

    public ExaminerAuditExportService(
            ObjectMapper mapper,
            @Value("${conduit.audit.store.bucket:conduit-audit}") String bucket,
            @Value("${conduit.audit.store.prefix:audit}") String prefix,
            @Value("${conduit.audit.store.endpoint:}") String endpoint,
            @Value("${conduit.audit.store.region:us-east-1}") String region,
            @Value("${conduit.audit.store.path-style:true}") boolean pathStyle,
            @Value("${conduit.audit.store.access-key:}") String accessKey,
            @Value("${conduit.audit.store.secret-key:}") String secretKey,
            @Value("${conduit.audit.s3.api-call-timeout-ms:10000}") long apiCallTimeoutMs,
            @Value("${conduit.audit.s3.api-call-attempt-timeout-ms:5000}") long apiCallAttemptTimeoutMs) {
        this.mapper = mapper;
        this.bucket = bucket;
        this.prefix = prefix;
        this.endpoint = endpoint;
        this.region = region;
        this.pathStyle = pathStyle;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.apiCallTimeoutMs = apiCallTimeoutMs;
        this.apiCallAttemptTimeoutMs = apiCallAttemptTimeoutMs;
    }

    @PostConstruct
    public void init() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMs))
                        .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMs))
                        .build())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        this.s3 = builder.build();
        log.info("Examiner audit export ready → bucket='{}' prefix='{}'", bucket, prefix);
    }

    /** Export every record in {@code tenantId}'s partition (no additional filter). */
    public ExaminerExport export(String tenantId) {
        return export(tenantId, ExportFilter.all());
    }

    /**
     * Export {@code tenantId}'s WORM audit records matching {@code filter}. The partition scope is
     * mandatory and inviolable — {@code tenantId} must be a resolved (non-blank) tenant.
     */
    public ExaminerExport export(String tenantId, ExportFilter filter) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Examiner export requires a resolved tenant partition");
        }
        ExportFilter f = filter != null ? filter : ExportFilter.all();
        List<String> keys = new ArrayList<>();
        List<AuditRecord> records = new ArrayList<>();

        String continuation = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix + "/");
            if (continuation != null) req.continuationToken(continuation);
            ListObjectsV2Response resp = s3.listObjectsV2(req.build());

            for (S3Object obj : resp.contents()) {
                String key = obj.key();
                // Strict partition filter: the tenant segment is part of the object KEY the sink wrote,
                // not a guess over content. Only keys under this exact partition are candidates.
                if (!keyBelongsToTenant(key, tenantId)) continue;

                AuditRecord record = fetch(key);
                if (record == null) continue;

                // Defense in depth: the record's own partition key must agree with its object key.
                if (!tenantId.equals(record.partitionTenantId())) {
                    log.error("Examiner export: record at key {} claims partition {} but sits in tenant={} — "
                                    + "dropping (A6 isolation guard)", key, record.partitionTenantId(), tenantId);
                    continue;
                }
                if (!f.matches(record)) continue;

                keys.add(key);
                records.add(record);
            }
            continuation = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
        } while (continuation != null);

        keys.sort(String::compareTo);
        return new ExaminerExport(tenantId, List.copyOf(keys), List.copyOf(records));
    }

    private AuditRecord fetch(String key) {
        try {
            byte[] body = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
            return mapper.readValue(body, AuditRecord.class);
        } catch (Exception e) {
            log.error("Examiner export: could not read audit object {}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * True iff {@code key} sits under the {@code tenant=<tenantId>} partition segment. Exact match on
     * the whole segment (so {@code tenant=acme} never matches {@code tenant=acme-eu}).
     */
    static boolean keyBelongsToTenant(String key, String tenantId) {
        String needle = "/tenant=" + tenantId + "/";
        return key.contains(needle);
    }

    /** The result of an examiner export: the tenant scope, the object keys (the diffed listing), the records. */
    public record ExaminerExport(String tenantId, List<String> objectKeys, List<AuditRecord> records) {}

    /** Optional post-partition filter: inclusive date bounds (UTC) and/or an outcome. */
    public record ExportFilter(LocalDate fromInclusive, LocalDate toInclusive, String outcome) {

        public static ExportFilter all() {
            return new ExportFilter(null, null, null);
        }

        boolean matches(AuditRecord r) {
            if (outcome != null && !outcome.equalsIgnoreCase(r.outcome())) return false;
            if (fromInclusive == null && toInclusive == null) return true;
            LocalDate dt;
            try {
                dt = java.time.OffsetDateTime.parse(r.occurredAt()).toLocalDate();
            } catch (Exception e) {
                return false;   // an unparseable timestamp cannot satisfy a date bound
            }
            if (fromInclusive != null && dt.isBefore(fromInclusive)) return false;
            return toInclusive == null || !dt.isAfter(toInclusive);
        }
    }
}
