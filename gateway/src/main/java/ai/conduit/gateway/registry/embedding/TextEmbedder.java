package ai.conduit.gateway.registry.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns text into a vector. The single low-level seam over whatever model is configured.
 *
 * <p>Callers do not use this directly. Two collaborators sit on top of it, because embedding the
 * agent corpus and embedding a user's question are different problems that happen to share a
 * model: see {@link ManifestEmbedder} (batch, cached, runs at boot) and {@link QueryEmbedder}
 * (one text, uncached, on the request path).
 */
public interface TextEmbedder extends EmbeddingModel {

    /** Embed a single text. */
    float[] embed(String text);

    /**
     * Embed many texts. The default implementation is a loop; an implementation backed by a
     * service that accepts a batch should override this, because the corpus is embedded as one
     * unit at startup and a per-text round trip is pure latency.
     */
    default List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embed(text));
        }
        return out;
    }
}
