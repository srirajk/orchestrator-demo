package ai.conduit.gateway.registry.index;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of the HNSW vector index over example-prompt embeddings.
 *
 * <p>Index name:  intent_idx (default/single-tenant) — {@code intent_idx__{tenant}} for a real
 * tenant<br>
 * Key prefix:  vec:{agent_id}:{example_index} (default) — {@code t:{tenant}:vec:...} for a real
 * tenant
 *
 * <p><b>Tenant isolation (Axiom A3).</b> The index name and key prefix are resolved through the
 * {@link TenantKeyspace} seam from the request's {@link TenantExecutionContext}. For the default
 * tenant (or with multi-tenancy off) the seam returns the LEGACY names ({@code intent_idx},
 * {@code vec:}), so a default-tenant routing query reads exactly the index the registry-service
 * wrote — the single-tenant demo is unaffected. The per-tenant {@code intent_idx__{tenant}} scheme
 * activates only for real tenants (whose per-tenant ingestion is A4, not yet built). The
 * no-tenant-argument {@link #search(String, String, int, java.util.function.Function)} overload
 * resolves to the legacy names and is what the current request path uses.
 *
 * <p><b>This class cannot write.</b> Building the index — creating it, embedding the agent corpus,
 * and writing the vectors — belongs to {@link VectorIndexWriter}, which exists only in the
 * {@code ingest} profile. The gateway is a reader of routing data, not its producer: it holds no
 * corpus embedder and no index writer, so no future code path in it can rebuild, overwrite, or drop
 * the index. That separation is structural rather than conventional because the previous
 * arrangement let a {@code mvn test} run drop the live routing index.
 */
@Service
public class VectorIndex {

    static final String INDEX_NAME      = "intent_idx";
    static final String KEY_PREFIX      = "vec:";
    /** Records which embedding model produced the vectors currently in the index. */
    static final String MODEL_STAMP_KEY = "intent_idx:model";
    /** Records which manifest expression dialect the registry ingested the manifests in. */
    static final String EXPR_DIALECT_STAMP_KEY = "intent_idx:expr_dialect";

    private static final int TOP_K = 20;  // fetch more, deduplicate later

    private final JedisPooled jedis;
    private final QueryEmbedder queryEmbedder;
    private final MeterRegistry meterRegistry;
    private final TenantKeyspace keyspace;

    public VectorIndex(JedisPooled jedis, QueryEmbedder queryEmbedder, MeterRegistry meterRegistry,
                       TenantKeyspace keyspace) {
        this.jedis         = jedis;
        this.queryEmbedder = queryEmbedder;
        this.meterRegistry = meterRegistry;
        this.keyspace      = keyspace;
    }

    /** The embedding model that produced the vectors in the default/legacy index, or null if unstamped. */
    public String stampedModelId() {
        return stampedModelId(null);
    }

    /**
     * The embedding model that produced the vectors in this tenant's index, or null if unstamped.
     * The stamp key is tenant-qualified through {@link TenantKeyspace}: legacy {@code intent_idx:model}
     * for the default tenant, {@code t:{tenant}:intent_idx:model} for a real tenant — so one tenant's
     * model-mismatch verdict is read from that tenant's own stamp and cannot shadow another's.
     */
    public String stampedModelId(TenantExecutionContext tenant) {
        try {
            return jedis.get(keyspace.key(MODEL_STAMP_KEY, tenant));
        } catch (Exception e) {
            return null;
        }
    }

    /** The manifest expression dialect the default/legacy index was ingested with, or null if unstamped. */
    public String stampedExprDialect() {
        return stampedExprDialect(null);
    }

    /** The manifest expression dialect this tenant's index was ingested with, or null if unstamped. */
    public String stampedExprDialect(TenantExecutionContext tenant) {
        try {
            return jedis.get(keyspace.key(EXPR_DIALECT_STAMP_KEY, tenant));
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the default/legacy index itself exists in Redis. */
    public boolean exists() {
        return exists(null);
    }

    /** Whether this tenant's routing index exists in Redis (per-tenant name via {@link TenantKeyspace}). */
    public boolean exists(TenantExecutionContext tenant) {
        try {
            jedis.ftInfo(keyspace.indexName(INDEX_NAME, tenant));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * How many documents this tenant's index holds — the per-tenant "non-empty" signal the readiness
     * verifier gates on. A tenant whose ingest wrote nothing (broken/empty folder) has a 0 count and is
     * failed closed without touching any other tenant's index. Returns 0 if the index is absent.
     */
    public long documentCount(TenantExecutionContext tenant) {
        try {
            var info = jedis.ftInfo(keyspace.indexName(INDEX_NAME, tenant));
            Object numDocs = info.get("num_docs");
            if (numDocs == null) return 0L;
            return Long.parseLong(numDocs.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * KNN search returning top candidates, in the default/single-tenant keyspace.
     * Filters: is_mutating == 0 (read-only agents only).
     * Optionally filters by domain.
     *
     * <p>Resolves the index name through the {@link TenantKeyspace} seam with no tenant context,
     * i.e. the legacy {@code intent_idx} — byte-identical to the pre-A3 behaviour and what the
     * current request path uses. The tenant-aware overload
     * {@link #search(String, String, int, TenantExecutionContext, java.util.function.Function)} is
     * the seam A4 wires once per-tenant ingestion exists.
     */
    public List<RoutingCandidate> search(String queryText, String domain, int topK,
                                          java.util.function.Function<String, AgentManifest> manifestLoader) {
        return search(queryText, domain, topK, null, manifestLoader);
    }

    /**
     * KNN search against the tenant-qualified routing index. For the default tenant (or with
     * multi-tenancy off) this is the legacy {@code intent_idx}; for a real tenant it is that
     * tenant's own {@code intent_idx__{tenant}} — a per-tenant index, so a query issued in one
     * tenant's context physically cannot return another tenant's documents.
     */
    public List<RoutingCandidate> search(String queryText, String domain, int topK,
                                          TenantExecutionContext tenant,
                                          java.util.function.Function<String, AgentManifest> manifestLoader) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            float[] queryVec = queryEmbedder.embed(queryText);
            byte[] queryBytes = floatsToBytes(queryVec);

            String filterClause = "@is_mutating:[0 0]";
            if (domain != null && !domain.isBlank()) {
                filterClause = "(@is_mutating:[0 0] @domain:{" + escapeDomain(domain) + "})";
            }

            String q = String.format("(%s)=>[KNN %d @embedding $BLOB AS score]", filterClause, TOP_K);
            Query query = new Query(q)
                    .addParam("BLOB", queryBytes)
                    .returnFields("agent_id", "domain", "is_mutating", "score")
                    .setSortBy("score", true)
                    .dialect(2)
                    .limit(0, TOP_K);

            SearchResult result = jedis.ftSearch(keyspace.indexName(INDEX_NAME, tenant), query);

            // Deduplicate by agent_id, keeping best (lowest distance = highest similarity)
            Map<String, Double> best = new HashMap<>();
            for (var doc : result.getDocuments()) {
                String agentId = (String) doc.get("agent_id");
                double score   = 1.0 - parseScore(doc.get("score")); // cosine dist → similarity
                best.merge(agentId, score, Math::max);
            }

            List<RoutingCandidate> candidates = new ArrayList<>();
            best.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(topK)
                    .forEach(e -> {
                        AgentManifest m = manifestLoader.apply(e.getKey());
                        if (m != null) candidates.add(new RoutingCandidate(m, e.getValue()));
                    });

            return candidates;

        } finally {
            sample.stop(meterRegistry.timer("resolver.route.latency"));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static byte[] floatsToBytes(float[] floats) {
        ByteBuffer bb = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) bb.putFloat(f);
        return bb.array();
    }

    private static double parseScore(Object raw) {
        if (raw == null) return 1.0;
        try { return Double.parseDouble(raw.toString()); } catch (Exception e) { return 1.0; }
    }

    private static String escapeDomain(String domain) {
        return domain.replace("-", "\\-");
    }
}
