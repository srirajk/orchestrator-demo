package ai.conduit.gateway.registry.tenancy;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.index.VectorIndexWriter;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.List;

import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.MODEL;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.manifest;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.manifestEmbedder;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.queryEmbedder;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.tenant;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo-preservation guard for the A4 WRITE side — the mirror of A3's
 * {@code DefaultTenantUsesLegacyIndexNameTest} (which pinned the READ side).
 *
 * <p>The single-tenant demo's registry-service ingests into, and the gateway reads, the LEGACY names
 * ({@code intent_idx}, {@code vec:...}, stamp {@code intent_idx:model}) — no {@code __tenant} suffix,
 * no {@code t:{tenant}:} prefix. A4 makes the writer tenant-aware; this pins that with multi-tenancy
 * ON, a DEFAULT-tenant ingest still writes exactly those legacy artifacts (so ingestion→routing keeps
 * working) and produces NO tenant-qualified index or keys. If the writer wrote {@code intent_idx__default}
 * while the gateway reads {@code intent_idx}, the demo would die with an empty result set.
 */
class DefaultTenantWritesLegacyIndexTest extends RedisContainerTest {

    private JedisPooled jedis;
    private TenantKeyspace keyspace;
    private VectorIndex vectorIndex;
    private VectorIndexWriter writer;

    @BeforeEach
    void setUp() {
        jedis = new JedisPooled(redisHost(), redisPort());
        jedis.flushAll();
        // Multi-tenancy ON — the default tenant must STILL resolve to legacy names.
        keyspace = new TenantKeyspace(true, "default");

        QueryEmbedder query = queryEmbedder();
        vectorIndex = new VectorIndex(jedis, query, new SimpleMeterRegistry(), keyspace);
        writer = new VectorIndexWriter(jedis, manifestEmbedder(), query, keyspace);
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) jedis.close();
    }

    @Test
    void defaultIngestWritesLegacyNamesAndRoutesGreen() {
        // Ingest via the no-arg (default) writer seam AND the explicit default-tenant seam — both legacy.
        writer.ensureIndex();
        writer.index(manifest("legacy.default.one", "domain.d"));
        writer.ensureIndex(tenant("default"));
        writer.index(manifest("legacy.default.two", "domain.d"), tenant("default"));

        // Legacy index + legacy value keys + legacy stamp exist...
        assertThat(vectorIndex.exists()).isTrue();                 // ftInfo("intent_idx")
        assertThat(jedis.keys("vec:legacy.default.one:*")).isNotEmpty();
        assertThat(jedis.get("intent_idx:model")).isEqualTo(MODEL);
        assertThat(jedis.get("intent_idx:expr_dialect")).isNotNull();

        // ...and NO tenant-qualified artifacts were created for the default tenant.
        assertThat(jedis.keys("t:default:*")).isEmpty();
        assertThat(jedis.keys("t:*:vec:*")).isEmpty();
        assertThat(indexExists("intent_idx__default")).isFalse();

        // A legacy (no-tenant) routing query returns hits — ingestion→routing works.
        List<RoutingCandidate> hits = vectorIndex.search("anything", null, 10,
                id -> manifest(id, "domain.d"));
        assertThat(hits).extracting(c -> c.manifest().agentId())
                .contains("legacy.default.one", "legacy.default.two");
    }

    private boolean indexExists(String name) {
        try {
            jedis.ftInfo(name);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
