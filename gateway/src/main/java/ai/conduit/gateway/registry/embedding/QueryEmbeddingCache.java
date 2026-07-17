package ai.conduit.gateway.registry.embedding;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A behaviour-preserving memoisation of the query embedding hop — the fix for the routing-throughput
 * ceiling measured in {@code docs/archive/perf/RESULTS.md}.
 *
 * <p>The measured bottleneck is not CPU in the gateway; it is that every request re-embeds its query
 * on the sidecar's CPU-bound {@code SentenceTransformer.encode}, and that hop sits on the routing
 * path of every request. But a text-to-vector function is a pure function: the same text under the
 * same model always yields the same vector. So the second and later occurrences of a query need not
 * pay the embed at all. Under any workload where questions repeat — a demo replaying prompts, a load
 * test, a support queue asking the same thing a hundred ways that collapse to a handful of phrasings
 * — this removes the embed call from all but the first occurrence.
 *
 * <h2>Why it cannot change a routing decision</h2>
 * The cache is a <em>memoiser</em> of {@code compute(normalize(text))}, nothing more. A miss computes
 * exactly what the uncached path would compute and stores it; a hit returns that identical vector.
 * Normalisation ({@link QueryTextNormalizer}) is applied on the query path whether or not the cache
 * is enabled, so enabling the cache changes latency, never the vector — and identical query vectors
 * feed an identical KNN search, so routing is byte-for-byte the same. Disabling the cache
 * ({@code conduit.embedding.query.cache.enabled=false}) is a pure pass-through.
 *
 * <h2>Model-stamp safety</h2>
 * The cache key is {@code modelId + '\n' + normalize(text)}. The model id is
 * {@code provider:model:dimension} (see {@link EmbeddingModel#id()}), so a change of provider, model
 * or dimension changes every key and can never serve a vector from a different vector space. This
 * mirrors {@link ManifestEmbedder}'s content-addressing and is belt-and-braces with the boot-time
 * model stamp the gateway already refuses to start on. The separator is a newline, which normalize()
 * can never leave in the text (it collapses all whitespace) and which no model id contains, so no
 * {@code (modelId, text)} pair can ever alias another.
 *
 * <h2>Tenant-agnostic by construction</h2>
 * The key is (model, text) — it does not, and structurally cannot, depend on the principal, their
 * book, or a tenant. Text-to-vector has no such input. That is what makes the cache a safe
 * <em>shared</em> cache across all callers, and forward-compatible with multi-tenant identity: two
 * tenants asking the same question get the same vector, because they always would.
 *
 * <h2>Store choice — in-JVM bounded LRU, not Redis</h2>
 * Deliberately in-process. A Redis-backed query cache would replace the embedding-sidecar network hop
 * with a Redis network hop — still a per-request round trip, which is exactly what the throughput fix
 * is trying to remove. An in-JVM hash lookup is sub-microsecond and adds no I/O to the request path.
 * The trade-offs — per-instance and cold on restart — are acceptable precisely because the cache is
 * never load-bearing for correctness: a miss simply embeds, and a warm workload re-warms in seconds.
 * ({@link ManifestEmbedder} makes the opposite choice because it runs at ingestion, off the request
 * path, where shared cross-restart reuse is the whole point.) The map is bounded (LRU eviction at
 * {@code max-size}) so an adversarial stream of unique queries cannot grow it without bound.
 *
 * <h2>Single-flight</h2>
 * N concurrent identical queries collapse to one embed: the first installs an in-flight future, the
 * rest await it. No thundering herd on the sidecar for a cold-but-popular query.
 */
@Service
public class QueryEmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(QueryEmbeddingCache.class);

    /**
     * Delimits the model id from the query text in a cache key. A newline can never appear in either
     * side — {@link QueryTextNormalizer} collapses all whitespace, and a model id has none — so it
     * separates the two unambiguously, and no {@code (modelId, text)} pair can collide with another.
     */
    private static final char KEY_SEP = '\n';

    private final boolean enabled;
    private final int maxSize;

    /** Access-order LRU, guarded by its own monitor. Reads are O(1); the request path never blocks on I/O here. */
    private final Map<String, float[]> store;

    /** In-flight computations, so identical concurrent misses embed once. */
    private final ConcurrentHashMap<String, CompletableFuture<float[]>> inFlight = new ConcurrentHashMap<>();

    private final Counter hits;
    private final Counter misses;

    public QueryEmbeddingCache(MeterRegistry meterRegistry,
                               @Value("${conduit.embedding.query.cache.enabled:true}") boolean enabled,
                               @Value("${conduit.embedding.query.cache.max-size:4096}") int maxSize) {
        this.enabled = enabled;
        this.maxSize = maxSize;
        this.store = boundedLru(maxSize);
        this.hits = Counter.builder("conduit.embedding.query.cache")
                .tag("result", "hit")
                .description("Query embeddings served from the in-JVM cache (no sidecar embed call).")
                .register(meterRegistry);
        this.misses = Counter.builder("conduit.embedding.query.cache")
                .tag("result", "miss")
                .description("Query embeddings that had to be computed by the embedding model.")
                .register(meterRegistry);
        log.info("QueryEmbeddingCache {} (max-size={})", enabled ? "enabled" : "disabled", maxSize);
    }

    /**
     * Return the embedding of {@code rawText} under {@code modelId}, computing it via {@code compute}
     * (which receives the <em>normalised</em> text) only on a miss.
     *
     * <p>Behaviour-preserving contract: the returned vector is always
     * {@code compute.apply(normalize(rawText))} for that model — identical whether the cache is on or
     * off, whether the call hits or misses.
     */
    public float[] embed(String modelId, String rawText, Function<String, float[]> compute) {
        String normalized = QueryTextNormalizer.normalize(rawText);
        if (!enabled) {
            return compute.apply(normalized);
        }

        String key = modelId + KEY_SEP + normalized;

        float[] cached = lookup(key);
        if (cached != null) {
            hits.increment();
            return cached;
        }

        // Single-flight: only one caller computes a given key at a time; the rest await its future.
        CompletableFuture<float[]> mine = new CompletableFuture<>();
        CompletableFuture<float[]> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            // Another thread is already embedding this exact query — no second sidecar call.
            hits.increment();
            return existing.join();
        }

        try {
            // A racing thread may have finished and stored between our lookup and our claim.
            float[] raced = lookup(key);
            if (raced != null) {
                hits.increment();
                mine.complete(raced);
                return raced;
            }
            misses.increment();
            float[] vec = compute.apply(normalized);
            put(key, vec);
            mine.complete(vec);
            return vec;
        } catch (RuntimeException e) {
            // Never cache a failure. Fail this call and every waiter, then let the next attempt retry.
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private float[] lookup(String key) {
        synchronized (store) {
            return store.get(key);
        }
    }

    private void put(String key, float[] vec) {
        synchronized (store) {
            store.put(key, vec);
        }
    }

    /** Current number of cached vectors. For tests and diagnostics. */
    int size() {
        synchronized (store) {
            return store.size();
        }
    }

    private static Map<String, float[]> boundedLru(int maxSize) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > maxSize;
            }
        };
    }
}
