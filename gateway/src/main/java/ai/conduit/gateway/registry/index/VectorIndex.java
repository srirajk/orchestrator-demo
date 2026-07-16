package ai.conduit.gateway.registry.index;

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
 * <p>Index name:  intent_idx<br>
 * Key prefix:  vec:{agent_id}:{example_index}
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

    public VectorIndex(JedisPooled jedis, QueryEmbedder queryEmbedder, MeterRegistry meterRegistry) {
        this.jedis         = jedis;
        this.queryEmbedder = queryEmbedder;
        this.meterRegistry = meterRegistry;
    }

    /** The embedding model that produced the vectors in the index, or null if it was never stamped. */
    public String stampedModelId() {
        try {
            return jedis.get(MODEL_STAMP_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    /** The manifest expression dialect the registry ingested with, or null if it was never stamped. */
    public String stampedExprDialect() {
        try {
            return jedis.get(EXPR_DIALECT_STAMP_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the index itself exists in Redis. */
    public boolean exists() {
        try {
            jedis.ftInfo(INDEX_NAME);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * KNN search returning top candidates.
     * Filters: is_mutating == 0 (read-only agents only).
     * Optionally filters by domain.
     */
    public List<RoutingCandidate> search(String queryText, String domain, int topK,
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

            SearchResult result = jedis.ftSearch(INDEX_NAME, query);

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
