package ai.conduit.gateway.registry.index;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Deterministic 384-dim embedding via SHA-256 hashing of token n-grams.
 *
 * This is a structural stand-in for Phase 3: it produces consistent vectors for
 * identical text and reasonable cosine-similarity for overlapping vocabulary.
 * It is NOT a real semantic model — replace with DJL all-MiniLM-L6-v2 or a
 * hosted endpoint in Phase 4 by providing an alternative EmbeddingClient bean.
 *
 * Why it works well enough for the demo:
 *   - Agents are registered with diverse, domain-specific example prompts.
 *   - Query prompts overlap heavily with those examples (same words).
 *   - The confidence floor filters low-similarity hits.
 *   - The nav agent naturally separates because its prompts share no vocabulary
 *     with relationship-level queries.
 */
@Component
@ConditionalOnProperty(name = "conduit.embedding.provider", havingValue = "hash", matchIfMissing = true)
public class HashEmbeddingClient implements EmbeddingClient {

    private static final int DIM = 384;

    @Override
    public float[] embed(String text) {
        String normalized = normalize(text);
        String[] tokens = normalized.split("\\s+");

        float[] vector = new float[DIM];

        // Each token contributes to the vector at positions derived from its hash.
        // Overlapping tokens across query and example prompts → similar vectors.
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            byte[] hash = sha256(token);

            for (int j = 0; j < DIM; j++) {
                // XOR consecutive hash bytes to spread influence across all 384 dims
                int byteIdx = j % hash.length;
                int nextByteIdx = (j + 1) % hash.length;
                int combined = (hash[byteIdx] ^ hash[nextByteIdx]) & 0xFF;
                // Scale to [-1, 1] with position-dependent sign
                vector[j] += ((combined / 128.0f) - 1.0f) * (((i + j) % 2 == 0) ? 1 : -1);
            }

            // Bigrams — captures two-word phrases (e.g. "pending settlements", "risk profile")
            if (i + 1 < tokens.length) {
                byte[] bigramHash = sha256(token + "_" + tokens[i + 1]);
                for (int j = 0; j < DIM; j++) {
                    int byteIdx = j % bigramHash.length;
                    vector[j] += ((bigramHash[byteIdx] & 0xFF) / 128.0f - 1.0f) * 0.5f;
                }
            }
        }

        return l2Normalize(vector);
    }

    @Override
    public int dimension() {
        return DIM;
    }

    private static String normalize(String text) {
        return text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        if (norm < 1e-9) return v;
        double scale = 1.0 / Math.sqrt(norm);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] * scale);
        return out;
    }
}
