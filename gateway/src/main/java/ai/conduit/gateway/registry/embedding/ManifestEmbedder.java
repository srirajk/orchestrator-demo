package ai.conduit.gateway.registry.embedding;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Embeds the agent corpus — the skill example prompts that make up the routing index.
 *
 * <p>This half of the embedding workload has properties the request path does not: it runs once at
 * boot, it is a batch, and it is <em>deterministic given the manifest</em>. The same example text
 * under the same model yields the same vector on every restart, forever. Re-deriving it each boot
 * spent a round trip per example and made startup depend on the embedding service being reachable
 * even when nothing had changed.
 *
 * <p>So vectors are content-addressed: the cache key is the model identity plus a digest of the
 * text. A manifest edit changes the text and therefore the key; a model change changes the id and
 * therefore every key. Nothing is ever served from a different vector space than it was written in.
 *
 * <p>This is a <em>corpus</em> cache, deliberately not a query cache. The corpus is a fixed set of
 * strings read repeatedly; a user's question is a fresh string almost every time, so caching the
 * request path would optimise a benchmark rather than the system. See {@link QueryEmbedder}.
 */
@Service
@Profile("registry")
public class ManifestEmbedder {

    private static final Logger log = LoggerFactory.getLogger(ManifestEmbedder.class);
    private static final String KEY_PREFIX = "emb:corpus:";

    private final TextEmbedder embedder;
    private final JedisPooled jedis;
    private final boolean cacheEnabled;
    private final long cacheTtlSeconds;
    private final Counter hits;
    private final Counter misses;

    public ManifestEmbedder(TextEmbedder embedder,
                            JedisPooled jedis,
                            MeterRegistry meterRegistry,
                            @Value("${conduit.embedding.cache.enabled:true}") boolean cacheEnabled,
                            @Value("${conduit.embedding.cache.ttl-seconds:2592000}") long cacheTtlSeconds) {
        this.embedder = embedder;
        this.jedis = jedis;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.hits = Counter.builder("conduit.embedding.corpus.cache")
                .tag("result", "hit")
                .description("Corpus embeddings served from the content-addressed cache.")
                .register(meterRegistry);
        this.misses = Counter.builder("conduit.embedding.corpus.cache")
                .tag("result", "miss")
                .description("Corpus embeddings that had to be computed by the embedding model.")
                .register(meterRegistry);
    }

    /** The identity of the model behind this embedder. Stamped on the index that its vectors build. */
    public String modelId() {
        return embedder.id();
    }

    public int dimension() {
        return embedder.dimension();
    }

    /**
     * Embed a corpus, returning one vector per input in the same order. Cached texts are read from
     * Redis; the remainder go to the model in a single batch.
     */
    public List<float[]> embedCorpus(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        if (!cacheEnabled) {
            return embedder.embedBatch(texts);
        }

        float[][] resolved = new float[texts.size()][];
        List<String> missingTexts = new ArrayList<>();
        List<Integer> missingSlots = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            float[] cached = readCached(texts.get(i));
            if (cached != null) {
                resolved[i] = cached;
                hits.increment();
            } else {
                missingTexts.add(texts.get(i));
                missingSlots.add(i);
                misses.increment();
            }
        }

        if (!missingTexts.isEmpty()) {
            List<float[]> computed = embedder.embedBatch(missingTexts);
            for (int i = 0; i < computed.size(); i++) {
                float[] vec = computed.get(i);
                resolved[missingSlots.get(i)] = vec;
                writeCached(missingTexts.get(i), vec);
            }
        }

        log.debug("Corpus embed: {} texts, {} cached, {} computed",
                texts.size(), texts.size() - missingTexts.size(), missingTexts.size());
        return List.of(resolved);
    }

    private float[] readCached(String text) {
        try {
            byte[] raw = jedis.get(cacheKey(text));
            if (raw == null || raw.length != dimension() * Float.BYTES) {
                return null;
            }
            return bytesToFloats(raw);
        } catch (Exception e) {
            // A cache is an optimisation. If Redis is unhappy, compute the vector.
            log.debug("Corpus cache read failed, falling through to the model: {}", e.getMessage());
            return null;
        }
    }

    private void writeCached(String text, float[] vec) {
        try {
            jedis.setex(cacheKey(text), cacheTtlSeconds, floatsToBytes(vec));
        } catch (Exception e) {
            log.debug("Corpus cache write failed, continuing: {}", e.getMessage());
        }
    }

    /**
     * {@code emb:corpus:{modelId}:{sha256(text)}} — the model identity is inside the key, so
     * vectors from different models can never collide, and a model swap simply misses.
     */
    private byte[] cacheKey(String text) {
        return (KEY_PREFIX + embedder.id() + ":" + sha256Hex(text)).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] floatsToBytes(float[] floats) {
        ByteBuffer bb = ByteBuffer.allocate(floats.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) bb.putFloat(f);
        return bb.array();
    }

    private static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < out.length; i++) out[i] = bb.getFloat();
        return out;
    }
}
