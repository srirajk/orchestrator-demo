package ai.conduit.gateway.registry.tenancy;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.embedding.ManifestEmbedder;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.registry.model.AgentManifest.Skill;
import org.mockito.stubbing.Answer;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared fixtures for the Axiom A4 per-tenant registry tests. Every test drives the REAL A4 write
 * side ({@link ai.conduit.gateway.registry.index.VectorIndexWriter}) against a throwaway Redis, with
 * a deterministic stand-in embedder so vectors are stable and the model stamp is fixed.
 */
final class PerTenantTestSupport {

    static final int DIM = 384;
    static final String MODEL = "test-embedder:384";
    static final String LEGACY_INDEX = "intent_idx";

    private PerTenantTestSupport() {}

    static TenantExecutionContext tenant(String id) {
        return TenantExecutionContext.of(id, id, "v1");
    }

    static float[] unit(int idx) {
        float[] v = new float[DIM];
        v[Math.floorMod(idx, DIM)] = 1.0f;
        return v;
    }

    /** A deterministic vector for any text — stable across calls and tenants (statelessness). */
    static float[] deterministic(String text) {
        return unit(Math.floorMod(text.hashCode(), DIM));
    }

    /** A minimal read-only manifest carrying exactly one example prompt so it has a corpus to index. */
    static AgentManifest manifest(String agentId, String domain) {
        Skill skill = new Skill(agentId + ".s1", agentId + " skill", null, null,
                List.of(agentId + " example prompt"), null, null);
        return new AgentManifest(
                agentId, agentId, null, null, null, domain, null, null, null, "http",
                null, null, List.of(skill), new Constraints("read", "internal", 5_000),
                null, null, null, true, null);
    }

    /** A stand-in corpus embedder: pure function of the text, no tenant state (A4 statelessness). */
    static ManifestEmbedder manifestEmbedder() {
        ManifestEmbedder m = mock(ManifestEmbedder.class);
        when(m.modelId()).thenReturn(MODEL);
        when(m.dimension()).thenReturn(DIM);
        Answer<List<float[]>> answer = inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(deterministic(t));
            return out;
        };
        when(m.embedCorpus(anyList())).thenAnswer(answer);
        return m;
    }

    /** A stand-in query embedder with the matching model id, returning e0 for any query. */
    static QueryEmbedder queryEmbedder() {
        return queryEmbedder(MODEL);
    }

    static QueryEmbedder queryEmbedder(String modelId) {
        QueryEmbedder q = mock(QueryEmbedder.class);
        when(q.modelId()).thenReturn(modelId);
        when(q.dimension()).thenReturn(DIM);
        when(q.embed(anyString())).thenReturn(unit(0));
        return q;
    }

    /**
     * A content fingerprint of one tenant's routing index: every {@code t:{tenant}:vec:*} document's
     * key, fields, and raw embedding bytes, hashed in key order. Two identical fingerprints ⇒ the
     * index is byte-unchanged — the assertion that a sibling tenant's broken ingest touched nothing.
     */
    static String indexFingerprint(JedisPooled jedis, TenantKeyspace keyspace, TenantExecutionContext tenant) {
        String prefix = keyspace.key("vec:", tenant);
        Set<String> keys = new TreeSet<>(jedis.keys(prefix + "*"));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String key : keys) {
                md.update(key.getBytes(StandardCharsets.UTF_8));
                for (var e : new TreeSet<>(jedis.hgetAll(key).keySet())) {
                    md.update(e.getBytes(StandardCharsets.UTF_8));
                }
                byte[] emb = jedis.hget(key.getBytes(StandardCharsets.UTF_8), "embedding".getBytes(StandardCharsets.UTF_8));
                if (emb != null) md.update(emb);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
