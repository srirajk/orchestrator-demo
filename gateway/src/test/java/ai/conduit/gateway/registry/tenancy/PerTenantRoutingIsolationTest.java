package ai.conduit.gateway.registry.tenancy;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.index.VectorIndexWriter;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.function.Function;

import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.LEGACY_INDEX;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.manifest;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.manifestEmbedder;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.queryEmbedder;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.tenant;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4.2 — routing cross-tenant isolation, driven end to end through the A4 WRITE side.
 *
 * <p>Two tenants are ingested through the real {@link VectorIndexWriter} into their own per-tenant
 * {@code intent_idx__{tenant}} indexes. A routing query issued in one tenant's context (A3.2 read
 * seam) resolves that tenant's index and therefore <b>cannot</b> return the other tenant's agents —
 * not because a filter clause excludes them, but because they physically live in a different index.
 * A shared-index prefix filter that forgot the tenant clause would leak here; a per-tenant index
 * cannot.
 */
class PerTenantRoutingIsolationTest extends RedisContainerTest {

    private static final TenantExecutionContext TENANT_A = tenant("tenant-a");
    private static final TenantExecutionContext TENANT_B = tenant("tenant-b");

    private JedisPooled jedis;
    private TenantKeyspace keyspace;
    private VectorIndex vectorIndex;

    @BeforeEach
    void setUp() {
        jedis = new JedisPooled(redisHost(), redisPort());
        jedis.flushAll();
        keyspace = new TenantKeyspace(true, "default");

        QueryEmbedder query = queryEmbedder();
        vectorIndex = new VectorIndex(jedis, query, new SimpleMeterRegistry(), keyspace);

        VectorIndexWriter writer = new VectorIndexWriter(jedis, manifestEmbedder(), query, keyspace);
        // Each tenant is ingested into ITS OWN index via the real A4 write path.
        writer.ensureIndex(TENANT_A);
        writer.index(manifest("acme.alpha.one", "domain.a"), TENANT_A);
        writer.ensureIndex(TENANT_B);
        writer.index(manifest("beta.bravo.one", "domain.b"), TENANT_B);
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) jedis.close();
    }

    @Test
    void queryNeverReturnsOtherTenantHits() {
        Function<String, AgentManifest> loader = id ->
                id.startsWith("acme.") ? manifest(id, "domain.a") : manifest(id, "domain.b");

        List<RoutingCandidate> aHits = vectorIndex.search("anything", null, 10, TENANT_A, loader);
        List<RoutingCandidate> bHits = vectorIndex.search("anything", null, 10, TENANT_B, loader);

        // Each tenant sees only its own agent.
        assertThat(aHits).extracting(c -> c.manifest().agentId()).containsExactly("acme.alpha.one");
        assertThat(bHits).extracting(c -> c.manifest().agentId()).containsExactly("beta.bravo.one");

        // The hard guarantee: a query in B never surfaces A's agent, and vice-versa.
        assertThat(bHits).extracting(c -> c.manifest().agentId()).doesNotContain("acme.alpha.one");
        assertThat(aHits).extracting(c -> c.manifest().agentId()).doesNotContain("beta.bravo.one");
    }

    @Test
    void eachTenantHasItsOwnPhysicalIndexAndKeyspace() {
        assertThat(vectorIndex.exists(TENANT_A)).isTrue();
        assertThat(vectorIndex.exists(TENANT_B)).isTrue();
        assertThat(keyspace.indexName(LEGACY_INDEX, TENANT_A)).isEqualTo("intent_idx__tenant-a");
        assertThat(keyspace.indexName(LEGACY_INDEX, TENANT_B)).isEqualTo("intent_idx__tenant-b");
        // A's documents live only under A's tenant key prefix.
        assertThat(jedis.keys("t:tenant-a:vec:*")).isNotEmpty();
        assertThat(jedis.keys("t:tenant-b:vec:acme.alpha.one:*")).isEmpty();
    }
}
