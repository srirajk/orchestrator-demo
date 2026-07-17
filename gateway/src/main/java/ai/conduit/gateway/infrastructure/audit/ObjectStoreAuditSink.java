package ai.conduit.gateway.infrastructure.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Writes each {@link AuditRecord} to an S3-family object store as one immutable object under an
 * Object Lock retention. The name is role-first: this is the object-store adapter, and MinIO is
 * merely a configured endpoint of it — the same class serves AWS S3, R2, or GCS-interop.
 *
 * <p>WORM: with {@code object-lock-mode=COMPLIANCE} the object cannot be deleted or overwritten
 * before its retention expires, not even by the account root — the requirement behind SEC 17a-4(f)
 * / MiFID II "the firm cannot alter the record." MinIO supports S3 Object Lock, so the demo writes
 * real WORM, not a mock.
 *
 * <p>Present only when {@code conduit.audit.enabled=true}. Called from the audit drain thread.
 */
@Component
@ConditionalOnProperty(name = "conduit.audit.enabled", havingValue = "true")
public class ObjectStoreAuditSink implements AuditRecordSink {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreAuditSink.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final String bucket;
    private final String prefix;
    private final String endpoint;
    private final String region;
    private final boolean pathStyle;
    private final String accessKey;
    private final String secretKey;
    private final int retentionDays;
    private final String objectLockMode;
    // F3 VT-pinning discipline: an S3 PutObject on the audit drain thread must not park forever if
    // the object store accepts the connection then stalls. apiCallTimeout bounds the whole call
    // (incl. retries); apiCallAttemptTimeout bounds a single attempt. Config, never a constant.
    private final long apiCallTimeoutMs;
    private final long apiCallAttemptTimeoutMs;

    private S3Client s3;

    public ObjectStoreAuditSink(
            @Value("${conduit.audit.store.bucket:conduit-audit}") String bucket,
            @Value("${conduit.audit.store.prefix:audit}") String prefix,
            @Value("${conduit.audit.store.endpoint:}") String endpoint,
            @Value("${conduit.audit.store.region:us-east-1}") String region,
            @Value("${conduit.audit.store.path-style:true}") boolean pathStyle,
            @Value("${conduit.audit.store.access-key:}") String accessKey,
            @Value("${conduit.audit.store.secret-key:}") String secretKey,
            @Value("${conduit.audit.retention-days:2555}") int retentionDays,
            @Value("${conduit.audit.object-lock-mode:COMPLIANCE}") String objectLockMode,
            @Value("${conduit.audit.s3.api-call-timeout-ms:10000}") long apiCallTimeoutMs,
            @Value("${conduit.audit.s3.api-call-attempt-timeout-ms:5000}") long apiCallAttemptTimeoutMs) {
        this.bucket = bucket;
        this.prefix = prefix;
        this.endpoint = endpoint;
        this.region = region;
        this.pathStyle = pathStyle;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.retentionDays = retentionDays;
        this.objectLockMode = objectLockMode;
        this.apiCallTimeoutMs = apiCallTimeoutMs;
        this.apiCallAttemptTimeoutMs = apiCallAttemptTimeoutMs;
    }

    @PostConstruct
    void init() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMs))
                        .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMs))
                        .build())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));   // MinIO / R2 / GCS-interop
        }
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        this.s3 = builder.build();
        log.info("Audit sink → object store {} bucket='{}' prefix='{}' lock={} retentionDays={}",
                endpoint.isBlank() ? region : endpoint, bucket, prefix, objectLockMode, retentionDays);
    }

    @Override
    public void write(AuditRecord record) throws Exception {
        // A6: an un-partitioned record is REJECTED here — it must never enter a shared partition. The
        // async writer meters the throw (conduit.audit.write.failed) as an incident; the record is
        // dead-lettered, not silently placed under an "unknown"/"default" bucket.
        String key = objectKey(record);   // throws UnpartitionedAuditRecordException if partition unresolved
        byte[] body = mapper.writeValueAsBytes(record);

        PutObjectRequest.Builder put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/json");

        // Object Lock — the immutability. "none" writes without a retention (dev convenience).
        if (!"none".equalsIgnoreCase(objectLockMode)) {
            put.objectLockMode(ObjectLockMode.fromValue(objectLockMode.toUpperCase()))
               .objectLockRetainUntilDate(Instant.now().plus(retentionDays, ChronoUnit.DAYS))
               .objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.OFF);
        }

        s3.putObject(put.build(), RequestBody.fromBytes(body));
    }

    /**
     * {@code {prefix}/dt=YYYY-MM-DD/tenant={partition}/{transactionId}[-{view}].json} —
     * Hive/Iceberg-friendly so the later analytical layer needs no reshuffle. Deterministic from the
     * record, so a re-write of the same request is idempotent (and, under Object Lock, refused — which
     * is correct).
     *
     * <p>A6: the {@code tenant=} segment is the record's OWN resolved {@code partitionTenantId} — never
     * the query-time principal guess, and never an {@code unknown}/{@code default} fallback. A record
     * whose partition is unresolved is <em>rejected</em> ({@link UnpartitionedAuditRecordException}) so
     * it can never land in a shared partition. For a delegated cross-tenant op the {@code -actor} /
     * {@code -subject} view suffix keeps the two partition views distinct.
     */
    String objectKey(AuditRecord record) {
        if (!record.hasResolvedPartition()) {
            throw new UnpartitionedAuditRecordException(record.transactionId());
        }
        String dt = OffsetDateTime.ofInstant(Instant.parse(record.occurredAt()), ZoneOffset.UTC)
                .toLocalDate().toString();
        String tenant = record.partitionTenantId();
        String suffix = record.view() != null && !record.view().isBlank() ? "-" + record.view() : "";
        return "%s/dt=%s/tenant=%s/%s%s.json".formatted(prefix, dt, tenant, record.transactionId(), suffix);
    }
}
