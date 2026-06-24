package ai.meridian.gateway.orchestration.harness;

import ai.meridian.gateway.adapter.ProtocolAdapter;
import ai.meridian.gateway.orchestration.model.NodeResult;
import ai.meridian.gateway.orchestration.model.PlanNode;
import ai.meridian.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

/**
 * Wraps each agent adapter call in a Resilience4j harness:
 * {@code CircuitBreaker(agentId) → TimeLimiter(slaMs) → Bulkhead(agentId) → adapter.invoke()}.
 *
 * <p>Contract: {@link #execute} NEVER throws — every outcome is encoded in
 * the returned {@link NodeResult}.
 *
 * <p>Circuit breakers and bulkheads are keyed by {@code agentId} and are shared
 * across concurrent requests, so a repeatedly failing agent trips once for all callers.
 */
@Service
public class AgentHarness {

    private static final Logger log = LoggerFactory.getLogger(AgentHarness.class);

    /** Max concurrent calls per agent (semaphore bulkhead). */
    private static final int MAX_CONCURRENT_PER_AGENT = 5;
    /** Default SLA if the manifest doesn't specify one. */
    private static final int DEFAULT_SLA_MS = 5_000;

    private final List<ProtocolAdapter> adapters;
    private final CircuitBreakerRegistry cbRegistry;
    private final TimeLimiterRegistry tlRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    /** Shared scheduler used by TimeLimiter to cancel futures on timeout. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "resilience4j-scheduler");
                t.setDaemon(true);
                return t;
            });

    public AgentHarness(
            List<ProtocolAdapter> adapters,
            CircuitBreakerRegistry cbRegistry,
            TimeLimiterRegistry tlRegistry,
            BulkheadRegistry bulkheadRegistry) {
        this.adapters = adapters;
        this.cbRegistry = cbRegistry;
        this.tlRegistry = tlRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
    }

    /**
     * Execute a single plan node through the resilience harness.
     *
     * <p>Never throws — all failures are captured in the returned {@link NodeResult}.
     */
    public NodeResult execute(PlanNode node) {
        AgentManifest agent = node.agent();
        String agentId = agent.agentId();
        long start = System.currentTimeMillis();

        ProtocolAdapter adapter = findAdapter(agent.protocol());
        if (adapter == null) {
            long latency = System.currentTimeMillis() - start;
            log.warn("No adapter found for protocol '{}' (agent={})", agent.protocol(), agentId);
            return failed(node, latency,
                    "No ProtocolAdapter registered for protocol: " + agent.protocol());
        }

        // Resolve per-agent Resilience4j components (created on demand with their defaults,
        // then overridden with per-agent settings below).
        CircuitBreaker cb = circuitBreaker(agentId);
        TimeLimiter tl = timeLimiter(agentId, resolveTimeoutMs(agent));
        Bulkhead bulkhead = bulkhead(agentId);

        // Build the decorated supplier chain: CB → TimeLimiter → Bulkhead → adapter
        Callable<JsonNode> decorated = TimeLimiter.decorateFutureSupplier(
                tl,
                () -> {
                    // Wrap the Bulkhead → adapter call in a Future so TimeLimiter can cancel it.
                    Callable<JsonNode> innerCall =
                            Bulkhead.decorateCallable(bulkhead,
                                    () -> CircuitBreaker.decorateCallable(cb,
                                            () -> adapter.invoke(node.agent(), node.input()))
                                            .call());
                    // Submit on a virtual thread so the TimeLimiter can interrupt it.
                    Future<JsonNode> future =
                            Executors.newVirtualThreadPerTaskExecutor().submit(innerCall);
                    return future;
                });

        try {
            JsonNode data = decorated.call();
            long latency = System.currentTimeMillis() - start;
            log.debug("Agent {} OK in {}ms", agentId, latency);
            return new NodeResult(node.nodeId(), agentId, agent.protocol(),
                    NodeResult.Status.OK, data, latency, null);

        } catch (CallNotPermittedException e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Circuit breaker OPEN for agent {} — skipping call", agentId);
            return new NodeResult(node.nodeId(), agentId, agent.protocol(),
                    NodeResult.Status.BREAKER_OPEN, null, latency,
                    "Circuit breaker is open: " + e.getMessage());

        } catch (TimeoutException e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Agent {} TIMEOUT after {}ms (sla={}ms)", agentId, latency,
                    resolveTimeoutMs(agent));
            return new NodeResult(node.nodeId(), agentId, agent.protocol(),
                    NodeResult.Status.TIMEOUT, null, latency,
                    "Agent timed out after " + latency + "ms");

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Agent {} FAILED: {}", agentId, e.getMessage(), e);
            return failed(node, latency, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // ── Component factories ──────────────────────────────────────────────────

    private CircuitBreaker circuitBreaker(String agentId) {
        // Use existing config if already registered; otherwise create with sensible defaults.
        if (!cbRegistry.getAllCircuitBreakers().stream()
                .map(CircuitBreaker::getName).anyMatch(agentId::equals)) {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)           // open after 50% failures
                    .slowCallRateThreshold(80)
                    .slowCallDurationThreshold(Duration.ofSeconds(10))
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .slidingWindowSize(10)
                    .minimumNumberOfCalls(3)
                    .permittedNumberOfCallsInHalfOpenState(2)
                    .build();
            return cbRegistry.circuitBreaker(agentId, config);
        }
        return cbRegistry.circuitBreaker(agentId);
    }

    private TimeLimiter timeLimiter(String agentId, int timeoutMs) {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeoutMs))
                .cancelRunningFuture(true)
                .build();
        return tlRegistry.timeLimiter(agentId, config);
    }

    private Bulkhead bulkhead(String agentId) {
        if (!bulkheadRegistry.getAllBulkheads().stream()
                .map(Bulkhead::getName).anyMatch(agentId::equals)) {
            BulkheadConfig config = BulkheadConfig.custom()
                    .maxConcurrentCalls(MAX_CONCURRENT_PER_AGENT)
                    .maxWaitDuration(Duration.ofMillis(500))
                    .build();
            return bulkheadRegistry.bulkhead(agentId, config);
        }
        return bulkheadRegistry.bulkhead(agentId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ProtocolAdapter findAdapter(String protocol) {
        return adapters.stream()
                .filter(a -> a.protocol().equals(protocol))
                .findFirst()
                .orElse(null);
    }

    private int resolveTimeoutMs(AgentManifest agent) {
        if (agent.constraints() != null && agent.constraints().slaTimeoutMs() > 0) {
            return agent.constraints().slaTimeoutMs();
        }
        return DEFAULT_SLA_MS;
    }

    private NodeResult failed(PlanNode node, long latencyMs, String message) {
        return new NodeResult(
                node.nodeId(),
                node.agent().agentId(),
                node.agent().protocol(),
                NodeResult.Status.FAILED,
                null,
                latencyMs,
                message);
    }
}
