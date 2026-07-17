package ai.conduit.gateway.registry.index;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
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

import ai.conduit.gateway.infrastructure.expression.ExpressionDialect;

import static ai.conduit.gateway.registry.index.VectorIndex.EXPR_DIALECT_STAMP_KEY;
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
 *
 * <p><b>Per-tenant write side (Axiom A4).</b> Every index name, key prefix, and model/dialect stamp
 * key is resolved through the {@link TenantKeyspace} seam that A3 built for the read side, so the
 * WRITE side names match the READ side by construction. The default tenant (or multi-tenancy off)
 * resolves to the LEGACY names ({@code intent_idx}, {@code vec:}, {@code intent_idx:model}) — so a
 * default ingest writes exactly what the single-tenant demo reads, byte-for-byte. A real tenant
 * resolves to {@code intent_idx__{tenant}} / {@code t:{tenant}:vec:} / {@code t:{tenant}:intent_idx:model}:
 * a physically separate index and key space, so one tenant's ingest — including a rebuild that drops
 * and recreates — cannot touch another tenant's routing data. The no-argument methods are the legacy
 * seam and delegate with a null context.
 */
@Service
@Profile("registry")
public class VectorIndexWriter {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexWriter.class);

    private final JedisPooled jedis;
    private final ManifestEmbedder manifestEmbedder;
    private final QueryEmbedder queryEmbedder;
    private final TenantKeyspace keyspace;

    /**
     * Legacy/single-tenant constructor: no tenant seam, every name resolves to the legacy scheme.
     * Retained so mock-based unit tests and any single-tenant wiring keep the exact pre-A4 behaviour.
     */
    public VectorIndexWriter(JedisPooled jedis,
                             ManifestEmbedder manifestEmbedder,
                             QueryEmbedder queryEmbedder) {
        this(jedis, manifestEmbedder, queryEmbedder, new TenantKeyspace(false, "default"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VectorIndexWriter(JedisPooled jedis,
                             ManifestEmbedder manifestEmbedder,
                             QueryEmbedder queryEmbedder,
                             TenantKeyspace keyspace) {
        this.jedis            = jedis;
        this.manifestEmbedder = manifestEmbedder;
        this.queryEmbedder    = queryEmbedder;
        this.keyspace         = keyspace;
    }

    // ── legacy/default seam (null context ⇒ legacy names) ────────────────────────────────────────

    /** Ensure the default/legacy routing index. */
    public void ensureIndex() {
        ensureIndex(null);
    }

    /** Index one agent into the default/legacy routing index. */
    public void index(AgentManifest manifest) {
        index(manifest, null);
    }

    /** Remove one agent from the default/legacy routing index. */
    public void removeAgent(String agentId) {
        removeAgent(agentId, null);
    }

    // ── per-tenant seam (Axiom A4) ───────────────────────────────────────────────────────────────

    /**
     * Create this tenant's index if absent, and rebuild it whenever it was built by a different
     * embedding model than the one now configured.
     *
     * <p>Cosine similarity between vectors from two different models is arithmetic without meaning.
     * The index carries the model's identity so a change of provider, model, or dimension forces a
     * rebuild in one coherent space rather than passing unnoticed. The stamp is per tenant, so a
     * mismatch fails only that tenant; a healthy sibling keeps its own current stamp.
     */
    public void ensureIndex(TenantExecutionContext tenant) {
        String indexName    = keyspace.indexName(INDEX_NAME, tenant);
        String keyPrefix    = keyspace.key(KEY_PREFIX, tenant);
        String modelStamp   = keyspace.key(MODEL_STAMP_KEY, tenant);
        String dialectStamp = keyspace.key(EXPR_DIALECT_STAMP_KEY, tenant);

        String currentModel = manifestEmbedder.modelId();
        if (!currentModel.equals(queryEmbedder.modelId())) {
            throw new IllegalStateException("Corpus and query embedders disagree on the model: '"
                    + currentModel + "' vs '" + queryEmbedder.modelId()
                    + "'. Documents and queries would occupy different vector spaces.");
        }

        // Stamp the expression dialect these manifests were ingested in — independent of the embedding
        // model rebuild below, and rewritten every ingest so a dialect flip always re-stamps. The
        // gateway's RegistryReadinessVerifier refuses to start on a mismatch (cross-container skew gate).
        writeExprDialectStamp(dialectStamp);

        String stampedModel = readStamp(modelStamp);
        boolean exists;
        boolean hasSubDomain;
        try {
            var info = jedis.ftInfo(indexName);
            exists = true;
            hasSubDomain = info.values().stream()
                    .anyMatch(v -> v != null && v.toString().contains("sub_domain"));
        } catch (Exception e) {
            exists = false;
            hasSubDomain = false;
        }

        if (exists && hasSubDomain && currentModel.equals(stampedModel)) {
            log.info("Vector index '{}' is current (model={})", indexName, currentModel);
            return;
        }

        if (exists) {
            String why = !hasSubDomain
                    ? "missing sub_domain field"
                    : "built by embedding model '" + stampedModel + "' but '" + currentModel + "' is configured";
            log.warn("Rebuilding vector index '{}' — {}", indexName, why);
            dropIndexAndDocuments(indexName, keyPrefix);
        }
        createIndex(indexName, keyPrefix);
        writeStamp(modelStamp, currentModel);
    }

    /**
     * Index all example prompts for one agent into this tenant's index.
     * Existing entries for this agent_id are deleted first (clean re-index on update).
     */
    public void index(AgentManifest manifest, TenantExecutionContext tenant) {
        String keyPrefix = keyspace.key(KEY_PREFIX, tenant);
        removeAgent(manifest.agentId(), tenant);

        List<String> examples = manifest.allExamples();
        // One batched, content-addressed call for the agent's whole corpus rather than a round trip
        // per example. Unchanged examples are served from cache and never reach the model. The cache is
        // content-addressed (model-id + text digest), so it is tenant-agnostic by construction: the same
        // example text yields the same vector regardless of which tenant is ingesting.
        List<float[]> vectors = manifestEmbedder.embedCorpus(examples);

        for (int i = 0; i < examples.size(); i++) {
            String key = keyPrefix + manifest.agentId() + ":" + i;
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
        log.debug("Indexed {} examples for agent '{}' (index='{}')",
                examples.size(), manifest.agentId(), keyspace.indexName(INDEX_NAME, tenant));
    }

    /** Remove all vector entries for an agent from this tenant's index. */
    public void removeAgent(String agentId, TenantExecutionContext tenant) {
        deleteByPattern(keyspace.key(KEY_PREFIX, tenant) + agentId + ":*");
    }

    private String readStamp(String modelStampKey) {
        try {
            return jedis.get(modelStampKey);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeStamp(String modelStampKey, String modelId) {
        try {
            jedis.set(modelStampKey, modelId);
        } catch (Exception e) {
            log.warn("Could not stamp the vector index with model '{}': {}", modelId, e.getMessage());
        }
    }

    private void writeExprDialectStamp(String dialectStampKey) {
        try {
            jedis.set(dialectStampKey, ExpressionDialect.CURRENT);
            log.info("Stamped routing index with expression dialect '{}'", ExpressionDialect.CURRENT);
        } catch (Exception e) {
            log.warn("Could not stamp the vector index with expression dialect '{}': {}",
                    ExpressionDialect.CURRENT, e.getMessage());
        }
    }

    /**
     * Drop the index and the documents it indexed. Dropping the index alone would leave the old
     * vectors behind under the same keys, to be silently re-indexed into the new schema. Scoped to
     * this tenant's key prefix, so a tenant rebuild never scans or deletes another tenant's vectors.
     */
    private void dropIndexAndDocuments(String indexName, String keyPrefix) {
        try { jedis.ftDropIndex(indexName); } catch (Exception ignored) { }
        int deleted = deleteByPattern(keyPrefix + "*");
        log.info("Dropped vector index '{}' and {} stale document(s)", indexName, deleted);
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

    private void createIndex(String indexName, String keyPrefix) {
        int dim = manifestEmbedder.dimension();
        log.info("Creating HNSW vector index '{}' (dim={}, model={}, prefix='{}')",
                indexName, dim, manifestEmbedder.modelId(), keyPrefix);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("TYPE", "FLOAT32");
        attrs.put("DIM", dim);
        attrs.put("DISTANCE_METRIC", "COSINE");
        attrs.put("INITIAL_CAP", 500);
        attrs.put("M", 16);
        attrs.put("EF_CONSTRUCTION", 200);

        jedis.ftCreate(indexName,
                FTCreateParams.createParams()
                        .on(redis.clients.jedis.search.IndexDataType.HASH)
                        .prefix(keyPrefix),
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
        log.info("Vector index '{}' created", indexName);
    }
}
