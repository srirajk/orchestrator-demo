package ai.conduit.gateway.registry.embedding;

import org.springframework.stereotype.Service;

/**
 * Embeds the user's question, once, on the request path.
 *
 * <p>The counterpart to {@link ManifestEmbedder}. Same model, opposite characteristics: a single
 * text rather than a batch, latency-critical rather than boot-time, and — the reason there is no
 * cache here — almost never a repeat. Two users rarely phrase a question identically, so a query
 * cache would carry the memory and invalidation cost of a hit rate near zero. It would improve a
 * benchmark that replays one prompt and do nothing for the system.
 *
 * <p>Every call is therefore a real embedding call, and this is the hop that sets the routing
 * throughput ceiling. That is a property of where the model runs, not of caching.
 *
 * <p>This class is deliberately thin. It exists so the two workloads are separately visible,
 * separately measurable, and separately optimisable — the query path can move in-process without
 * disturbing the corpus path, and the corpus path can be cached without pretending the query path
 * can be.
 */
@Service
public class QueryEmbedder {

    private final TextEmbedder embedder;

    public QueryEmbedder(TextEmbedder embedder) {
        this.embedder = embedder;
    }

    /** Embed one user query. Never cached — see the class comment. */
    public float[] embed(String queryText) {
        return embedder.embed(queryText);
    }

    /** The identity of the model behind this embedder. Must match the index's stamp. */
    public String modelId() {
        return embedder.id();
    }

    public int dimension() {
        return embedder.dimension();
    }
}
