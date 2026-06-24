package ai.meridian.gateway.registry.index;

import ai.meridian.gateway.registry.model.AgentManifest;
import ai.meridian.gateway.registry.model.RoutingCandidate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.FTSearchParams;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HNSW vector index over example-prompt embeddings.
 *
 * Index name:  intent_idx
 * Key prefix:  vec:{agent_id}:{example_index}
 * Fields:
 *   agent_id      (TAG)   — exact filter
 *   domain        (TAG)   — routing filter (wealth-management | asset-servicing)
 *   is_mutating   (NUMERIC) — must be 0 for read-only fan-out
 *   embedding     (VECTOR HNSW 384-dim COSINE)
 *
 * Each example prompt is stored as a separate vector entry so the index has
 * dense coverage. At search time we deduplicate by agent_id and keep max score.
 */
@Service
public class VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);

    private static final String INDEX_NAME  = "intent_idx";
    private static final String KEY_PREFIX  = "vec:";
    private static final int    TOP_K       = 20;  // fetch more, deduplicate later

    private final JedisPooled jedis;
    private final EmbeddingClient embedding;
    private final MeterRegistry meterRegistry;

    public VectorIndex(JedisPooled jedis, EmbeddingClient embedding, MeterRegistry meterRegistry) {
        this.jedis         = jedis;
        this.embedding     = embedding;
        this.meterRegistry = meterRegistry;
    }

    public void ensureIndex() {
        try {
            jedis.ftInfo(INDEX_NAME);
            log.info("Vector index '{}' already exists", INDEX_NAME);
        } catch (Exception e) {
            createIndex();
        }
    }

    private void createIndex() {
        int dim = embedding.dimension();
        log.info("Creating HNSW vector index '{}' (dim={})", INDEX_NAME, dim);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("TYPE", "FLOAT32");
        attrs.put("DIM", dim);
        attrs.put("DISTANCE_METRIC", "COSINE");
        attrs.put("INITIAL_CAP", 500);
        attrs.put("M", 16);
        attrs.put("EF_CONSTRUCTION", 200);

        jedis.ftCreate(INDEX_NAME,
                FTCreateParams.createParams()
                        .on(redis.clients.jedis.search.IndexDataType.HASH)
                        .prefix(KEY_PREFIX),
                TagField.of("agent_id"),
                TagField.of("domain"),
                NumericField.of("is_mutating"),
                VectorField.builder()
                        .fieldName("embedding")
                        .algorithm(VectorField.VectorAlgorithm.HNSW)
                        .attributes(attrs)
                        .build()
        );
        log.info("Vector index '{}' created", INDEX_NAME);
    }

    /**
     * Index all example prompts for one agent.
     * Existing entries for this agent_id are deleted first (clean re-index on update).
     *
     * Redis HASH vector fields require:
     *   - String fields stored as regular HSET string key/value pairs
     *   - The vector field stored as raw bytes via HSET byte[] overload
     */
    public void index(AgentManifest manifest) {
        removeAgent(manifest.agentId());

        List<String> examples = manifest.allExamples();
        for (int i = 0; i < examples.size(); i++) {
            String key = KEY_PREFIX + manifest.agentId() + ":" + i;
            float[] vec = embedding.embed(examples.get(i));

            // Store metadata fields as strings
            Map<String, String> stringFields = new HashMap<>();
            stringFields.put("agent_id",   manifest.agentId());
            stringFields.put("domain",     manifest.domain());
            stringFields.put("is_mutating", manifest.constraints().isMutating() ? "1" : "0");
            jedis.hset(key, stringFields);

            // Store vector as raw bytes — required for RediSearch VECTOR field
            jedis.hset(key.getBytes(), "embedding".getBytes(), floatsToBytes(vec));
        }
        log.debug("Indexed {} examples for agent '{}'", examples.size(), manifest.agentId());
    }

    /**
     * Remove all vector entries for an agent.
     */
    public void removeAgent(String agentId) {
        // Scan with prefix pattern to find all keys for this agent
        String pattern = KEY_PREFIX + agentId + ":*";
        var cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START;
        var scanParams = new redis.clients.jedis.params.ScanParams().match(pattern).count(100);
        do {
            var result = jedis.scan(cursor, scanParams);
            result.getResult().forEach(jedis::del);
            cursor = result.getCursor();
        } while (!cursor.equals(redis.clients.jedis.params.ScanParams.SCAN_POINTER_START));
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
            float[] queryVec = embedding.embed(queryText);
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

    private static byte[] floatsToBytes(float[] floats) {
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
