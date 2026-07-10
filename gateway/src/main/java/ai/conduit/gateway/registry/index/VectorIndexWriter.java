package ai.conduit.gateway.registry.index;

import ai.conduit.gateway.registry.embedding.ManifestEmbedder;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.model.AgentManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.conduit.gateway.registry.index.VectorIndex.INDEX_NAME;
import static ai.conduit.gateway.registry.index.VectorIndex.KEY_PREFIX;
import static ai.conduit.gateway.registry.index.VectorIndex.MODEL_STAMP_KEY;

/**
 * Builds the routing vector index. Exists only in the {@code registry} profile.
 *
 * <p>Ingestion is a separate concern from resolution, and now a separate service. The gateway
 * resolves; the ingestor produces. Because this bean is absent from the gateway's context, the
 * gateway holds no code path that can create, overwrite, or drop the index — the guarantee is a
 * missing bean, not a discipline.
 */
@Service
@Profile("registry")
public class VectorIndexWriter {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexWriter.class);

    private final JedisPooled jedis;
    private final ManifestEmbedder manifestEmbedder;
    private final QueryEmbedder queryEmbedder;

    public VectorIndexWriter(JedisPooled jedis,
                             ManifestEmbedder manifestEmbedder,
                             QueryEmbedder queryEmbedder) {
        this.jedis            = jedis;
        this.manifestEmbedder = manifestEmbedder;
        this.queryEmbedder    = queryEmbedder;
    }

    /**
     * Create the index if absent, and rebuild it whenever it was built by a different embedding
     * model than the one now configured.
     *
     * <p>Cosine similarity between vectors from two different models is arithmetic without meaning.
     * The index carries the model's identity so a change of provider, model, or dimension forces a
     * rebuild in one coherent space rather than passing unnoticed.
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

    /**
     * Index all example prompts for one agent.
     * Existing entries for this agent_id are deleted first (clean re-index on update).
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

            Map<String, String> stringFields = new HashMap<>();
            stringFields.put("agent_id",   manifest.agentId());
            stringFields.put("domain",     manifest.domain());
            stringFields.put("sub_domain", manifest.subDomain() != null ? manifest.subDomain() : "");
            // Internal read-only routing flag: 0 = read (fannable), 1 = write. Derived from the
            // manifest access_mode; index field name kept stable so the "@is_mutating:[0 0]"
            // read-only filter needs no reindex.
            stringFields.put("is_mutating",
                    "write".equals(manifest.constraints().accessMode()) ? "1" : "0");
            jedis.hset(key, stringFields);

            // Store vector as raw bytes — required for RediSearch VECTOR field
            jedis.hset(key.getBytes(), "embedding".getBytes(), VectorIndex.floatsToBytes(vec));
        }
        log.debug("Indexed {} examples for agent '{}'", examples.size(), manifest.agentId());
    }

    /** Remove all vector entries for an agent. */
    public void removeAgent(String agentId) {
        deleteByPattern(KEY_PREFIX + agentId + ":*");
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
        int deleted = deleteByPattern(KEY_PREFIX + "*");
        log.info("Dropped vector index '{}' and {} stale document(s)", INDEX_NAME, deleted);
    }

    private int deleteByPattern(String pattern) {
        var cursor = ScanParams.SCAN_POINTER_START;
        var scanParams = new ScanParams().match(pattern).count(500);
        int deleted = 0;
        do {
            var result = jedis.scan(cursor, scanParams);
            for (String key : result.getResult()) {
                jedis.del(key);
                deleted++;
            }
            cursor = result.getCursor();
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        return deleted;
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
}
