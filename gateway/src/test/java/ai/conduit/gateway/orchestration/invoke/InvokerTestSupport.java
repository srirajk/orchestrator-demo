package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.testsupport.ScriptedAdapter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;

/** Shared, domain-free builders for the {@link GovernedInvoker} unit battery (no docker, no Spring). */
final class InvokerTestSupport {

    private InvokerTestSupport() {}

    static AgentManifest agent(String id) {
        return agent(id, 5_000);
    }

    static AgentManifest agent(String id, int slaMs) {
        return new AgentManifest(
                id, id, null, null, null, null, null, null, null, "http",
                null, null, null, new Constraints("read", "internal", slaMs),
                null, null, null, null, true, null);
    }

    static PlanNode node(String id) {
        return new PlanNode(id, agent(id), null, List.of());
    }

    static PlanNode node(String id, int slaMs) {
        return new PlanNode(id, agent(id, slaMs), null, List.of());
    }

    /** The REAL harness (real Resilience4j) wrapped over a scripted adapter — proves genuine dispatch. */
    static AgentHarness harness(ScriptedAdapter adapter) {
        return new AgentHarness(List.of(adapter), CircuitBreakerRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                new ai.conduit.gateway.infrastructure.faults.NoopFaultInjector(),
                5_000, 32, 256, 50, 80, 10, 30, 100, 3, 2);
    }

    /** A context with a fresh structural grant for {@code agentId} (the green path). */
    static InvocationContext granting(String principalId, String requestId, String agentId) {
        return InvocationContext.of(principalId, "conv", requestId,
                null, List.of(AuthorizationGrant.structural(principalId, agentId, "cerbos", requestId)));
    }
}
