package ai.conduit.gateway.registry.embedding;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query cache's whole job is to be invisible: it must return exactly the vector the sidecar
 * would have, and only skip the call when it provably already has that vector. These tests pin the
 * load-bearing property (behaviour-preserving vs the uncached path) alongside the mechanics that
 * make it a cache rather than a leak (bounded eviction, single-flight, model-stamp keying).
 */
class QueryEmbeddingCacheTest {

    private static final String MODEL = "remote:all-MiniLM-L6-v2:4";

    /** Deterministic 4-dim embedder that counts how many texts actually reached the model. */
    private static final class CountingEmbedder implements TextEmbedder {
        private final String id;
        final AtomicInteger embedCalls = new AtomicInteger();

        CountingEmbedder(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public int dimension() { return 4; }

        @Override
        public float[] embed(String text) {
            embedCalls.incrementAndGet();
            float h = text.hashCode();
            return new float[]{h, h + 1, h + 2, h + 3};
        }
    }

    private QueryEmbeddingCache cache(MeterRegistry registry, boolean enabled, int maxSize) {
        return new QueryEmbeddingCache(registry, enabled, maxSize);
    }

    private double counter(MeterRegistry registry, String result) {
        return registry.get("conduit.embedding.query.cache").tag("result", result).counter().count();
    }

    // ── the load-bearing property ────────────────────────────────────────────

    @Test
    void sameTextHitsCacheNoSecondEmbed() {
        MeterRegistry registry = new SimpleMeterRegistry();
        QueryEmbeddingCache cache = cache(registry, true, 128);
        CountingEmbedder model = new CountingEmbedder(MODEL);

        float[] first = cache.embed(model.id(), "pending settlements for the fund", model::embed);
        float[] second = cache.embed(model.id(), "pending settlements for the fund", model::embed);

        assertThat(model.embedCalls.get())
                .as("the second identical query must not reach the model")
                .isEqualTo(1);
        assertThat(second)
                .as("a hit returns the identical vector a miss would have computed")
                .containsExactly(first);
        assertThat(counter(registry, "miss")).isEqualTo(1.0);
        assertThat(counter(registry, "hit")).isEqualTo(1.0);
    }

    @Test
    void behaviorPreservingVsUncached() {
        // The uncached path is the same cache with memoisation switched off: a pure pass-through.
        CountingEmbedder shared = new CountingEmbedder(MODEL);
        QueryEmbeddingCache enabled = cache(new SimpleMeterRegistry(), true, 128);
        QueryEmbeddingCache disabled = cache(new SimpleMeterRegistry(), false, 128);

        List<String> matrix = List.of(
                "how much is in the reserve account",
                "settlement status today",
                "how much is in the reserve account",   // a repeat: exercises a hit
                "   settlement status today   ",         // whitespace variant of an earlier query
                "risk\tprofile\nsummary",                 // mixed whitespace
                "a completely different question");

        for (String q : matrix) {
            float[] cached = enabled.embed(shared.id(), q, shared::embed);
            float[] uncached = disabled.embed(shared.id(), q, shared::embed);
            assertThat(cached)
                    .as("cache-on and cache-off must return byte-identical vectors for: <%s>", q)
                    .containsExactly(uncached);
        }
    }

    @Test
    void whitespaceVariantsCollapseToOneEmbed() {
        MeterRegistry registry = new SimpleMeterRegistry();
        QueryEmbeddingCache cache = cache(registry, true, 128);
        CountingEmbedder model = new CountingEmbedder(MODEL);

        float[] a = cache.embed(model.id(), "settlement status today", model::embed);
        float[] b = cache.embed(model.id(), "  settlement   status\ttoday\n", model::embed);

        assertThat(model.embedCalls.get())
                .as("trim + whitespace-collapse make these the same query, so it embeds once")
                .isEqualTo(1);
        assertThat(b).containsExactly(a);
    }

    @Test
    void caseIsPreservedAndNotFolded() {
        MeterRegistry registry = new SimpleMeterRegistry();
        QueryEmbeddingCache cache = cache(registry, true, 128);
        CountingEmbedder model = new CountingEmbedder(MODEL);

        cache.embed(model.id(), "Apple treasury position", model::embed);
        cache.embed(model.id(), "apple treasury position", model::embed);

        assertThat(model.embedCalls.get())
                .as("case can carry routing meaning; it is deliberately NOT normalised, so these are two queries")
                .isEqualTo(2);
    }

    // ── model-stamp safety ───────────────────────────────────────────────────

    @Test
    void modelStampChangeInvalidates() {
        MeterRegistry registry = new SimpleMeterRegistry();
        QueryEmbeddingCache cache = cache(registry, true, 128);
        AtomicInteger computes = new AtomicInteger();
        Function<String, float[]> compute = text -> {
            computes.incrementAndGet();
            return new float[]{1, 2, 3, 4};
        };

        cache.embed("remote:all-MiniLM-L6-v2:4", "same question", compute);
        cache.embed("remote:some-other-model:4", "same question", compute);

        assertThat(computes.get())
                .as("a different model id is a different key — no vector from the old space is served")
                .isEqualTo(2);
    }

    // ── bounded, no unbounded growth ─────────────────────────────────────────

    @Test
    void boundedEvictionNoUnboundedGrowth() {
        MeterRegistry registry = new SimpleMeterRegistry();
        QueryEmbeddingCache cache = cache(registry, true, 2);
        CountingEmbedder model = new CountingEmbedder(MODEL);

        cache.embed(model.id(), "q1", model::embed);
        cache.embed(model.id(), "q2", model::embed);
        cache.embed(model.id(), "q3", model::embed);   // evicts q1 (LRU eldest)

        assertThat(cache.size())
                .as("the cache never exceeds its configured max size")
                .isEqualTo(2);

        cache.embed(model.id(), "q1", model::embed);   // evicted → recomputed, not stale

        assertThat(model.embedCalls.get())
                .as("q1 was evicted, so it costs a fresh embed rather than a phantom hit")
                .isEqualTo(4);
        assertThat(cache.size()).isEqualTo(2);
    }

    // ── single-flight ────────────────────────────────────────────────────────

    @Test
    void concurrentSameQueryComputesOnce() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        QueryEmbeddingCache cache = cache(registry, true, 128);

        AtomicInteger computes = new AtomicInteger();
        CountDownLatch inCompute = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Function<String, float[]> slowCompute = text -> {
            computes.incrementAndGet();
            inCompute.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new float[]{9, 8, 7, 6};
        };

        int n = 8;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // Thread 0 becomes the single computer and parks inside compute, holding the in-flight future.
            CompletableFuture<float[]> first =
                    CompletableFuture.supplyAsync(() -> cache.embed(MODEL, "hot query", slowCompute), pool);
            inCompute.await();

            // Everyone else arrives while the first is still computing: they must dedup onto its future.
            List<CompletableFuture<float[]>> rest = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                rest.add(CompletableFuture.supplyAsync(() -> cache.embed(MODEL, "hot query", slowCompute), pool));
            }
            // Give the waiters a moment to reach the in-flight join before we let the computer finish.
            Thread.sleep(50);
            release.countDown();

            float[] firstVec = first.get();
            for (CompletableFuture<float[]> f : rest) {
                assertThat(f.get())
                        .as("every concurrent caller gets the one computed vector")
                        .containsExactly(firstVec);
            }
        }

        assertThat(computes.get())
                .as("N concurrent identical queries must trigger exactly one embed (no thundering herd)")
                .isEqualTo(1);
        assertThat(counter(registry, "miss")).isEqualTo(1.0);
        assertThat(counter(registry, "hit"))
                .as("the other callers are served without their own embed")
                .isEqualTo((double) (n - 1));
    }

    // ── disabled is a pass-through ───────────────────────────────────────────

    @Test
    void disabledCacheAlwaysEmbeds() {
        MeterRegistry registry = new SimpleMeterRegistry();
        QueryEmbeddingCache cache = cache(registry, false, 128);
        CountingEmbedder model = new CountingEmbedder(MODEL);

        cache.embed(model.id(), "same question", model::embed);
        cache.embed(model.id(), "same question", model::embed);

        assertThat(model.embedCalls.get())
                .as("with the cache off, every call reaches the model")
                .isEqualTo(2);
    }
}
