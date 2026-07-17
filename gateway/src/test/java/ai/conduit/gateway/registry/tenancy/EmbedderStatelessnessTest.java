package ai.conduit.gateway.registry.tenancy;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.registry.embedding.RemoteEmbedder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.tenant;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4.4 — the shared embedding sidecar is stateless, so it is safe to share across tenants.
 *
 * <p>{@link RemoteEmbedder} takes no tenant, holds no per-caller state, and does no tenant-specific
 * preprocessing: the vector is a pure function of {@code (text, model, dimension)}. This test drives
 * the REAL {@link RemoteEmbedder} against a deterministic stub sidecar and proves that embedding the
 * identical text "as tenant A" and "as tenant B" yields byte-identical vectors — which is exactly why
 * the content-addressed corpus cache (keyed on model-id + text digest) is tenant-agnostic by
 * construction: there is no tenant identity in the vector to leak between tenants sharing a cache hit.
 */
class EmbedderStatelessnessTest {

    private static final int DIM = 8;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TenantExecutionContext TENANT_A = tenant("tenant-a");
    private static final TenantExecutionContext TENANT_B = tenant("tenant-b");

    private HttpServer server;
    private RemoteEmbedder embedder;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                JsonNode body = MAPPER.readTree(in.readAllBytes());
                ArrayNode data = MAPPER.createArrayNode();
                for (JsonNode input : body.path("input")) {
                    ObjectNode entry = MAPPER.createObjectNode();
                    ArrayNode vec = MAPPER.createArrayNode();
                    for (float f : deterministicVector(input.asText())) vec.add(f);
                    entry.set("embedding", vec);
                    data.add(entry);
                }
                byte[] out = MAPPER.writeValueAsBytes(MAPPER.createObjectNode().set("data", data));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            } finally {
                exchange.close();
            }
        });
        server.start();

        embedder = new RemoteEmbedder();
        int port = server.getAddress().getPort();
        ReflectionTestUtils.setField(embedder, "endpointUrl", "http://127.0.0.1:" + port + "/v1/embeddings");
        ReflectionTestUtils.setField(embedder, "dimension", DIM);
        ReflectionTestUtils.setField(embedder, "modelName", "stub-model");
        ReflectionTestUtils.setField(embedder, "connectTimeoutMs", 2000L);
        ReflectionTestUtils.setField(embedder, "readTimeoutMs", 5000L);
        embedder.probe(); // builds the RestTemplate and warms the client
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void sameTextSameVectorAcrossTenants() {
        String text = "holdings and settlements for the relationship";

        // The embedder is tenant-agnostic: a "tenant-scoped" call is just embed(text) — there is no
        // tenant argument, which is the whole point. Two tenants asking the same question get the same
        // vector.
        float[] asTenantA = embedAsTenant(TENANT_A, text);
        float[] asTenantB = embedAsTenant(TENANT_B, text);

        assertThat(asTenantA).containsExactly(asTenantB);
        assertThat(asTenantA).hasSize(DIM);

        // Purity sanity: a DIFFERENT text yields a different vector, so equality above is content-driven,
        // not a constant.
        float[] other = embedAsTenant(TENANT_A, "a completely unrelated question");
        assertThat(Arrays.equals(asTenantA, other)).isFalse();

        // And repeated calls are stable (deterministic), the property the corpus cache relies on.
        assertThat(embedAsTenant(TENANT_B, text)).containsExactly(asTenantA);
    }

    /** There is no tenant seam on the embedder — this documents that by ignoring the context. */
    private float[] embedAsTenant(TenantExecutionContext ignoredTenantContext, String text) {
        return embedder.embed(text);
    }

    private static float[] deterministicVector(String text) {
        float[] v = new float[DIM];
        int h = text.hashCode();
        for (int i = 0; i < DIM; i++) {
            v[i] = ((h >>> i) & 1) == 1 ? 1.0f : 0.25f;
        }
        return v;
    }
}
