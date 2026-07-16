package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.testsupport.ScriptedAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 §a harness test 8 — a DENIED node is excluded from synthesis and reported as missing, never
 * confused with a Cerbos-pruned agent. It codifies the exact invariant the synthesizer keys on:
 * {@code AnswerSynthesizer} only feeds results where {@code isOk() && data() != null}, and a DENIED
 * result carries neither — so it contributes no data yet still flows to results (unlike a pruned agent,
 * which produces no NodeResult at all), where the "state what's missing" path handles it.
 */
class DeniedNodeSynthesisTest {

    @Test
    void deniedResultCarriesNoDataAndIsNotOk() {
        ScriptedAdapter adapter = ScriptedAdapter.fastOk();
        AgentHarness harness = InvokerTestSupport.harness(adapter);
        GovernedInvoker invoker = new GovernedInvoker(new GrantVerifyingAuthorizer(), harness, null);

        PlanNode node = InvokerTestSupport.node("a1");
        NodeResult denied = invoker.invoke(
                InvocationContext.of("p1", "conv", "req", null, List.of()), node, null);   // no grant → DENIED

        assertThat(denied.status()).isEqualTo(NodeResult.Status.DENIED);
        // The synthesizer's contribution predicate (AnswerSynthesizer: isOk() && data()!=null) rejects it.
        assertThat(denied.isOk()).isFalse();
        assertThat(denied.data()).isNull();
        // …but it DID produce a NodeResult (it reaches results and synthesis' missing-data path),
        // which is the distinction from a Cerbos-pruned agent (no NodeResult at all).
        assertThat(denied.isFailure()).isTrue();
        assertThat(denied.isCleanSkip()).isFalse();
        assertThat(denied.agentId()).isEqualTo("a1");
    }
}
