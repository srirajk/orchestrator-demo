package ai.conduit.gateway.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F4 harness test 1 — the PayloadHandle type contract.
 * <ul>
 *   <li>{@code Inline.json()} returns the SAME reference it was wrapped with (no clone, no re-parse).</li>
 *   <li>{@code Ref} constructor rejects a blank sha256 and a negative size.</li>
 * </ul>
 */
class PayloadHandleContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void inlineJsonIsSameReference() {
        ObjectNode node = mapper.createObjectNode().put("a", 1);
        PayloadHandle.Inline inline = new PayloadHandle.Inline(node);

        assertThat(inline.json()).isSameAs(node);
        assertThat(inline.tree()).isSameAs(node);
        assertThat(inline.provenance()).isEqualTo(PayloadHandle.Provenance.ADAPTER);
        assertThat(inline.parentSha256()).isNull();
    }

    @Test
    void refRejectsBlankSha() {
        assertThatThrownBy(() -> new PayloadHandle.Ref(URI.create("s3://b/x"), "  ", 10, "application/json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256");
        assertThatThrownBy(() -> new PayloadHandle.Ref(URI.create("s3://b/x"), null, 10, "application/json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refRejectsNegativeSize() {
        assertThatThrownBy(() -> new PayloadHandle.Ref(URI.create("s3://b/x"), "abc123", -1, "application/json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes");
    }

    @Test
    void refCarriesProvenanceAndTree() {
        ObjectNode node = mapper.createObjectNode().put("k", "v");
        PayloadHandle.Ref ref = new PayloadHandle.Ref(URI.create("s3://b/abc"), "abc", 3,
                "application/json", PayloadHandle.Provenance.FETCHED, node);
        assertThat(ref.provenance()).isEqualTo(PayloadHandle.Provenance.FETCHED);
        assertThat(ref.tree()).isSameAs(node);
    }
}
