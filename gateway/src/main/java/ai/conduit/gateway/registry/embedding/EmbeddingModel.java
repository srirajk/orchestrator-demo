package ai.conduit.gateway.registry.embedding;

/**
 * The identity of the embedding model in use.
 *
 * <p>Vectors are only comparable when they were produced by the same model at the same
 * dimension. Nothing in this system recorded which model built the routing index, so a change
 * of provider, model, or dimension could not be detected — the index would simply be searched
 * with vectors from a different space, and cosine similarity would return confident nonsense.
 *
 * <p>{@link #id()} is that missing fact. It is stamped on the index when it is built and
 * checked on every boot; a mismatch forces a rebuild rather than a silent degradation.
 */
public interface EmbeddingModel {

    /**
     * A stable identity for this model, of the form {@code provider:model:dimension}.
     * Two embedders with the same id must produce vectors in the same space.
     */
    String id();

    /** Dimension of the vectors this model produces. Fixed for the lifetime of a deployment. */
    int dimension();
}
