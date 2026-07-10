package ai.conduit.gateway.registry.embedding;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The corpus embedder's contract: identical text under an identical model is embedded once, ever;
 * a change to either is a different vector and therefore a different cache entry.
 */
class ManifestEmbedderTest {

    private JedisPooled jedis;
    private Map<String, byte[]> store;

    /** Counts how many texts actually reached the model. */
    private static final class CountingEmbedder implements TextEmbedder {
        private final String id;
        int textsEmbedded = 0;
        int batchCalls = 0;

        CountingEmbedder(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public int dimension() { return 4; }

        @Override
        public float[] embed(String text) {
            textsEmbedded++;
            float h = text.hashCode() % 100;
            return new float[]{h, h + 1, h + 2, h + 3};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            batchCalls++;
            List<float[]> out = new ArrayList<>();
            for (String t : texts) out.add(embed(t));
            return out;
        }
    }

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        jedis = mock(JedisPooled.class);
        when(jedis.get(any(byte[].class)))
                .thenAnswer(inv -> store.get(new String(inv.getArgument(0), StandardCharsets.UTF_8)));
        doAnswer(inv -> {
            store.put(new String(inv.getArgument(0, byte[].class), StandardCharsets.UTF_8),
                    inv.getArgument(2, byte[].class));
            return "OK";
        }).when(jedis).setex(any(byte[].class), anyLong(), any(byte[].class));
    }

    private ManifestEmbedder embedder(TextEmbedder delegate, boolean cacheEnabled) {
        return new ManifestEmbedder(delegate, jedis, new SimpleMeterRegistry(), cacheEnabled, 60);
    }

    @Test
    void aCorpusIsEmbeddedOnceAndServedFromCacheOnEveryLaterBoot() {
        CountingEmbedder model = new CountingEmbedder("remote:test:4");
        List<String> corpus = List.of("pending settlements", "risk profile", "ytd performance");

        List<float[]> first = embedder(model, true).embedCorpus(corpus);
        assertThat(model.textsEmbedded).as("cold: every text reaches the model").isEqualTo(3);

        // A restart: a fresh embedder over the same Redis contents.
        CountingEmbedder afterRestart = new CountingEmbedder("remote:test:4");
        List<float[]> second = embedder(afterRestart, true).embedCorpus(corpus);

        assertThat(afterRestart.textsEmbedded)
                .as("warm: nothing reaches the model, so boot no longer needs the embedding service")
                .isZero();
        for (int i = 0; i < corpus.size(); i++) {
            assertThat(second.get(i)).isEqualTo(first.get(i));
        }
    }

    @Test
    void onlyTheChangedExampleIsRecomputedWhenAManifestIsEdited() {
        embedder(new CountingEmbedder("remote:test:4"), true)
                .embedCorpus(List.of("pending settlements", "risk profile"));

        CountingEmbedder model = new CountingEmbedder("remote:test:4");
        embedder(model, true).embedCorpus(List.of("pending settlements", "risk profile EDITED"));

        assertThat(model.textsEmbedded)
                .as("the untouched example is content-addressed and still cached")
                .isEqualTo(1);
    }

    /**
     * The property that makes the cache safe: a vector is never served to a model that did not
     * produce it. Without the model id in the key, a provider swap would silently reuse vectors
     * from the previous vector space.
     */
    @Test
    void changingTheModelInvalidatesEveryCachedVector() {
        List<String> corpus = List.of("pending settlements", "risk profile");
        embedder(new CountingEmbedder("remote:all-MiniLM-L6-v2:4"), true).embedCorpus(corpus);

        CountingEmbedder differentModel = new CountingEmbedder("remote:some-other-model:4");
        embedder(differentModel, true).embedCorpus(corpus);

        assertThat(differentModel.textsEmbedded)
                .as("a different model must not read the previous model's vectors")
                .isEqualTo(2);
    }

    @Test
    void theCorpusReachesTheModelInASingleBatchRatherThanOneCallPerExample() {
        CountingEmbedder model = new CountingEmbedder("remote:test:4");
        embedder(model, true).embedCorpus(List.of("a", "b", "c", "d", "e"));

        assertThat(model.batchCalls)
                .as("five examples must cost one round trip, not five")
                .isEqualTo(1);
    }

    @Test
    void vectorsSurviveTheCacheRoundTripExactly() {
        CountingEmbedder model = new CountingEmbedder("remote:test:4");
        List<String> corpus = List.of("settlement status");

        float[] computed = embedder(model, true).embedCorpus(corpus).get(0);
        float[] fromCache = embedder(new CountingEmbedder("remote:test:4"), true).embedCorpus(corpus).get(0);

        assertThat(fromCache).containsExactly(computed);
    }

    @Test
    void disablingTheCacheAlwaysReachesTheModel() {
        CountingEmbedder model = new CountingEmbedder("remote:test:4");
        List<String> corpus = List.of("a", "b");

        embedder(model, false).embedCorpus(corpus);
        embedder(model, false).embedCorpus(corpus);

        assertThat(model.textsEmbedded).isEqualTo(4);
    }
}
