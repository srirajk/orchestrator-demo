package ai.conduit.gateway.infrastructure.payload;

import ai.conduit.gateway.adapter.PayloadHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 harness test 3 (Tier-A) — THE ONE HASH RULE. The same stamped response, taken through the inline
 * audit-hashing path and through the spill path, yields EQUAL sha256 by construction — because both hash
 * the SAME canonical sorted-keys bytes. This is what makes the audit provenance hash equal the object key.
 */
class HashUniformityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode stamped() {
        // A realistic stamped tree: _verified_sub prepended, then some agent fields (deliberately
        // NOT in sorted order, to prove canonicalisation, not insertion order, drives the hash).
        ObjectNode n = mapper.createObjectNode();
        n.put("_verified_sub", "user-1");
        n.put("zeta", 9);
        n.put("alpha", "a");
        ObjectNode nested = n.putObject("nested");
        nested.put("y", 2);
        nested.put("x", 1);
        return n;
    }

    @Test
    void inlineAuditShaEqualsSpillPathSha() {
        ObjectNode tree = stamped();

        // Spill path: canonical bytes → sha used as the object key.
        byte[] canonical = CanonicalSha.canonicalBytes(tree);
        String spillKeySha = CanonicalSha.sha256Hex(canonical);

        // Inline audit path: the flat view computes the sha lazily on flush.
        PayloadAuditView inlineView = PayloadAuditView.of(new PayloadHandle.Inline(tree));

        // A Ref built from the spill would carry that same sha.
        PayloadHandle.Ref ref = new PayloadHandle.Ref(
                URI.create("s3://bucket/" + spillKeySha), spillKeySha, canonical.length,
                "application/json", PayloadHandle.Provenance.ADAPTER, tree);
        PayloadAuditView refView = PayloadAuditView.of(ref);

        assertThat(inlineView.payloadSha256())
                .as("inline audit sha == spill object key")
                .isEqualTo(spillKeySha);
        assertThat(refView.payloadSha256())
                .as("Ref audit sha == spill object key")
                .isEqualTo(spillKeySha);
        assertThat(inlineView.payloadSize()).isEqualTo(canonical.length);
        assertThat(inlineView.payloadKind()).isEqualTo("inline");
        assertThat(refView.payloadKind()).isEqualTo("ref");
    }

    @Test
    void canonicalisationIsFieldOrderIndependent() {
        ObjectNode a = mapper.createObjectNode();
        a.put("b", 2);
        a.put("a", 1);
        ObjectNode b = mapper.createObjectNode();
        b.put("a", 1);
        b.put("b", 2);
        assertThat(CanonicalSha.hashHex(a))
                .as("two trees differing only in key insertion order hash equal")
                .isEqualTo(CanonicalSha.hashHex(b));
    }
}
