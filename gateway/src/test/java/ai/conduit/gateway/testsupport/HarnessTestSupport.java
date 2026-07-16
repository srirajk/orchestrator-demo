package ai.conduit.gateway.testsupport;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;

/**
 * Public builders that wire the REAL {@link AgentHarness} (real Resilience4j) to a
 * {@link ScriptedAdapter}, so tests in any package can exercise the bulkhead → breaker → SLA guard
 * end-to-end without docker. Mirrors the package-private {@code ExecutorTestSupport} but reusable.
 */
public final class HarnessTestSupport {

    private HarnessTestSupport() {}

    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** A manifest with the given protocol and SLA; all domain fields null (World-B: no domain shape). */
    public static AgentManifest agent(String id, String protocol, int slaMs) {
        return new AgentManifest(
                id, id, null, null, null, null, null, null, null, protocol,
                null, null, null, new Constraints("read", "internal", slaMs),
                null, null, null, null, true, null);
    }

    public static PlanNode node(String id, String protocol, int slaMs, JsonNode input) {
        return new PlanNode(id, agent(id, protocol, slaMs), input, List.of());
    }

    /**
     * Real harness with a generous bulkhead (so distinct-agent fan-out never rejects) and a
     * wide circuit-breaker sliding window (so the breaker stays closed under a healthy sweep).
     */
    public static AgentHarness harness(ProtocolAdapter adapter, int defaultSlaMs,
                                       int maxConcurrent, int queueCapacity) {
        return new AgentHarness(
                List.of(adapter), CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(),
                defaultSlaMs, maxConcurrent, queueCapacity,
                50, 80, 10, 30, 1000, 3, 2);
    }
}
