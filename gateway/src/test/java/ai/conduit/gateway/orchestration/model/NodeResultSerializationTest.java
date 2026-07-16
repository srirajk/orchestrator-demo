package ai.conduit.gateway.orchestration.model;

import ai.conduit.gateway.adapter.PayloadHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 harness test 11 — the sealed PayloadHandle NEVER leaks through the trace/insight serializers.
 * Serializing a Ref-bearing NodeResult produces no {@code payload} field and no object-store URI/sha;
 * a round-trip deserialization still succeeds (payload is transient — {@code @JsonIgnore}).
 */
class NodeResultSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refPayloadNeverSerializesAndRoundTrips() throws Exception {
        ObjectNode data = mapper.createObjectNode().put("figure", 42);
        String leakUri = "s3://conduit-payload/deadbeefsha";
        String leakSha = "deadbeefsha";
        PayloadHandle.Ref ref = new PayloadHandle.Ref(URI.create(leakUri), leakSha, 128,
                "application/json", PayloadHandle.Provenance.ADAPTER, data);

        NodeResult result = new NodeResult("n1", "agent-1", "http", NodeResult.Status.OK,
                data, 12L, null, ref);

        String json = mapper.writeValueAsString(result);

        assertThat(json)
                .as("no handle field, no object-store URI/sha leaks into the serialized NodeResult")
                .doesNotContain("payload")
                .doesNotContain(leakUri)
                .doesNotContain(leakSha)
                .doesNotContain("s3://");
        // The data tree itself is still serialized (the answer's ground truth), unchanged.
        assertThat(json).contains("\"figure\":42");

        NodeResult roundTrip = mapper.readValue(json, NodeResult.class);
        assertThat(roundTrip.nodeId()).isEqualTo("n1");
        assertThat(roundTrip.status()).isEqualTo(NodeResult.Status.OK);
        assertThat(roundTrip.data().path("figure").asInt()).isEqualTo(42);
        assertThat(roundTrip.payload()).as("handle is transient across serialization").isNull();
    }
}
