package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Consume;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.registry.model.AgentManifest.Io;
import ai.conduit.gateway.registry.model.AgentManifest.Produce;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared, domain-free builders for the {@link DagPlanExecutor} concurrency/chaos battery. Uses the
 * REAL {@link AgentHarness} (real Resilience4j) wired to configurable fake adapters, so binding,
 * layering and partial-failure are exercised end-to-end without docker.
 */
final class ExecutorTestSupport {

    private ExecutorTestSupport() {}

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Tracer TRACER = OpenTelemetry.noop().getTracer("test");

    // ── manifest / node builders ──────────────────────────────────────────────────────────────

    static AgentManifest agent(String id, Io io) {
        return new AgentManifest(
                id, id, null, null, null, null, null, null, null, "http",
                null, null, null, new Constraints("read", "internal", 5_000),
                io, null, null, null, true, null);
    }

    static Io io(List<Consume> consumes, List<Produce> produces) { return new Io(consumes, produces); }
    static Consume from(String type)                 { return new Consume(null, type, true); }
    static Consume entity(String key)                { return new Consume(key, null, true); }
    static Produce produce(String name, String type) { return new Produce(name, type); }

    static PlanNode node(String id, Io io, List<String> deps) {
        return new PlanNode(id, agent(id, io), null, deps);
    }

    // ── harness / executor factories (generous bulkhead so distinct-agent fan-out never rejects) ─

    static AgentHarness harness(ProtocolAdapter adapter) {
        return new AgentHarness(List.of(adapter), CircuitBreakerRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                5_000,   // default SLA
                32,      // bulkhead max-concurrent (per agentId)
                256,     // bulkhead queue-capacity (per agentId)
                50, 80, 10, 30, 100, 3, 2);   // CB config (large sliding window → CB stays closed)
    }

    static DagPlanExecutor executor(AgentHarness harness, long deadlineMs) {
        return new DagPlanExecutor(harness, TRACER, new SimpleMeterRegistry(), deadlineMs);
    }

    /**
     * Fake HTTP adapter: sleeps a random few ms to force thread interleaving, records invocation
     * order and the exact input each agent was bound with, returns a UNIQUE, identifiable payload
     * per agent ({@code {"src": <agentId>, "seq": <n>}}), and fails a configurable set of agentIds.
     */
    static final class SleepyAdapter implements ProtocolAdapter {
        final List<String> order = new CopyOnWriteArrayList<>();
        final Map<String, JsonNode> inputs = new ConcurrentHashMap<>();
        final Set<String> failIds;
        final int maxSleepMs;

        SleepyAdapter(Set<String> failIds, int maxSleepMs) {
            this.failIds = failIds;
            this.maxSleepMs = maxSleepMs;
        }

        @Override public String protocol() { return "http"; }

        @Override public JsonNode invoke(AgentManifest m, JsonNode input, String bearerToken) {
            String id = m.agentId();
            order.add(id);
            if (input != null) inputs.put(id, input);
            if (maxSleepMs > 0) {
                try { Thread.sleep(ThreadLocalRandom.current().nextInt(maxSleepMs + 1)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (failIds.contains(id)) throw new RuntimeException("chaos-fail:" + id);
            return uniqueOutput(id);
        }
    }

    /** The exact payload {@link SleepyAdapter} returns for a healthy agent — the value-tracing oracle. */
    static JsonNode uniqueOutput(String agentId) {
        return MAPPER.createObjectNode().put("src", agentId);
    }
}
