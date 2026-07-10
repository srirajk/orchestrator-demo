package ai.conduit.gateway.registry.index;

import ai.conduit.gateway.registry.embedding.ManifestEmbedder;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
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
    /** Records which embedding model produced the vectors currently in the index. */
    private static final String MODEL_STAMP_KEY = "intent_idx:model";
    private static final int    TOP_K       = 20;  // fetch more, deduplicate later

    private final JedisPooled jedis;
    private final ManifestEmbedder manifestEmbedder;
    private final QueryEmbedder queryEmbedder;
    private final MeterRegistry meterRegistry;

    public VectorIndex(JedisPooled jedis,
                       ManifestEmbedder manifestEmbedder,
                       QueryEmbedder queryEmbedder,
                       MeterRegistry meterRegistry) {
        this.jedis            = jedis;
        this.manifestEmbedder = manifestEmbedder;
        this.queryEmbedder    = queryEmbedder;
        this.meterRegistry    = meterRegistry;
    }

    /**
     * Create the index if absent, and rebuild it whenever it was built by a different embedding
     * model than the one now configured.
     *
     * <p>Cosine similarity between vectors from two different models is arithmetic without meaning.
     * Previously nothing recorded which model built the index, so the only structural change this
     * method could detect was a missing {@code sub_domain} field; a change of provider, model, or
     * dimension passed unnoticed. The index now carries the model's identity, and a mismatch drops
     * both the index and its documents so they are rebuilt in one coherent space.
     */
    public void ensureIndex() {
        String currentModel = manifestEmbedder.modelId();
        if (!currentModel.equals(queryEmbedder.modelId())) {
            throw new IllegalStateException("Corpus and query embedders disagree on the model: '"
                    + currentModel + "' vs '" + queryEmbedder.modelId()
                    + "'. Documents and queries would occupy different vector spaces.");
        }

        String stampedModel = readStamp();
        boolean exists;
        boolean hasSubDomain;
        try {
            var info = jedis.ftInfo(INDEX_NAME);
            exists = true;
            hasSubDomain = info.values().stream()
                    .anyMatch(v -> v != null && v.toString().contains("sub_domain"));
        } catch (Exception e) {
            exists = false;
            hasSubDomain = false;
        }

        if (exists && hasSubDomain && currentModel.equals(stampedModel)) {
            log.info("Vector index '{}' is current (model={})", INDEX_NAME, currentModel);
            return;
        }

        if (exists) {
            String why = !hasSubDomain
                    ? "missing sub_domain field"
                    : "built by embedding model '" + stampedModel + "' but '" + currentModel + "' is configured";
            log.warn("Rebuilding vector index '{}' — {}", INDEX_NAME, why);
            dropIndexAndDocuments();
        }
        createIndex();
        writeStamp(currentModel);
    }

    private String readStamp() {
        try {
            return jedis.get(MODEL_STAMP_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeStamp(String modelId) {
        try {
            jedis.set(MODEL_STAMP_KEY, modelId);
        } catch (Exception e) {
            log.warn("Could not stamp the vector index with model '{}': {}", modelId, e.getMessage());
        }
    }

    /**
     * Drop the index and the documents it indexed. Dropping the index alone would leave the old
     * vectors behind under the same keys, to be silently re-indexed into the new schema.
     */
    private void dropIndexAndDocuments() {
        try { jedis.ftDropIndex(INDEX_NAME); } catch (Exception ignored) { }
        var cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START;
        var scanParams = new redis.clients.jedis.params.ScanParams().match(KEY_PREFIX + "*").count(500);
        int deleted = 0;
        do {
            var result = jedis.scan(cursor, scanParams);
            for (String key : result.getResult()) {
                jedis.del(key);
                deleted++;
            }
            cursor = result.getCursor();
        } while (!cursor.equals(redis.clients.jedis.params.ScanParams.SCAN_POINTER_START));
        log.info("Dropped vector index '{}' and {} stale document(s)", INDEX_NAME, deleted);
    }

    private void createIndex() {
        int dim = manifestEmbedder.dimension();
        log.info("Creating HNSW vector index '{}' (dim={}, model={})",
                INDEX_NAME, dim, manifestEmbedder.modelId());

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
                TagField.of("sub_domain"),
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
        // One batched, content-addressed call for the agent's whole corpus rather than a round trip
        // per example. Unchanged examples are served from cache and never reach the model.
        List<float[]> vectors = manifestEmbedder.embedCorpus(examples);

        for (int i = 0; i < examples.size(); i++) {
            String key = KEY_PREFIX + manifest.agentId() + ":" + i;
            float[] vec = vectors.get(i);

            // Store metadata fields as strings
            Map<String, String> stringFields = new HashMap<>();
            stringFields.put("agent_id",   manifest.agentId());
            stringFields.put("domain",     manifest.domain());
            stringFields.put("sub_domain", manifest.subDomain() != null ? manifest.subDomain() : "");
            // Internal read-only routing flag: 0 = read (fannable), 1 = write. Derived from the
            // manifest access_mode (formerly the is_mutating boolean); index field name kept stable
            // so the "@is_mutating:[0 0]" read-only filter needs no reindex.
            stringFields.put("is_mutating",
                    "write".equals(manifest.constraints().accessMode()) ? "1" : "0");
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
