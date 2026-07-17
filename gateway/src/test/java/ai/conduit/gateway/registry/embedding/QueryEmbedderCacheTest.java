package ai.conduit.gateway.registry.embedding;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request-path seam: {@link QueryEmbedder} must behave exactly like the bare {@link TextEmbedder}
 * it wraps (modulo the documented whitespace normalisation), while collapsing repeat queries to a
 * single embed. This pins the wiring that {@code VectorIndex.search} actually calls.
 */
class QueryEmbedderCacheTest {

    private static final class CountingEmbedder implements TextEmbedder {
        final AtomicInteger embedCalls = new AtomicInteger();
        @Override public String id() { return "remote:all-MiniLM-L6-v2:4"; }
        @Override public int dimension() { return 4; }
        @Override public float[] embed(String text) {
            embedCalls.incrementAndGet();
            float h = text.hashCode();
            return new float[]{h, h + 1, h + 2, h + 3};
        }
    }

    @Test
    void embedIsBehaviorPreservingAndCachesRepeats() {
        CountingEmbedder model = new CountingEmbedder();
        QueryEmbeddingCache cache = new QueryEmbeddingCache(new SimpleMeterRegistry(), true, 128);
        QueryEmbedder subject = new QueryEmbedder(model, cache);

        // A reference embedder computes the uncached truth: embedder.embed(normalize(text)).
        CountingEmbedder reference = new CountingEmbedder();

        for (String q : List.of("client exposure report", "client exposure report", "  client   exposure report ")) {
            float[] got = subject.embed(q);
            float[] want = reference.embed(QueryTextNormalizer.normalize(q));
            assertThat(got)
                    .as("QueryEmbedder must return embedder.embed(normalize(text)) for: <%s>", q)
                    .containsExactly(want);
        }

        assertThat(model.embedCalls.get())
                .as("three phrasings of one normalised query cost exactly one embed through the cache")
                .isEqualTo(1);
    }

    @Test
    void modelIdAndDimensionBypassTheCache() {
        CountingEmbedder model = new CountingEmbedder();
        QueryEmbedder subject = new QueryEmbedder(model, new QueryEmbeddingCache(new SimpleMeterRegistry(), true, 128));

        assertThat(subject.modelId()).isEqualTo(model.id());
        assertThat(subject.dimension()).isEqualTo(model.dimension());
        assertThat(model.embedCalls.get())
                .as("identity queries must never trigger an embed")
                .isZero();
    }
}
