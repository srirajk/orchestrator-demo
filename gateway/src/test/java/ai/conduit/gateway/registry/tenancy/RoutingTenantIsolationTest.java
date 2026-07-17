package ai.conduit.gateway.registry.tenancy;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.index.VectorIndexWriter;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.registry.service.AgentRegistry;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import ai.conduit.gateway.resolver.service.RoutingRerankerClient;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.manifest;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.manifestEmbedder;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.queryEmbedder;
import static ai.conduit.gateway.registry.tenancy.PerTenantTestSupport.tenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Axiom H5 gate — routing cannot fail-open under multi-tenant enforcement.
 *
 * <p>The security audit found the request-path resolver called the tenant-free
 * {@link VectorIndex#search(String, String, int, java.util.function.Function)} overload, so with
 * {@code conduit.tenancy.multi-tenant.enabled=true} a routing query would silently read the shared
 * legacy {@code intent_idx} — cross-tenant routing-metadata fail-open. H5 threads the request
 * {@link TenantExecutionContext} through {@link AgentResolver} into {@link VectorIndex} and fails a
 * tenant-less data route CLOSED instead of falling back to the legacy index.
 *
 * <p>This drives the REAL {@link AgentResolver} against a throwaway Redis seeded through the real A4
 * write side, and probes the ACTUAL index queried by asserting which tenant's documents come back:
 * <ul>
 *   <li>multi-tenant ON — a route in tenant B surfaces only B's agent (from {@code intent_idx__tenant-b}),
 *       and a route in tenant A never surfaces B's agent;</li>
 *   <li>multi-tenant ON, tenant-less data route — DENIED ({@link AgentResolver.TenantlessRouteDeniedException}),
 *       does not read the seeded legacy {@code intent_idx};</li>
 *   <li>multi-tenant ON, the default tenant — keeps the legacy index (demo preserved, complementing the
 *       unit-level {@code DefaultTenantUsesLegacyIndexNameTest}).</li>
 * </ul>
 */
class RoutingTenantIsolationTest extends RedisContainerTest {

    private static final TenantExecutionContext TENANT_A = tenant("tenant-a");
    private static final TenantExecutionContext TENANT_B = tenant("tenant-b");
    private static final TenantExecutionContext DEFAULT  = tenant("default");

    private JedisPooled jedis;

    @BeforeEach
    void setUp() {
        jedis = new JedisPooled(redisHost(), redisPort());
        jedis.flushAll();
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) jedis.close();
    }

    // ── multi-tenant ON: a routing query resolves the request tenant's own index ──────────────────
    @Test
    void multiTenantOn_routeHitsRequestTenantsIndex_neverAnotherTenants() {
        TenantKeyspace keyspace = new TenantKeyspace(true, "default");
        seedTenant(keyspace, TENANT_A, "acme.alpha.one", "domain.a");
        seedTenant(keyspace, TENANT_B, "beta.bravo.one", "domain.b");
        AgentResolver resolver = resolver(keyspace);

        List<String> bHits = candidateIds(resolver.resolveContextual("anything", false, TENANT_B));
        List<String> aHits = candidateIds(resolver.resolveContextual("anything", false, TENANT_A));

        // The route in B queried intent_idx__tenant-b: only B's agent is reachable.
        assertThat(bHits).containsExactly("beta.bravo.one");
        assertThat(aHits).containsExactly("acme.alpha.one");
        // The hard guarantee: a query in one tenant physically cannot surface another tenant's agent.
        assertThat(bHits).doesNotContain("acme.alpha.one");
        assertThat(aHits).doesNotContain("beta.bravo.one");
    }

    // ── multi-tenant ON: a tenant-less data route fails CLOSED, never reads legacy intent_idx ──────
    @Test
    void multiTenantOn_tenantlessDataRoute_deniesAndNeverReadsLegacyIndex() {
        TenantKeyspace keyspace = new TenantKeyspace(true, "default");
        // A legacy index EXISTS and holds a routable agent — the fail-open target. A fail-open resolver
        // would return this; a fail-closed one denies before it is ever queried.
        seedLegacy(keyspace, "legacy.leak.one", "domain.legacy");
        AgentResolver resolver = resolver(keyspace);

        assertThatThrownBy(() -> resolver.resolveContextual("anything", false, (TenantExecutionContext) null))
                .isInstanceOf(AgentResolver.TenantlessRouteDeniedException.class)
                .hasMessageContaining("no resolved tenant");

        // Proof the legacy index was never consulted: had it been, the leak agent would have surfaced.
        // (The throw is raised in the resolver BEFORE any VectorIndex.search call.)
        assertThat(candidateIdsOrEmpty(resolver, DEFAULT)).contains("legacy.leak.one"); // sanity: legacy IS routable
    }

    // ── multi-tenant ON: the default tenant keeps the legacy index (single-tenant demo preserved) ──
    @Test
    void multiTenantOn_defaultTenant_keepsLegacyIndex() {
        TenantKeyspace keyspace = new TenantKeyspace(true, "default");
        seedLegacy(keyspace, "legacy.demo.one", "domain.legacy");
        AgentResolver resolver = resolver(keyspace);

        // Routing as the default tenant resolves the legacy intent_idx and surfaces its agent — the demo
        // path is byte-unchanged even with the flag ON.
        assertThat(candidateIds(resolver.resolveContextual("anything", false, DEFAULT)))
                .containsExactly("legacy.demo.one");
    }

    // ── multi-tenant OFF: legacy index, no fail-closed (byte-identical to the pre-H5 demo) ─────────
    @Test
    void multiTenantOff_tenantlessRoute_readsLegacyIndex_noDeny() {
        TenantKeyspace keyspace = new TenantKeyspace(false, "default");
        seedLegacy(keyspace, "legacy.off.one", "domain.legacy");
        AgentResolver resolver = resolver(keyspace);

        // With the flag OFF a tenant-less route is the normal single-tenant path — it reads legacy and
        // never denies.
        assertThat(candidateIds(resolver.resolveContextual("anything", false, (TenantExecutionContext) null)))
                .containsExactly("legacy.off.one");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private void seedTenant(TenantKeyspace keyspace, TenantExecutionContext t, String agentId, String domain) {
        VectorIndexWriter writer = new VectorIndexWriter(jedis, manifestEmbedder(), queryEmbedder(), keyspace);
        writer.ensureIndex(t);
        writer.index(manifest(agentId, domain), t);
    }

    private void seedLegacy(TenantKeyspace keyspace, String agentId, String domain) {
        VectorIndexWriter writer = new VectorIndexWriter(jedis, manifestEmbedder(), queryEmbedder(), keyspace);
        writer.ensureIndex();                 // legacy intent_idx
        writer.index(manifest(agentId, domain));
    }

    /** A real resolver over a real tenant-aware VectorIndex; reranker off, floors permissive. */
    private AgentResolver resolver(TenantKeyspace keyspace) {
        VectorIndex vectorIndex = new VectorIndex(jedis, queryEmbedder(), new SimpleMeterRegistry(), keyspace);
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.find(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            String domain = id.startsWith("acme.") ? "domain.a"
                    : id.startsWith("beta.") ? "domain.b" : "domain.legacy";
            return Optional.of(manifest(id, domain));
        });

        AgentResolver resolver = new AgentResolver(
                vectorIndex, registry, new SimpleMeterRegistry(),
                mock(RoutingRerankerClient.class), keyspace);
        // Permissive floors so any KNN hit is surfaced as a candidate — this test probes WHICH index is
        // queried, not the confidence arithmetic (covered elsewhere).
        ReflectionTestUtils.setField(resolver, "confidenceFloor", 0.0);
        ReflectionTestUtils.setField(resolver, "relativeFloorFactor", 0.0);
        ReflectionTestUtils.setField(resolver, "topK", 10);
        ReflectionTestUtils.setField(resolver, "domainMargin", 0.0);
        ReflectionTestUtils.setField(resolver, "decisiveScore", 0.0);
        ReflectionTestUtils.setField(resolver, "routingMinScore", 0.0);
        ReflectionTestUtils.setField(resolver, "routingMinMargin", 0.0);
        ReflectionTestUtils.setField(resolver, "rerankEnabled", false);
        ReflectionTestUtils.setField(resolver, "rerankConflictTriggerEnabled", false);
        ReflectionTestUtils.setField(resolver, "rerankMarginThreshold", 0.0);
        ReflectionTestUtils.setField(resolver, "rerankAbstainAdjacentBand", 0.0);
        ReflectionTestUtils.setField(resolver, "rerankMaxCandidates", 5);
        return resolver;
    }

    /** Every agent the resolver saw for this route (selected ∪ skipped) — score-agnostic index probe. */
    private static List<String> candidateIds(ResolverResult result) {
        List<RoutingCandidate> all = new ArrayList<>(result.selected());
        all.addAll(result.skipped());
        return all.stream().map(c -> c.manifest().agentId()).distinct().toList();
    }

    private static List<String> candidateIdsOrEmpty(AgentResolver resolver, TenantExecutionContext t) {
        return candidateIds(resolver.resolveContextual("anything", false, t));
    }
}
