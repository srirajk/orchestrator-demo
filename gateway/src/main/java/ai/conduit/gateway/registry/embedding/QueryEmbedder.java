package ai.conduit.gateway.registry.embedding;

import org.springframework.stereotype.Service;

/**
 * Embeds the user's question, once, on the request path.
 *
 * <p>The counterpart to {@link ManifestEmbedder}. Same model, opposite characteristics: a single
 * text rather than a batch, and latency-critical rather than boot-time. This is the hop that sets
 * the routing throughput ceiling ({@code docs/archive/perf/RESULTS.md}: throughput saturates well
 * below CPU, bottlenecked on the sidecar's per-request {@code SentenceTransformer.encode}).
 *
 * <p>It once carried no cache, on the reasoning that two users rarely phrase a question identically.
 * That is true of distinct users typing freely, but it is not true of the workloads that actually
 * push the ceiling: a demo replaying a script, a load test, a queue of near-duplicate questions that
 * collapse to a handful of phrasings once whitespace is normalised. For those, re-embedding the same
 * text on every request is pure waste — the same text always yields the same vector. So the embed is
 * now memoised through {@link QueryEmbeddingCache}: a repeat query skips the sidecar entirely, and a
 * novel one costs exactly what it did before.
 *
 * <p>The memoisation is behaviour-preserving by construction. The cache is a pure function of
 * {@code (model-id, normalised-text)}; a hit returns the identical vector a miss would have computed,
 * so routing is byte-for-byte unchanged. Turning the cache off
 * ({@code conduit.embedding.query.cache.enabled=false}) changes throughput, never the answer.
 *
 * <p>{@link #modelId()} and {@link #dimension()} deliberately bypass the cache: they are identity,
 * not embedding, and the registry readiness/stamp machinery depends on them reflecting the live
 * model.
 */
@Service
public class QueryEmbedder {

    private final TextEmbedder embedder;
    private final QueryEmbeddingCache cache;

    public QueryEmbedder(TextEmbedder embedder, QueryEmbeddingCache cache) {
        this.embedder = embedder;
        this.cache = cache;
    }

    /**
     * Embed one user query, memoised by {@link QueryEmbeddingCache}. A cache hit returns the identical
     * vector the model would have produced for the normalised text; a miss embeds and stores it.
     */
    public float[] embed(String queryText) {
        return cache.embed(embedder.id(), queryText, embedder::embed);
    }

    /** The identity of the model behind this embedder. Must match the index's stamp. */
    public String modelId() {
        return embedder.id();
    }

    public int dimension() {
        return embedder.dimension();
    }
}
