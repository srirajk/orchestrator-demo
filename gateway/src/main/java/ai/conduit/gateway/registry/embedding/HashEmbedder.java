package ai.conduit.gateway.registry.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic 384-dim vectors from SHA-256 hashing of token n-grams. A structural stand-in that
 * needs no model download, useful for tests and offline development.
 *
 * <p><b>This is not a semantic model.</b> It gives consistent vectors for identical text and some
 * cosine similarity for overlapping vocabulary — nothing more. Two questions that mean the same
 * thing in different words are unrelated to it.
 *
 * <p>It must be selected explicitly. It used to carry {@code matchIfMissing = true}, so a
 * deployment that simply forgot {@code CONDUIT_EMBEDDING_PROVIDER} would boot on it and route on
 * hashed n-grams. Nothing failed: the corpus and the query were embedded by the same function, so
 * the vectors were mutually consistent and the index was internally coherent. The system was
 * confidently, silently wrong — the worst failure mode available to it. Absent configuration now
 * yields no {@link TextEmbedder} bean and the context refuses to start.
 */
@Component
@ConditionalOnProperty(name = "conduit.embedding.provider", havingValue = "hash")
public class HashEmbedder implements TextEmbedder {

    private static final int DIM = 384;

    @Override
    public String id() {
        return "hash:sha256-ngram:" + DIM;
    }

    @Override
    public int dimension() {
        return DIM;
    }

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
