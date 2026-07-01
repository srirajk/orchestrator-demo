package ai.conduit.gateway.registry.index;

/**
 * Seam for the embedding model.
 * Phase 3: hash-based 384-dim vectors (no model download needed, good enough for routing demo).
 * Phase 4+: swap to DJL all-MiniLM-L6-v2 or a hosted endpoint — callers don't change.
 */
public interface EmbeddingClient {

    /**
     * Embed a single text string into a float vector.
     * The vector dimension must be fixed and consistent for all calls within one deployment.
     */
    float[] embed(String text);

    /** Dimension of the vectors this client produces. */
    int dimension();
}
