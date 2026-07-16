package ai.conduit.gateway.orchestration.harness;

import ai.conduit.gateway.adapter.PayloadHandle;
import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.infrastructure.expression.CompiledExpr;
import ai.conduit.gateway.infrastructure.payload.MaterializeContext;
import ai.conduit.gateway.infrastructure.payload.PayloadSpiller;
import ai.conduit.gateway.infrastructure.payload.PayloadStore;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.testsupport.HarnessTestSupport;
import ai.conduit.gateway.testsupport.PayloadTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 harness test 9 — the spill runs INSIDE the harness's submitted callable, so a slow {@code store.put}
 * is bounded by the node SLA: {@code future.get(slaMs)} cuts it and the node is TIMEOUT (not a runaway
 * that ignores the deadline). A slow store therefore fails the node within its budget, never past it.
 */
class SpillSlaPlacementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A store whose put() parks far longer than the node SLA — models a stalled object store. */
    private static final class SlowStore implements PayloadStore {
        @Override public PayloadHandle.Ref put(byte[] b, String sha, String mt,
                                               PayloadHandle.Provenance p, JsonNode tree) throws Exception {
            Thread.sleep(3_000);   // >> SLA
            return new PayloadHandle.Ref(URI.create("s3://b/" + sha), sha, b.length, mt, p, tree);
        }
        @Override public JsonNode materialize(PayloadHandle.Ref ref, CompiledExpr sel, MaterializeContext ctx) {
            throw new UnsupportedOperationException();
        }
    }

    /** An adapter whose invokeHandle spills (threshold 0 → always) through the slow store. */
    private static final class SpillingAdapter implements ProtocolAdapter {
        private final PayloadSpiller spiller = PayloadTestSupport.spiller(new SlowStore(), 0L);
        @Override public String protocol() { return "http"; }
        @Override public JsonNode invoke(AgentManifest m, JsonNode in, String tok) {
            return MAPPER.createObjectNode().put("figure", 1);
        }
        @Override public PayloadHandle invokeHandle(AgentManifest m, JsonNode in, String tok) throws Exception {
            return spiller.handleFor(invoke(m, in, tok), PayloadHandle.Provenance.ADAPTER);
        }
    }

    @Test
    void slowSpillIsCutByTheNodeSla() {
        int slaMs = 300;
        AgentHarness harness = HarnessTestSupport.harness(new SpillingAdapter(), slaMs, 5, 20);
        PlanNode node = HarnessTestSupport.node("a", "http", slaMs, MAPPER.createObjectNode());

        long t0 = System.nanoTime();
        NodeResult r = harness.execute(node, null, "tok");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(r.status())
                .as("the spill runs inside the submitted callable, so the SLA cuts the node")
                .isEqualTo(NodeResult.Status.TIMEOUT);
        assertThat(elapsedMs)
                .as("cut within SLA + slack, NOT the 3s store.put")
                .isLessThan(slaMs + 700);
    }
}
