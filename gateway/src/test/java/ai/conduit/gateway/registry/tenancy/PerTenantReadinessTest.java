package ai.conduit.gateway.registry.tenancy;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.index.VectorIndexWriter;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.registry.readiness.RegistryReadinessVerifier;
import ai.conduit.gateway.registry.readiness.TenantRegistryNotReadyException;
import ai.conduit.gateway.registry.service.AgentRegistry;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.List;

import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.indexFingerprint;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.manifest;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.manifestEmbedder;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.queryEmbedder;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.tenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A4.1 — one tenant's broken/empty ingest fails ONLY that tenant closed; healthy siblings serve, and
 * the healthy tenant's index is byte-unchanged by the broken sibling's ingest.
 *
 * <p>Tenant A's ingest is broken (its index is created but no agent is indexed — an empty registry).
 * Tenant B is healthy. The per-tenant {@link RegistryReadinessVerifier} marks A not-ready and B ready;
 * a routing op in A is denied with {@link TenantRegistryNotReadyException} while B routes normally. The
 * red-bar version of this test would be a single shared gate that blocks every tenant on one bad ingest.
 */
class PerTenantReadinessTest extends RedisContainerTest {

    private static final TenantExecutionContext TENANT_A = tenant("tenant-a");
    private static final TenantExecutionContext TENANT_B = tenant("tenant-b");

    private JedisPooled jedis;
    private TenantKeyspace keyspace;
    private VectorIndex vectorIndex;
    private VectorIndexWriter writer;
    private RegistryReadinessVerifier verifier;

    @BeforeEach
    void setUp() {
        jedis = new JedisPooled(redisHost(), redisPort());
        jedis.flushAll();
        keyspace = new TenantKeyspace(true, "default");

        QueryEmbedder query = queryEmbedder();
        vectorIndex = new VectorIndex(jedis, query, new SimpleMeterRegistry(), keyspace);
        writer = new VectorIndexWriter(jedis, manifestEmbedder(), query, keyspace);

        // Healthy tenant B: index created and one agent ingested.
        writer.ensureIndex(TENANT_B);
        writer.index(manifest("beta.bravo.one", "domain.b"), TENANT_B);

        // The default/legacy tenant must pass the process gate so the verifier can construct — give it a
        // legacy index the AgentRegistry count agrees with.
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.count()).thenReturn(1L);
        writer.ensureIndex();                                   // legacy intent_idx + stamp
        writer.index(manifest("legacy.default.one", "domain.d")); // one legacy doc

        verifier = new RegistryReadinessVerifier(vectorIndex, registry, query, keyspace, null, true);
        verifier.verify(); // default passes; no non-default discovered yet
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) jedis.close();
    }

    @Test
    void brokenTenantFailsClosedOthersServe() {
        // Snapshot B's healthy index BEFORE tenant A's broken ingest runs.
        String bBefore = indexFingerprint(jedis, keyspace, TENANT_B);

        // Tenant A's ingest is broken: the index is created but NOTHING is indexed (empty registry).
        writer.ensureIndex(TENANT_A);
        // (no writer.index(...) call — this is the empty/broken ingest)

        // Recompute readiness over both tenants.
        verifier.refreshReadiness(List.of(TENANT_A, TENANT_B));

        // A is not ready; B is ready — a per-tenant verdict, not a shared all-or-nothing gate.
        assertThat(verifier.isReady(TENANT_A)).isFalse();
        assertThat(verifier.isReady(TENANT_B)).isTrue();
        assertThat(verifier.readiness().get("tenant-a").ready()).isFalse();
        assertThat(verifier.readiness().get("tenant-b").ready()).isTrue();

        // Routing in A is denied with registry-not-ready; B routes normally.
        assertThatThrownBy(() -> verifier.requireReady(TENANT_A))
                .isInstanceOf(TenantRegistryNotReadyException.class)
                .hasMessageContaining("tenant registry not ready");
        assertThatCode(() -> verifier.requireReady(TENANT_B)).doesNotThrowAnyException();

        List<RoutingCandidate> bHits = vectorIndex.search("anything", null, 10, TENANT_B,
                id -> manifest(id, "domain.b"));
        assertThat(bHits).extracting(c -> c.manifest().agentId()).containsExactly("beta.bravo.one");

        // B's index is byte-identical before and after A's broken ingest — no contamination.
        String bAfter = indexFingerprint(jedis, keyspace, TENANT_B);
        assertThat(bAfter).isEqualTo(bBefore);
    }

    @Test
    void defaultTenantIsAlwaysReadyOnceTheProcessGatePassed() {
        // The default/legacy tenant is the process gate; it is ready independent of the per-tenant map.
        assertThat(verifier.isReady(tenant("default"))).isTrue();
        assertThatCode(() -> verifier.requireReady(tenant("default"))).doesNotThrowAnyException();
    }
}
