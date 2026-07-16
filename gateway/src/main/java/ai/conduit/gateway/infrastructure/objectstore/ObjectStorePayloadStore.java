package ai.conduit.gateway.infrastructure.objectstore;

import ai.conduit.gateway.adapter.PayloadHandle;
import ai.conduit.gateway.infrastructure.expression.CelEvalEngine;
import ai.conduit.gateway.infrastructure.expression.CompiledExpr;
import ai.conduit.gateway.infrastructure.expression.EvalEngine;
import ai.conduit.gateway.infrastructure.payload.CanonicalSha;
import ai.conduit.gateway.infrastructure.payload.MaterializeContext;
import ai.conduit.gateway.infrastructure.payload.PayloadIntegrityException;
import ai.conduit.gateway.infrastructure.payload.PayloadStore;
import ai.conduit.gateway.infrastructure.payload.PayloadTooLargeException;
import com.fasterxml.jackson.databind.JsonNode;
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
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.time.Duration;

/**
 * The object-store adapter for the claim-check port ({@link PayloadStore}). Role-first name, exactly as
 * {@code ObjectStoreAuditSink}: this is the S3-family adapter; MinIO is merely a configured endpoint of
 * it (the same class serves AWS S3 / R2 / GCS-interop).
 *
 * <p>Present only when {@code conduit.payload.store.enabled=true}. Every setting is {@code @Value}
 * (World B / §5: no config constant). The sync client uses a BOUNDED Apache connection pool with a
 * bounded acquisition wait plus finite api-call timeouts, so a saturated or stalled store fails a
 * materialise within the deadline rather than hanging a virtual thread's carrier (F3 discipline).
 */
@Component
@ConditionalOnProperty(name = "conduit.payload.store.enabled", havingValue = "true")
public class ObjectStorePayloadStore implements PayloadStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorePayloadStore.class);

    private final ObjectMapper mapper;
    private final EvalEngine evalEngine;

    private final String bucket;
    private final String endpoint;
    private final String region;
    private final boolean pathStyle;
    private final String accessKey;
    private final String secretKey;
    private final long apiCallTimeoutMs;
    private final long apiCallAttemptTimeoutMs;
    private final int maxConnections;
    private final long connectionAcquisitionTimeoutMs;
    private final long connectTimeoutMs;
    private final long socketTimeoutMs;
    private final long maxMaterializeBytes;

    private S3Client s3;

    public ObjectStorePayloadStore(
            ObjectMapper mapper,
            @Value("${conduit.payload.store.bucket:conduit-payload}") String bucket,
            @Value("${conduit.payload.store.endpoint:}") String endpoint,
            @Value("${conduit.payload.store.region:us-east-1}") String region,
            @Value("${conduit.payload.store.path-style:true}") boolean pathStyle,
            @Value("${conduit.payload.store.access-key:}") String accessKey,
            @Value("${conduit.payload.store.secret-key:}") String secretKey,
            @Value("${conduit.payload.store.api-call-timeout-ms:8000}") long apiCallTimeoutMs,
            @Value("${conduit.payload.store.api-call-attempt-timeout-ms:4000}") long apiCallAttemptTimeoutMs,
            @Value("${conduit.payload.store.max-connections:8}") int maxConnections,
            @Value("${conduit.payload.store.connection-acquisition-timeout-ms:2000}") long connectionAcquisitionTimeoutMs,
            @Value("${conduit.payload.store.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${conduit.payload.store.socket-timeout-ms:4000}") long socketTimeoutMs,
            @Value("${conduit.payload.max-materialize-bytes:8388608}") long maxMaterializeBytes) {
        this.mapper = mapper;
        this.evalEngine = new CelEvalEngine(mapper);   // stateless; compiled exprs come in pre-checked
        this.bucket = bucket;
        this.endpoint = endpoint;
        this.region = region;
        this.pathStyle = pathStyle;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.apiCallTimeoutMs = apiCallTimeoutMs;
        this.apiCallAttemptTimeoutMs = apiCallAttemptTimeoutMs;
        this.maxConnections = maxConnections;
        this.connectionAcquisitionTimeoutMs = connectionAcquisitionTimeoutMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.socketTimeoutMs = socketTimeoutMs;
        this.maxMaterializeBytes = maxMaterializeBytes;
    }

    @PostConstruct
    public void init() {
        var http = ApacheHttpClient.builder()
                .maxConnections(maxConnections)
                .connectionAcquisitionTimeout(Duration.ofMillis(connectionAcquisitionTimeoutMs))
                .connectionTimeout(Duration.ofMillis(connectTimeoutMs))
                .socketTimeout(Duration.ofMillis(socketTimeoutMs));
        var builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(http)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMs))
                        .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMs))
                        // Request-path fail-fast: no retries. A pool-acquisition/socket timeout must surface
                        // immediately (bounded by the acquire wait / socket timeout), not be re-attempted —
                        // the node SLA / deadline governs, and a spill blip simply falls back to Inline.
                        .retryPolicy(RetryPolicy.none())
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
        ensureBucket();
        log.info("PayloadStore → object store {} bucket='{}' maxConn={} acquireWaitMs={}",
                endpoint == null || endpoint.isBlank() ? region : endpoint, bucket,
                maxConnections, connectionAcquisitionTimeoutMs);
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("PayloadStore created bucket '{}'", bucket);
            } catch (RuntimeException ce) {
                log.warn("PayloadStore could not create bucket '{}': {}", bucket, ce.getMessage());
            }
        } catch (RuntimeException e) {
            // Endpoint unreachable at boot — do not fail startup; a spill will fall back to Inline and a
            // materialise will fail fast. The store being down must never take the gateway down.
            log.warn("PayloadStore bucket preflight failed for '{}': {}", bucket, e.getMessage());
        }
    }

    @Override
    public PayloadHandle.Ref put(byte[] canonicalBytes, String sha256, String mediaType,
                                 PayloadHandle.Provenance provenance, JsonNode tree) throws Exception {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(sha256)
                .contentType(mediaType == null ? "application/json" : mediaType)
                .build();
        s3.putObject(req, RequestBody.fromBytes(canonicalBytes));
        URI uri = URI.create("s3://" + bucket + "/" + sha256);
        return new PayloadHandle.Ref(uri, sha256, canonicalBytes.length,
                mediaType == null ? "application/json" : mediaType, provenance, tree);
    }

    @Override
    public JsonNode materialize(PayloadHandle.Ref ref, CompiledExpr select, MaterializeContext ctx)
            throws Exception {
        if (ref == null) {
            throw new IllegalArgumentException("materialize requires a non-null Ref");
        }
        // Cap guard BEFORE any I/O: without a projecting select to bound the in-memory result, refuse an
        // over-cap object. With a select, a caller has bounded the result and may proceed.
        if (select == null && ref.sizeBytes() > maxMaterializeBytes) {
            throw new PayloadTooLargeException("payload " + ref.sizeBytes() + " bytes exceeds max-materialize "
                    + maxMaterializeBytes + " and no select was supplied to bound it");
        }

        byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(ref.sha256()).build()).asByteArray();

        // Integrity: the downloaded object MUST re-digest to the sha it was fetched under.
        String actual = CanonicalSha.sha256Hex(bytes);
        if (!actual.equals(ref.sha256())) {
            throw new PayloadIntegrityException("payload integrity check failed for " + ref.uri()
                    + ": expected sha256=" + ref.sha256() + " but downloaded object digests to " + actual);
        }

        JsonNode tree = mapper.readTree(bytes);
        if (select == null) {
            return tree;
        }
        // Deferred lazy-fetch projection: eval the pre-compiled (F2) expression against the tree.
        return evalEngine.eval(select, tree, EvalEngine.Mode.LENIENT);
    }
}
