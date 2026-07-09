package ai.conduit.gateway.orchestration.harness;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Resilience integration test — verifies the harness's core invariant:
 * {@code execute()} NEVER throws; every outcome is encoded in {@link NodeResult}.
 *
 * Uses real Resilience4j registries (no mocking) so circuit-breaker thresholds,
 * bulkhead limits, and timer behaviour are exactly as configured in production.
 */
class AgentHarnessResilienceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    CircuitBreakerRegistry cbReg;

    @BeforeEach
    void freshRegistries() {
        cbReg = CircuitBreakerRegistry.ofDefaults();
    }

    // ── Adapter factories ────────────────────────────────────────────────────

    private ProtocolAdapter adapter(String protocol, JsonNode result) {
        return new ProtocolAdapter() {
            @Override public String protocol() { return protocol; }
            @Override public JsonNode invoke(AgentManifest m, JsonNode i, String bearerToken) { return result; }
        };
    }

    private ProtocolAdapter failingAdapter(String protocol) {
        return new ProtocolAdapter() {
            @Override public String protocol() { return protocol; }
            @Override public JsonNode invoke(AgentManifest m, JsonNode i, String bearerToken) {
                throw new RuntimeException("simulated agent failure");
            }
        };
    }

    private ProtocolAdapter slowAdapter(String protocol, long delayMs) {
        return new ProtocolAdapter() {
            @Override public String protocol() { return protocol; }
            @Override public JsonNode invoke(AgentManifest m, JsonNode i, String bearerToken) throws Exception {
                Thread.sleep(delayMs);
                return MAPPER.createObjectNode().put("ok", true);
            }
        };
    }

    // ── Manifest / node builders ─────────────────────────────────────────────

    /**
     * Builds a minimal {@link AgentManifest} suitable for harness tests.
     * All optional/derived fields are null; only the fields the harness reads are set:
     * {@code agentId}, {@code protocol}, and {@code constraints.slaTimeoutMs}.
     */
    private AgentManifest manifest(String id, String protocol, int slaMs) {
        return new AgentManifest(
                id,                       // agentId
                id + "-name",             // name
                "test agent",             // description
                "1.0",                    // version
                null,                     // provider
                "wealth",                 // domain
                "segment",                // audience
                null,                     // subDomain
                null,                     // maxResponseTokens
                protocol,                 // protocol
                null,                     // connection
                null,                     // capabilities
                null,                     // skills
                new AgentManifest.Constraints("read", "internal", slaMs), // constraints
                null,                     // inputSchema  (derived)
                null,                     // outputSchema (derived)
                null,                     // resolvedConnection (derived)
                null,                     // indexed (derived)
                null                      // registeredAt (derived)
        );
    }

    private PlanNode node(String id, String protocol, int slaMs) {
        return new PlanNode(id, manifest(id, protocol, slaMs), MAPPER.createObjectNode(), List.of());
    }

    private String tokenWithExp(long exp) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJSUzI1NiJ9." + payload + ".signature";
    }

    /** Construct a harness with sensible test defaults for all config values. */
    private AgentHarness harness(List<ProtocolAdapter> adapters) {
        return new AgentHarness(adapters, cbReg,
                new SimpleMeterRegistry(), // meterRegistry
                5_000, // defaultSlaMs
                5,     // bulkheadMaxConcurrent
                20,    // bulkheadQueueCapacity
                50,    // cbFailureRate
                80,    // cbSlowCallRate
                10,    // cbSlowCallDurationSeconds
                30,    // cbOpenWaitSeconds
                10,    // cbSlidingWindowSize
                3,     // cbMinCalls
                2);    // cbHalfOpenPermittedCalls
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void successfulAdapter_yieldsOkResult() {
        JsonNode data = MAPPER.createObjectNode().put("value", 42);
        AgentHarness harness = harness(List.of(adapter("http", data)));

        NodeResult result = harness.execute(node("agent-ok", "http", 5_000));

        assertThat(result.status()).isEqualTo(NodeResult.Status.OK);
        assertThat(result.isOk()).isTrue();
        assertThat(result.data()).isEqualTo(data);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void expiredCallerToken_yieldsAuthExpiredBeforeDispatch() {
        JsonNode data = MAPPER.createObjectNode().put("value", 42);
        AgentHarness harness = harness(List.of(adapter("http", data)));

        NodeResult result = harness.execute(node("agent-expired", "http", 5_000),
                null, tokenWithExp(1));

        assertThat(result.status()).isEqualTo(NodeResult.Status.AUTH_EXPIRED);
        assertThat(result.data()).isNull();
        assertThat(result.errorMessage()).contains("expired");
    }

    @Test
    void failingAdapter_yieldsFailedResult_doesNotThrow() {
        AgentHarness harness = harness(List.of(failingAdapter("http")));

        // Must NOT throw — the invariant is that execute() always returns
        assertThatCode(() -> harness.execute(node("agent-fail", "http", 5_000)))
                .doesNotThrowAnyException();

        NodeResult result = harness.execute(node("agent-fail-2", "http", 5_000));
        assertThat(result.status()).isEqualTo(NodeResult.Status.FAILED);
        assertThat(result.isOk()).isFalse();
        assertThat(result.data()).isNull();
        assertThat(result.errorMessage()).contains("simulated agent failure");
    }

    @Test
    void unknownProtocol_yieldsFailedResult() {
        AgentHarness harness = harness(List.of(adapter("http", MAPPER.createObjectNode())));

        NodeResult result = harness.execute(node("agent-mcp", "mcp", 5_000));

        assertThat(result.status()).isEqualTo(NodeResult.Status.FAILED);
        assertThat(result.errorMessage()).contains("ProtocolAdapter");
    }

    @Test
    void timedOutAdapter_yieldsTimeoutResult() throws Exception {
        // SLA = 200ms, adapter sleeps 2s → should TIME OUT
        AgentHarness harness = harness(List.of(slowAdapter("http", 2_000)));

        NodeResult result = harness.execute(node("agent-slow", "http", 200));

        assertThat(result.status()).isEqualTo(NodeResult.Status.TIMEOUT);
        assertThat(result.isOk()).isFalse();
    }

    @Test
    void twoAgentsOneFails_otherSucceeds_bothReturnResults() throws Exception {
        JsonNode successData = MAPPER.createObjectNode().put("holdings", "data");
        ProtocolAdapter httpSuccess = adapter("http", successData);
        ProtocolAdapter mcpFail    = failingAdapter("mcp");

        AgentHarness harness = harness(List.of(httpSuccess, mcpFail));

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<NodeResult> f1 = exec.submit(() -> harness.execute(node("http-agent", "http", 5_000), exec));
            Future<NodeResult> f2 = exec.submit(() -> harness.execute(node("mcp-agent",  "mcp",  5_000), exec));

            NodeResult r1 = f1.get();
            NodeResult r2 = f2.get();

            // HTTP agent succeeded
            assertThat(r1.status()).isEqualTo(NodeResult.Status.OK);
            assertThat(r1.data()).isEqualTo(successData);

            // MCP agent failed — but we still got a result (not a throw)
            assertThat(r2.status()).isEqualTo(NodeResult.Status.FAILED);
            assertThat(r2.isOk()).isFalse();
        }
    }

    @Test
    void circuitBreaker_opensAfterRepeatedFailures() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        ProtocolAdapter alwaysFails = new ProtocolAdapter() {
            @Override public String protocol() { return "http"; }
            @Override public JsonNode invoke(AgentManifest m, JsonNode i, String bearerToken) {
                callCount.incrementAndGet();
                throw new RuntimeException("always fails");
            }
        };

        // CB config: minimumNumberOfCalls=3, slidingWindowSize=10, failureRate=50%
        // After enough failures the breaker opens and subsequent calls yield BREAKER_OPEN.
        AgentHarness harness = harness(List.of(alwaysFails));

        int failedCount = 0;
        int breakerOpenCount = 0;
        String agentId = "cb-test-agent";

        for (int i = 0; i < 20; i++) {
            // Use unique node IDs but same agentId (CB is keyed by agentId)
            NodeResult r = harness.execute(
                    new PlanNode("node-" + i,
                            manifest(agentId, "http", 5_000),
                            MAPPER.createObjectNode(),
                            List.of()));
            if (r.status() == NodeResult.Status.FAILED)      failedCount++;
            if (r.status() == NodeResult.Status.BREAKER_OPEN) breakerOpenCount++;
        }

        // All 20 calls returned results (no throws)
        assertThat(failedCount + breakerOpenCount).isEqualTo(20);
        // After enough failures, breaker must have opened at least once
        assertThat(breakerOpenCount).isGreaterThan(0);
    }

    @Test
    void multipleSuccessfulAgents_allReturnOk_inParallel() throws Exception {
        JsonNode data = MAPPER.createObjectNode().put("ok", true);
        AgentHarness harness = harness(List.of(adapter("http", data)));

        int agentCount = 7; // hero prompt fans out to ~7 agents
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<NodeResult>> futures = IntStream.range(0, agentCount)
                    .mapToObj(i -> exec.submit(() -> harness.execute(
                            node("agent-" + i, "http", 5_000), exec)))
                    .toList();

            long okCount = futures.stream()
                    .map(f -> {
                        try { return f.get(); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .filter(NodeResult::isOk)
                    .count();

            assertThat(okCount).isEqualTo(agentCount);
        }
    }
}
