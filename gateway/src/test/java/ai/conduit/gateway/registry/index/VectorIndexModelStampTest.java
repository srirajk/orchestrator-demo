package ai.conduit.gateway.registry.index;

import ai.conduit.gateway.registry.embedding.ManifestEmbedder;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.schemafields.SchemaField;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cosine similarity between vectors produced by two different embedding models is arithmetic
 * without meaning: it returns a confident number for an unrelated comparison. Nothing recorded
 * which model built the routing index, so swapping the provider, the model, or the dimension left
 * an index full of one model's vectors being searched with another's — and no error anywhere.
 *
 * <p>The index now carries the model's identity. These tests pin what that stamp buys.
 */
class VectorIndexModelStampTest {

    private static final String INDEX = "intent_idx";
    private static final String STAMP = "intent_idx:model";

    private static ManifestEmbedder manifestEmbedder(String modelId) {
        ManifestEmbedder m = mock(ManifestEmbedder.class);
        when(m.modelId()).thenReturn(modelId);
        when(m.dimension()).thenReturn(384);
        return m;
    }

    private static QueryEmbedder queryEmbedder(String modelId) {
        QueryEmbedder q = mock(QueryEmbedder.class);
        when(q.modelId()).thenReturn(modelId);
        when(q.dimension()).thenReturn(384);
        return q;
    }

    /** An existing, well-formed index carrying the current model's stamp. */
    private static void indexExistsWithStamp(JedisPooled jedis, String stampedModel) {
        when(jedis.ftInfo(INDEX)).thenReturn(Map.of("attributes", "sub_domain"));
        when(jedis.get(STAMP)).thenReturn(stampedModel);
    }

    private static void noKeysToScan(JedisPooled jedis) {
        when(jedis.scan(anyString(), any())).thenReturn(new ScanResult<>("0", List.of()));
    }

    @Test
    void anIndexBuiltByADifferentModelIsDroppedAndRebuilt() {
        JedisPooled jedis = mock(JedisPooled.class);
        indexExistsWithStamp(jedis, "hash:sha256-ngram:384");
        noKeysToScan(jedis);

        String current = "remote:all-MiniLM-L6-v2:384";
        new VectorIndex(jedis, manifestEmbedder(current), queryEmbedder(current), new SimpleMeterRegistry())
                .ensureIndex();

        verify(jedis).ftDropIndex(INDEX);
        verify(jedis).ftCreate(eq(INDEX), any(FTCreateParams.class), any(SchemaField.class), any(SchemaField.class), any(SchemaField.class), any(SchemaField.class), any(SchemaField.class));
        verify(jedis).set(STAMP, current);
    }

    @Test
    void anIndexBuiltByTheSameModelIsLeftAlone() {
        JedisPooled jedis = mock(JedisPooled.class);
        String current = "remote:all-MiniLM-L6-v2:384";
        indexExistsWithStamp(jedis, current);

        new VectorIndex(jedis, manifestEmbedder(current), queryEmbedder(current), new SimpleMeterRegistry())
                .ensureIndex();

        verify(jedis, never()).ftDropIndex(anyString());
        verify(jedis, never()).ftCreate(anyString(), any(FTCreateParams.class), any(SchemaField.class), any(SchemaField.class), any(SchemaField.class), any(SchemaField.class), any(SchemaField.class));
    }

    /**
     * A dimension change is a model change: the HNSW field's DIM is fixed at creation, so the index
     * must be rebuilt even though provider and model name are unchanged.
     */
    @Test
    void aDimensionChangeAloneForcesARebuild() {
        JedisPooled jedis = mock(JedisPooled.class);
        indexExistsWithStamp(jedis, "remote:all-MiniLM-L6-v2:384");
        noKeysToScan(jedis);

        String current = "remote:all-MiniLM-L6-v2:768";
        new VectorIndex(jedis, manifestEmbedder(current), queryEmbedder(current), new SimpleMeterRegistry())
                .ensureIndex();

        verify(jedis).ftDropIndex(INDEX);
        verify(jedis).set(STAMP, current);
    }

    /**
     * Dropping the index alone would leave the old vectors under their original keys, to be
     * re-indexed into the new schema — a rebuild that rebuilds nothing.
     */
    @Test
    void rebuildingAlsoDeletesTheStaleVectorDocuments() {
        JedisPooled jedis = mock(JedisPooled.class);
        indexExistsWithStamp(jedis, "hash:sha256-ngram:384");
        when(jedis.scan(anyString(), any()))
                .thenReturn(new ScanResult<>("0", List.of("vec:agent.a:0", "vec:agent.a:1")));

        String current = "remote:all-MiniLM-L6-v2:384";
        new VectorIndex(jedis, manifestEmbedder(current), queryEmbedder(current), new SimpleMeterRegistry())
                .ensureIndex();

        verify(jedis).del("vec:agent.a:0");
        verify(jedis).del("vec:agent.a:1");
    }

    @Test
    void anUnstampedLegacyIndexIsRebuiltRatherThanTrusted() {
        JedisPooled jedis = mock(JedisPooled.class);
        when(jedis.ftInfo(INDEX)).thenReturn(Map.of("attributes", "sub_domain"));
        when(jedis.get(STAMP)).thenReturn(null);
        noKeysToScan(jedis);

        String current = "remote:all-MiniLM-L6-v2:384";
        new VectorIndex(jedis, manifestEmbedder(current), queryEmbedder(current), new SimpleMeterRegistry())
                .ensureIndex();

        verify(jedis).ftDropIndex(INDEX);
        verify(jedis).set(STAMP, current);
    }

    /**
     * The corpus and the query must be embedded by the same model. Wiring that could ever produce
     * two different models is a defect that must not reach the index.
     */
    @Test
    void corpusAndQueryEmbeddersDisagreeingOnTheModelIsFatal() {
        JedisPooled jedis = mock(JedisPooled.class);

        VectorIndex index = new VectorIndex(jedis,
                manifestEmbedder("remote:all-MiniLM-L6-v2:384"),
                queryEmbedder("hash:sha256-ngram:384"),
                new SimpleMeterRegistry());

        assertThatThrownBy(index::ensureIndex)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different vector spaces");
    }
}
