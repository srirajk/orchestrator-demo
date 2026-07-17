package ai.conduit.gateway.registry.tenancy;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.index.VectorIndexWriter;
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
 * A4.3 — the model stamp is per tenant, so a stamp mismatch fails ONLY that tenant.
 *
 * <p>Both tenants are ingested by the same model. Corrupting tenant A's stamp key
 * ({@code t:tenant-a:intent_idx:model}) to a foreign model id makes A's index unsearchable in the
 * gateway's vector space (cosine across two spaces is confident nonsense). The per-tenant verifier
 * fails A closed while B — whose own stamp still matches — stays ready. Because each tenant reads its
 * own stamp key, one corrupt stamp can never shadow another tenant's verdict.
 */
class PerTenantStampTest extends RedisContainerTest {

    private static final TenantExecutionContext TENANT_A = tenant("tenant-a");
    private static final TenantExecutionContext TENANT_B = tenant("tenant-b");

    private JedisPooled jedis;
    private TenantKeyspace keyspace;
    private VectorIndex vectorIndex;
    private RegistryReadinessVerifier verifier;

    @BeforeEach
    void setUp() {
        jedis = new JedisPooled(redisHost(), redisPort());
        jedis.flushAll();
        keyspace = new TenantKeyspace(true, "default");

        QueryEmbedder query = queryEmbedder();
        vectorIndex = new VectorIndex(jedis, query, new SimpleMeterRegistry(), keyspace);
        VectorIndexWriter writer = new VectorIndexWriter(jedis, manifestEmbedder(), query, keyspace);

        writer.ensureIndex(TENANT_A);
        writer.index(manifest("acme.alpha.one", "domain.a"), TENANT_A);
        writer.ensureIndex(TENANT_B);
        writer.index(manifest("beta.bravo.one", "domain.b"), TENANT_B);

        // Default/legacy process gate so the verifier can be constructed.
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.count()).thenReturn(1L);
        writer.ensureIndex();
        writer.index(manifest("legacy.default.one", "domain.d"));

        verifier = new RegistryReadinessVerifier(vectorIndex, registry, query, keyspace, null, true);
        verifier.verify();
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) jedis.close();
    }

    @Test
    void modelMismatchFailsOnlyThatTenant() {
        // Sanity: both tenants ready before corruption.
        verifier.refreshReadiness(List.of(TENANT_A, TENANT_B));
        assertThat(verifier.isReady(TENANT_A)).isTrue();
        assertThat(verifier.isReady(TENANT_B)).isTrue();

        // Corrupt ONLY tenant A's model stamp.
        jedis.set(keyspace.key("intent_idx:model", TENANT_A), "foreign-model:999");

        verifier.refreshReadiness(List.of(TENANT_A, TENANT_B));

        assertThat(verifier.isReady(TENANT_A)).isFalse();
        assertThat(verifier.readiness().get("tenant-a").reason()).contains("foreign-model:999");
        assertThat(verifier.isReady(TENANT_B)).isTrue();          // B's own stamp still matches

        assertThatThrownBy(() -> verifier.requireReady(TENANT_A))
                .isInstanceOf(TenantRegistryNotReadyException.class);
        assertThatCode(() -> verifier.requireReady(TENANT_B)).doesNotThrowAnyException();

        // B's stamp is untouched by A's corruption.
        assertThat(vectorIndex.stampedModelId(TENANT_B)).isEqualTo(PerTenantTestSupport.MODEL);
    }
}
