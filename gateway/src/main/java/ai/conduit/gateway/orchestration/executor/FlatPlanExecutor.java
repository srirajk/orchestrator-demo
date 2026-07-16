package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.infrastructure.telemetry.MdcPropagation;
import ai.conduit.gateway.orchestration.invoke.GovernedInvoker;
import ai.conduit.gateway.orchestration.invoke.InvocationContext;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Flat plan executor: fans out all {@link PlanNode}s in parallel on virtual threads
 * and harvests survivors up to a 30-second overall deadline.
 *
 * <p>A failed or timed-out node never cancels its siblings.  Callers receive a
 * {@link NodeResult} for every node — either OK, FAILED, TIMEOUT, or BREAKER_OPEN.
 */
@Service
public class FlatPlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(FlatPlanExecutor.class);

    private final GovernedInvoker governedInvoker;
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final long overallDeadlineMs;

    public FlatPlanExecutor(
            GovernedInvoker governedInvoker,
            Tracer tracer,
            MeterRegistry meterRegistry,
            @Value("${conduit.orchestration.fan-out-deadline-ms:60000}") long overallDeadlineMs) {
        this.governedInvoker = governedInvoker;
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.overallDeadlineMs = overallDeadlineMs;
    }

    /**
     * Execute all plan nodes in parallel, returning one result per node.
     *
     * <p>Nodes that haven't completed by {@code overallDeadlineMs} are represented
     * as {@link NodeResult.Status#TIMEOUT} results; no exception is ever thrown.
     *
     * @param callerToken the calling principal's verified JWT, captured as request-scoped DATA at
     *                    ingress (see {@code ChatCompletionsController}) and threaded here because
     *                    {@code SecurityContextHolder} — thread-local — does not survive the hop
     *                    onto this executor's virtual threads (F-IDENTITY). Passed straight through
     *                    to {@link AgentHarness#execute(PlanNode, java.util.concurrent.ExecutorService, String)}
     *                    for every node.
     */
    public List<NodeResult> execute(Plan plan, String callerToken) {
        if (plan == null || plan.nodes() == null || plan.nodes().isEmpty()) {
            log.warn("FlatPlanExecutor received an empty plan");
            return List.of();
        }

        log.info("FlatPlanExecutor: fanning out {} nodes in parallel (deadline={}ms)",
                plan.nodes().size(), overallDeadlineMs);

        // Authorization envelope minted by ChatService from the structural verdicts it already computed.
        // Absent (legacy/no-mint path) ⇒ an empty fail-closed context: the governed invoker denies.
        final InvocationContext ctx = plan.context() != null
                ? plan.context()
                : InvocationContext.empty(null, null);

        // Resolver-SELECTION counter (distinct from agent_calls): each node in the plan is an
        // agent the resolver picked for this request. agentId is manifest-declared (World B).
        plan.nodes().forEach(n -> Counter.builder("conduit.resolver.selection")
                .description("Agents selected by the resolver, per agent")
                .tag("agentId", n.agent().agentId())
                .register(meterRegistry)
                .increment());

        // One virtual thread per node — no pool starvation under load.
        var exec = Executors.newVirtualThreadPerTaskExecutor();

        // Capture the request's OTel context on the calling thread (where the
        // `chat.handle` span is current). CompletableFuture.supplyAsync does NOT
        // propagate OTel context into the worker threads, so we carry it manually:
        // each per-agent span is parented to this context, and made current inside
        // the VT so the outbound traceparent nests the agent's own (Python) span.
        final Context parentContext = Context.current();

        // Same reasoning as parentContext above, for SLF4J's MDC (requestId/conversationId/userId):
        // it's thread-local and does not survive the hop onto `exec`'s virtual threads, so every
        // log line from inside harness.execute() (including HttpAdapter/McpAdapter's identity-
        // propagation log) would otherwise print an empty [rid= cid= uid=]. Captured here (this
        // calling thread was itself re-populated by ChatService's own MdcPropagation.run) and
        // re-applied per node below.
        final Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        List<CompletableFuture<NodeResult>> futures = plan.nodes().stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    NodeResult[] holder = new NodeResult[1];
                    MdcPropagation.run(mdcContext, () -> {
                        log.debug("Executing node {} (agent={}, protocol={})",
                                node.nodeId(), node.agent().agentId(), node.agent().protocol());
                        // One `agent.invoke` span per node — completes the trace tree:
                        // request → agent.invoke → (downstream agent span via traceparent).
                        Span span = tracer.spanBuilder("agent.invoke")
                                .setParent(parentContext)
                                .startSpan();
                        span.setAttribute("openinference.span.kind", "AGENT");
                        span.setAttribute("agent.id", node.agent().agentId());
                        span.setAttribute("agent.protocol", node.agent().protocol());
                        try (Scope ignored = span.makeCurrent()) {
                            NodeResult result = governedInvoker.invoke(ctx, node, exec);
                            span.setAttribute("agent.status", result.status().name());
                            span.setAttribute("agent.latency_ms", result.latencyMs());
                            if (!result.isOk()) {
                                span.setStatus(StatusCode.ERROR, result.status().name());
                            }
                            holder[0] = result;
                        } finally {
                            span.end();
                        }
                    });
                    return holder[0];
                }, exec))
                .toList();

        // Wait for all futures up to the deadline; exceptionally() swallows the
        // TimeoutException so we can still harvest whatever has completed.
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(overallDeadlineMs, TimeUnit.MILLISECONDS)
                .exceptionally(t -> null)  // swallow overall-deadline timeout
                .join();

        // Harvest survivors: completed futures give their result; incomplete ones
        // (still running past the deadline) become synthetic TIMEOUT results.
        List<PlanNode> nodes = plan.nodes();
        List<NodeResult> results = IntStream.range(0, futures.size())
                .mapToObj(i -> harvest(futures.get(i), nodes.get(i)))
                .toList();

        exec.close(); // virtual-thread executor; close() is a no-op but signals intent

        long okCount = results.stream().filter(NodeResult::isOk).count();
        log.info("FlatPlanExecutor done: {}/{} nodes succeeded", okCount, results.size());

        return results;
    }

    /** Return the NodeResult from a completed future, or a synthetic TIMEOUT if it didn't finish. */
    private NodeResult harvest(CompletableFuture<NodeResult> future, PlanNode node) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            return future.join();
        }
        // Future either timed out or completed exceptionally — treat as TIMEOUT.
        log.warn("Node {} (agent={}) did not complete within the overall deadline — marking TIMEOUT",
                node.nodeId(), node.agent().agentId());
        return timedOut(node);
    }

    private NodeResult timedOut(PlanNode node) {
        return new NodeResult(
                node.nodeId(),
                node.agent().agentId(),
                node.agent().protocol(),
                NodeResult.Status.TIMEOUT,
                null,
                overallDeadlineMs,
                "Did not complete within the " + overallDeadlineMs + "ms overall deadline");
    }
}
