package ai.conduit.gateway.infrastructure.payload;

import ai.conduit.gateway.adapter.PayloadHandle;
import ai.conduit.gateway.orchestration.executor.Blackboard;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 harness test 10 (Tier-A) — coverage cannot be bypassed via the spill path.
 *
 * <p>The scenario a spilled produced-entity result creates: the pre-filter payload is a content-addressed
 * {@link PayloadHandle.Ref} (its full, un-filtered tree lives in the object store). When
 * {@code DagPlanExecutor.applyProducedEntityCoverage} filters a coverage-denied entity out, it rebuilds the
 * NodeResult with a DERIVED {@code Inline} over the FILTERED tree — DROPPING the Ref. This test asserts the
 * three invariants that make that safe:
 * <ol>
 *   <li>downstream projection ({@link Blackboard#project}/{@link Blackboard#bind}) sees the FILTERED tree only;</li>
 *   <li>the audit view is {@code derived} with {@code parent_sha256} = the pre-filter Ref sha (lineage), and no uri;</li>
 *   <li>the pre-filter Ref uri appears NOWHERE downstream — the denied entity cannot flow around CHECK.</li>
 * </ol>
 * This mirrors the exact rebuild the executor performs (same construction), so a regression that forwards
 * the spilled Ref downstream, or forgets the derived/parent lineage, fails here.
 */
class CoverageFilterDerivedHandleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentManifest agent(String id, AgentManifest.Io io) {
        return new AgentManifest(id, id, null, null, null, null, null, null, null, "http",
                null, null, null, null, io, null, null, null, true, null);
    }

    @Test
    void deniedEntityCannotEscapeViaSpilledRef() {
        // The FULL (pre-filter) tree — two produced entities, one of which coverage will deny.
        ObjectNode full = MAPPER.createObjectNode();
        var holdings = full.putArray("holdings");
        holdings.addObject().put("id", "ALLOWED-1");
        holdings.addObject().put("id", "DENIED-9");

        // It was large enough to spill: the pre-filter payload is a Ref in the object store.
        byte[] canonical = CanonicalSha.canonicalBytes(full);
        String parentSha = CanonicalSha.sha256Hex(canonical);
        URI spilledUri = URI.create("s3://conduit-payload/" + parentSha);
        PayloadHandle.Ref preFilterRef = new PayloadHandle.Ref(spilledUri, parentSha, canonical.length,
                "application/json", PayloadHandle.Provenance.ADAPTER, full);
        NodeResult preFilter = new NodeResult("p", "agent-p", "http", NodeResult.Status.OK,
                full, 5L, null, preFilterRef);

        // The filtered tree (DENIED-9 removed) — a DIFFERENT reference than the pre-filter tree.
        ObjectNode filtered = MAPPER.createObjectNode();
        filtered.putArray("holdings").addObject().put("id", "ALLOWED-1");

        // The exact rebuild DagPlanExecutor.applyProducedEntityCoverage performs when it filters.
        String parentShaFromHandle = (preFilter.payload() instanceof PayloadHandle.Ref r) ? r.sha256() : null;
        PayloadHandle derived = new PayloadHandle.Inline(filtered, PayloadHandle.Provenance.DERIVED, parentShaFromHandle);
        NodeResult postFilter = new NodeResult("p", "agent-p", "http", NodeResult.Status.OK,
                filtered, 5L, null, derived);

        // (1) downstream projection sees the FILTERED tree only.
        AgentManifest.Io producerIo = new AgentManifest.Io(null, List.of(new AgentManifest.Produce("out", "T")));
        AgentManifest.Io consumerIo = new AgentManifest.Io(
                List.of(new AgentManifest.Consume(null, "T", true)), null);
        PlanNode producer = new PlanNode("p", agent("p", producerIo), null, List.of());
        PlanNode consumer = new PlanNode("c", agent("c", consumerIo), null, List.of());

        Blackboard board = new Blackboard(Set.of(), Map.of(), MAPPER);
        board.project(producer, postFilter.data());   // executor projects r.data() for OK nodes
        JsonNode downstream = board.bind(consumer);

        assertThat(downstream).isSameAs(filtered);
        assertThat(downstream.toString())
                .as("the denied entity does not flow downstream")
                .doesNotContain("DENIED-9")
                .contains("ALLOWED-1");

        // (3) the pre-filter Ref uri appears NOWHERE downstream.
        assertThat(downstream.toString()).doesNotContain("s3://");
        assertThat(postFilter.payload()).isInstanceOf(PayloadHandle.Inline.class);

        // (2) the audit view is derived + parent_sha256 lineage, and carries no uri.
        PayloadAuditView view = PayloadAuditView.of(postFilter.payload());
        assertThat(view.provenance()).isEqualTo("derived");
        assertThat(view.parentSha256()).isEqualTo(parentSha);
        assertThat(view.payloadUri()).isNull();
        assertThat(view.payloadSha256())
                .as("the audit hash covers what actually grounded the answer (the filtered tree), not the pre-filter blob")
                .isEqualTo(CanonicalSha.hashHex(filtered))
                .isNotEqualTo(parentSha);
    }
}
