package ai.conduit.gateway.infrastructure.payload;

import ai.conduit.gateway.infrastructure.objectstore.ObjectStorePayloadStore;

import ai.conduit.gateway.adapter.PayloadHandle;
import ai.conduit.gateway.testsupport.MinioContainerTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F4 harness tests 5 (Tier-A, tamper) + 6 (store-down fallback) + the spill roundtrip mechanism behind
 * test 2, against a real (throwaway) MinIO.
 */
class PayloadStoreIT extends MinioContainerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectStorePayloadStore store(String bucket) {
        ObjectStorePayloadStore s = new ObjectStorePayloadStore(
                mapper, bucket, minioEndpoint(), "us-east-1", true, ACCESS_KEY, SECRET_KEY,
                8000, 4000, 8, 2000, 2000, 4000, 8_388_608);
        s.init();
        return s;
    }

    private S3Client rawClient() {
        return S3Client.builder()
                .region(Region.of("us-east-1"))
                .endpointOverride(URI.create(minioEndpoint()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
    }

    private ObjectNode stampedTree() {
        ObjectNode n = mapper.createObjectNode();
        n.put("_verified_sub", "user-7");
        n.put("figure", 314);
        return n;
    }

    // ── test 2 mechanism: spill roundtrip ────────────────────────────────────────────────────────

    @Test
    void spillStoresUnderShaKeyAndDownloadReDigestsToKey() throws Exception {
        ObjectStorePayloadStore store = store("payload-roundtrip-" + UUID.randomUUID());
        ObjectNode tree = stampedTree();
        byte[] canonical = CanonicalSha.canonicalBytes(tree);
        String sha = CanonicalSha.sha256Hex(canonical);

        PayloadHandle.Ref ref = store.put(canonical, sha, "application/json",
                PayloadHandle.Provenance.ADAPTER, tree);

        // the object key IS the sha; the Ref keeps the in-memory tree (v1 no-re-fetch).
        assertThat(ref.sha256()).isEqualTo(sha);
        assertThat(ref.uri().toString()).endsWith("/" + sha);
        assertThat(ref.tree()).isSameAs(tree);

        // materialize back, verify integrity, and confirm the stamped identity survived.
        JsonNode materialized = store.materialize(ref, null, MaterializeContext.withDeadline(0));
        assertThat(CanonicalSha.hashHex(materialized))
                .as("downloaded object re-digests to the key")
                .isEqualTo(sha);
        assertThat(materialized.path("_verified_sub").asText()).isEqualTo("user-7");
        assertThat(materialized.path("figure").asInt()).isEqualTo(314);
    }

    // ── test 5 (Tier-A): tamper detected; siblings survive ───────────────────────────────────────

    @Test
    void tamperedObjectFailsIntegrityWhileUntamperedSiblingSucceeds() throws Exception {
        String bucket = "payload-tamper-" + UUID.randomUUID();
        ObjectStorePayloadStore store = store(bucket);

        ObjectNode good = stampedTree();
        byte[] goodBytes = CanonicalSha.canonicalBytes(good);
        String goodSha = CanonicalSha.sha256Hex(goodBytes);
        PayloadHandle.Ref goodRef = store.put(goodBytes, goodSha, "application/json",
                PayloadHandle.Provenance.ADAPTER, good);

        ObjectNode sibling = mapper.createObjectNode().put("_verified_sub", "user-7").put("figure", 999);
        byte[] sibBytes = CanonicalSha.canonicalBytes(sibling);
        String sibSha = CanonicalSha.sha256Hex(sibBytes);
        PayloadHandle.Ref sibRef = store.put(sibBytes, sibSha, "application/json",
                PayloadHandle.Provenance.ADAPTER, sibling);

        // Tamper: overwrite the good object's bytes IN PLACE (key unchanged) with different content.
        try (S3Client raw = rawClient()) {
            raw.putObject(PutObjectRequest.builder().bucket(bucket).key(goodSha).build(),
                    RequestBody.fromString("{\"figure\":0,\"tampered\":true}", StandardCharsets.UTF_8));
        }

        // The tampered node FAILS the integrity check.
        assertThatThrownBy(() -> store.materialize(goodRef, null, MaterializeContext.withDeadline(0)))
                .isInstanceOf(PayloadIntegrityException.class)
                .hasMessageContaining("integrity");

        // Its sibling still materializes cleanly — one bad node does not take out the others.
        JsonNode sib = store.materialize(sibRef, null, MaterializeContext.withDeadline(0));
        assertThat(sib.path("figure").asInt()).isEqualTo(999);
    }

    // ── test 6: store down → spill falls back to Inline; materialize fails fast (no hang) ─────────

    @Test
    void storeDownMaterializeFailsFastNotHang() {
        // Point at a dead endpoint (nothing listening). Bounded timeouts must cut it quickly.
        ObjectStorePayloadStore dead = new ObjectStorePayloadStore(
                mapper, "no-bucket", "http://127.0.0.1:1", "us-east-1", true, ACCESS_KEY, SECRET_KEY,
                1500, 800, 4, 500, 400, 800, 8_388_608);
        dead.init();   // preflight logs a warning; must NOT fail startup.

        PayloadHandle.Ref ref = new PayloadHandle.Ref(URI.create("s3://no-bucket/x"),
                "x".repeat(64), 10, "application/json");

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> dead.materialize(ref, null, MaterializeContext.withDeadline(0)))
                .isInstanceOf(Exception.class);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(elapsedMs)
                .as("a down store fails within the bounded api-call timeout, it does not hang")
                .isLessThan(8000);
    }
}
