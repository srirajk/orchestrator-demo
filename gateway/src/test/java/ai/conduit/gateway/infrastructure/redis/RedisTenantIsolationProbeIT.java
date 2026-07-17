package ai.conduit.gateway.infrastructure.redis;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tenant-isolation probes over a real Redis Stack (Axiom A3). Extends {@link RedisContainerTest} so
 * it runs against a throwaway container, never the demo's Redis.
 *
 * <p>Multi-tenancy is turned ON here ({@code new TenantKeyspace(true, "default")}) so real tenants
 * {@code tenant-a} / {@code tenant-b} exercise the {@code intent_idx__{tenant}} / {@code t:{tenant}:}
 * scheme. The demo-preservation (legacy-for-default) behaviour is proven separately in
 * {@link DefaultTenantUsesLegacyIndexNameTest}.
 */
class RedisTenantIsolationProbeIT extends RedisContainerTest {

    private static final int DIM = 384;
    private static final String LEGACY_INDEX = "intent_idx";
    private static final String LEGACY_VEC_PREFIX = "vec:";

    private static final TenantExecutionContext TENANT_A = TenantExecutionContext.of("tenant-a", "tenant-a", "v1");
    private static final TenantExecutionContext TENANT_B = TenantExecutionContext.of("tenant-b", "tenant-b", "v1");

    private JedisPooled jedis;
    private TenantKeyspace keyspace;
    private VectorIndex vectorIndex;
    private final StringBuilder ftTranscript = new StringBuilder();

    @BeforeEach
    void setUp() {
        jedis = new JedisPooled(redisHost(), redisPort());
        jedis.flushAll();
        keyspace = new TenantKeyspace(true, "default");

        // A query embedder that returns e0 (unit vector at index 0) for any text, so a KNN query
        // matches tenant-A's agent.a document (also e0) perfectly — if the query is issued against
        // tenant-A's index.
        QueryEmbedder embedder = mock(QueryEmbedder.class);
        when(embedder.embed(anyString())).thenReturn(unit(0));
        when(embedder.modelId()).thenReturn("test-embedder:384");
        when(embedder.dimension()).thenReturn(DIM);

        vectorIndex = new VectorIndex(jedis, embedder, new SimpleMeterRegistry(), keyspace);

        // Two per-tenant routing indexes. Tenant A has agent.a (vector e0). Tenant B has agent.b
        // (vector e1) so B's index is non-empty — proving a query under B reaches B's own index,
        // not A's.
        seedTenantIndex(TENANT_A, "agent.a", unit(0), "domain.a");
        seedTenantIndex(TENANT_B, "agent.b", unit(1), "domain.b");
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) jedis.close();
    }

    // ── A3.1 — GET isolation ────────────────────────────────────────────────────────────────────
    @Test
    void getByKeyIsIsolated() {
        TenantRedisFacade facadeA = TenantRedisFacade.forTenant(jedis, keyspace, TENANT_A);
        TenantRedisFacade facadeB = TenantRedisFacade.forTenant(jedis, keyspace, TENANT_B);

        facadeA.set("probe:secret", "A-only-value");

        assertThat(facadeA.get("probe:secret")).isEqualTo("A-only-value");
        // Same logical key, different tenant context ⇒ miss. The physical keys are
        // t:tenant-a:probe:secret vs t:tenant-b:probe:secret.
        assertThat(facadeB.get("probe:secret")).isNull();

        assertThat(jedis.exists("t:tenant-a:probe:secret")).isTrue();
        assertThat(jedis.exists("t:tenant-b:probe:secret")).isFalse();
    }

    // ── A3.2 — RediSearch query isolation (the hard one) ────────────────────────────────────────
    @Test
    void rediSearchQueryIsIsolated() throws IOException {
        Function<String, AgentManifest> loader = this::minimalManifest;

        // Query in tenant-A's context resolves intent_idx__tenant-a and finds A's own agent.a.
        List<RoutingCandidate> aHits = vectorIndex.search("anything", null, 10, TENANT_A, loader);
        assertThat(aHits).extracting(c -> c.manifest().agentId()).contains("agent.a");

        // The SAME query in tenant-B's context resolves intent_idx__tenant-b. Because the index is
        // per-tenant, A's agent.a is physically absent from B's index — zero A hits, even though the
        // query vector (e0) is a perfect match for A's document. A shared-index prefix filter that
        // forgot the tenant clause would leak agent.a here; a per-tenant index cannot.
        List<RoutingCandidate> bHits = vectorIndex.search("anything", null, 10, TENANT_B, loader);
        assertThat(bHits).extracting(c -> c.manifest().agentId()).doesNotContain("agent.a");

        // Raw FT.SEARCH transcript proving 0 cross-hits, captured for the evidence bundle.
        int aInAIndex = rawKnnCount(keyspace.indexName(LEGACY_INDEX, TENANT_A), "agent.a");
        int aInBIndex = rawKnnCount(keyspace.indexName(LEGACY_INDEX, TENANT_B), "agent.a");
        assertThat(aInAIndex).isEqualTo(1);   // A's doc lives in A's index
        assertThat(aInBIndex).isEqualTo(0);   // and is unreachable from B's index

        writeTranscript();
    }

    // ── A3.3 — facade cannot enumerate cross-tenant (+ A3.4 bounded-context probe) ───────────────
    @Test
    void applicationFacadeCannotEnumerateOtherTenant() {
        TenantRedisFacade facadeA = TenantRedisFacade.forTenant(jedis, keyspace, TENANT_A);
        TenantRedisFacade facadeB = TenantRedisFacade.forTenant(jedis, keyspace, TENANT_B);

        facadeA.set("probe:1", "a1");
        facadeA.set("probe:2", "a2");
        facadeB.set("probe:1", "b1");

        // A bounded-context (IAM) key, tenant-qualified in the IAM context. It lives in the gateway
        // context's Redis here only for the probe; the gateway facade must never surface it.
        jedis.set("iam:t:tenant-a:oauth2:authorization:xyz", "iam-secret");

        // The facade's only listing op is bounded to its own tenant prefix — no unqualified SCAN.
        List<String> bKeys = facadeB.listOwnKeys("probe:*");
        assertThat(bKeys).containsExactly("t:tenant-b:probe:1");
        assertThat(bKeys).allMatch(k -> k.startsWith("t:tenant-b:"));

        List<String> aKeys = facadeA.listOwnKeys("probe:*");
        assertThat(aKeys).containsExactlyInAnyOrder("t:tenant-a:probe:1", "t:tenant-a:probe:2");

        // A3.4 — the IAM key is unreachable from the gateway namespace facade, whatever pattern the
        // gateway asks for: the facade always prepends the tenant prefix, so an "iam:*" query becomes
        // "t:tenant-a:iam:*" and matches nothing.
        assertThat(facadeA.listOwnKeys("iam:*")).isEmpty();
        assertThat(facadeB.listOwnKeys("iam:*")).isEmpty();
    }

    /**
     * A3.3 boundary honesty — a companion probe documenting the limit. Namespacing scopes the
     * gateway's own commands; it is NOT protection from a raw Redis operator. A privileged
     * {@code KEYS *} sees every tenant's keys. The application answer is the facade (proven above);
     * the infrastructure answer (Redis ACLs / a separate instance) is out of A3 scope.
     */
    @Test
    void rawAdminSeesEverything_isOutOfTheApplicationBoundary() {
        TenantRedisFacade.forTenant(jedis, keyspace, TENANT_A).set("probe:x", "a");
        TenantRedisFacade.forTenant(jedis, keyspace, TENANT_B).set("probe:x", "b");

        // A raw operator, bypassing the facade, can enumerate across tenants. Documented, not
        // defended, at the application layer.
        assertThat(jedis.keys("t:*:probe:x"))
                .contains("t:tenant-a:probe:x", "t:tenant-b:probe:x");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private void seedTenantIndex(TenantExecutionContext tenant, String agentId, float[] vec, String domain) {
        String indexName = keyspace.indexName(LEGACY_INDEX, tenant);
        String keyPrefix = keyspace.key(LEGACY_VEC_PREFIX, tenant);   // t:{tenant}:vec:

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("TYPE", "FLOAT32");
        attrs.put("DIM", DIM);
        attrs.put("DISTANCE_METRIC", "COSINE");

        jedis.ftCreate(indexName,
                FTCreateParams.createParams().on(IndexDataType.HASH).prefix(keyPrefix),
                TagField.of("agent_id"),
                TagField.of("domain"),
                NumericField.of("is_mutating"),
                VectorField.builder()
                        .fieldName("embedding")
                        .algorithm(VectorField.VectorAlgorithm.HNSW)
                        .attributes(attrs)
                        .build());

        String key = keyPrefix + agentId + ":0";
        Map<String, String> fields = new HashMap<>();
        fields.put("agent_id", agentId);
        fields.put("domain", domain);
        fields.put("is_mutating", "0");
        jedis.hset(key, fields);
        jedis.hset(key.getBytes(), "embedding".getBytes(), floatsToBytes(vec));
    }

    /** Raw KNN against a named index, counting docs whose agent_id equals {@code agentId}. */
    private int rawKnnCount(String indexName, String agentId) {
        String q = "(@is_mutating:[0 0])=>[KNN 10 @embedding $BLOB AS score]";
        Query query = new Query(q)
                .addParam("BLOB", floatsToBytes(unit(0)))
                .returnFields("agent_id", "score")
                .setSortBy("score", true)
                .dialect(2)
                .limit(0, 10);
        SearchResult result = jedis.ftSearch(indexName, query);
        long total = result.getTotalResults();
        int match = 0;
        for (var doc : result.getDocuments()) {
            if (agentId.equals(doc.get("agent_id"))) match++;
        }
        ftTranscript.append("FT.SEARCH ").append(indexName)
                .append(" \"(@is_mutating:[0 0])=>[KNN 10 @embedding $BLOB AS score]\" ")
                .append("PARAMS 2 BLOB <e0> DIALECT 2\n")
                .append("  -> total_results=").append(total)
                .append(", docs_with_agent_id=").append(agentId).append(" : ").append(match).append('\n');
        return match;
    }

    private void writeTranscript() throws IOException {
        Path dir = Path.of("..", "docs", "implementation", "evidence", "tenancy", "a3");
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("ft-search-transcript.txt"),
                    "Axiom A3 — RediSearch per-tenant index isolation (FT.SEARCH transcript)\n"
                    + "Query vector e0 is a perfect match for tenant-a's agent.a document.\n\n"
                    + ftTranscript
                    + "\nConclusion: agent.a is reachable only from intent_idx__tenant-a; querying\n"
                    + "intent_idx__tenant-b for the identical vector yields 0 cross-tenant hits.\n");
        } catch (IOException ignored) {
            // Evidence writing is best-effort; the assertions above are the actual gate.
        }
    }

    private AgentManifest minimalManifest(String agentId) {
        return new AgentManifest(
                agentId, agentId, null, null, null,
                agentId.equals("agent.a") ? "domain.a" : "domain.b",
                null, null, null, "http",
                null, null, null, new Constraints("read", "internal", 5_000),
                null, null, null, null, true, null);
    }

    private static float[] unit(int idx) {
        float[] v = new float[DIM];
        v[idx] = 1.0f;
        return v;
    }

    private static byte[] floatsToBytes(float[] floats) {
        ByteBuffer bb = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) bb.putFloat(f);
        return bb.array();
    }
}
