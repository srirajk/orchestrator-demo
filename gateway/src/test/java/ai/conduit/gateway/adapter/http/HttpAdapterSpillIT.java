package ai.conduit.gateway.adapter.http;

import ai.conduit.gateway.adapter.PayloadHandle;
import ai.conduit.gateway.infrastructure.payload.CanonicalSha;
import ai.conduit.gateway.infrastructure.payload.MaterializeContext;
import ai.conduit.gateway.infrastructure.objectstore.ObjectStorePayloadStore;
import ai.conduit.gateway.infrastructure.payload.PayloadStore;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Connection;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.testsupport.MinioContainerTest;
import ai.conduit.gateway.testsupport.PayloadTestSupport;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 harness test 2 — an HTTP agent body OVER the spill threshold is claim-checked to MinIO: the object
 * lands under key = sha, the downloaded bytes re-digest to that key, the parsed object contains
 * {@code _verified_sub}, and {@code data()} keeps the already-parsed stamped tree (same reference the Ref
 * carries — no re-fetch). UNDER threshold → {@code Inline} equal to today's parse.
 */
class HttpAdapterSpillIT extends MinioContainerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectStorePayloadStore store() {
        ObjectStorePayloadStore s = new ObjectStorePayloadStore(
                mapper, "http-spill-" + UUID.randomUUID(), minioEndpoint(), "us-east-1", true,
                ACCESS_KEY, SECRET_KEY, 8000, 4000, 8, 2000, 2000, 4000, 8_388_608);
        s.init();
        return s;
    }

    private HttpAdapter adapter(PayloadStore store, long thresholdBytes) {
        return new HttpAdapter(RestClient.builder().build(), mapper,
                PayloadTestSupport.spiller(store, thresholdBytes));
    }

    /** A stub HTTP agent: /openapi.json advertises getData(GET /data); /data returns a payload + verified sub. */
    private StubHttpServer agent(String payloadJson) {
        StubHttpServer stub = new StubHttpServer();
        stub.handle("/openapi.json", ex -> respond(ex, 200,
                "{\"openapi\":\"3.0.0\",\"paths\":{\"/data\":{\"get\":{\"operationId\":\"getData\"}}}}", null));
        stub.handle("/data", ex -> respond(ex, 200, payloadJson, "user-9"));
        return stub;
    }

    private AgentManifest httpAgent(String baseUrl) {
        return new AgentManifest("h1", "h1", null, null, null, null, null, null, null, "http",
                new Connection(baseUrl + "/openapi.json", "getData", null, null),
                null, null, new Constraints("read", "internal", 5000), null, null, null, null, true, null);
    }

    @Test
    void bodyOverThresholdSpillsToMinioAndDataKeepsTheParse() throws Exception {
        // A payload comfortably over a 32-byte threshold.
        String big = "{\"rows\":[\"aaaaaaaaaa\",\"bbbbbbbbbb\",\"cccccccccc\",\"dddddddddd\"]}";
        try (StubHttpServer stub = agent(big)) {
            ObjectStorePayloadStore store = store();
            HttpAdapter adapter = adapter(store, 32);

            PayloadHandle handle = adapter.invokeHandle(httpAgent(stub.baseUrl()), input(), "tok");

            assertThat(handle).isInstanceOf(PayloadHandle.Ref.class);
            PayloadHandle.Ref ref = (PayloadHandle.Ref) handle;

            // data() keeps the stamped tree, and it is the SAME reference the Ref carries (no re-fetch).
            assertThat(handle.tree().path("_verified_sub").asText()).isEqualTo("user-9");
            assertThat(ref.tree()).isSameAs(handle.tree());

            // the object exists under key = sha, and the download re-digests to that key.
            JsonNode materialized = store.materialize(ref, null, MaterializeContext.withDeadline(0));
            assertThat(CanonicalSha.hashHex(materialized)).isEqualTo(ref.sha256());
            assertThat(materialized.path("_verified_sub").asText())
                    .as("the spilled object parses back to a tree containing _verified_sub")
                    .isEqualTo("user-9");
        }
    }

    @Test
    void bodyUnderThresholdStaysInline() throws Exception {
        String small = "{\"x\":1}";
        try (StubHttpServer stub = agent(small)) {
            HttpAdapter adapter = adapter(store(), 1_000_000);   // threshold far above the body
            PayloadHandle handle = adapter.invokeHandle(httpAgent(stub.baseUrl()), input(), "tok");

            assertThat(handle).isInstanceOf(PayloadHandle.Inline.class);
            assertThat(handle.tree().path("x").asInt()).isEqualTo(1);
            assertThat(handle.tree().path("_verified_sub").asText()).isEqualTo("user-9");
        }
    }

    private JsonNode input() {
        return mapper.createObjectNode();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body,
                                String verifiedSub) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (verifiedSub != null) ex.getResponseHeaders().add("X-Conduit-Verified-Sub", verifiedSub);
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }
}
