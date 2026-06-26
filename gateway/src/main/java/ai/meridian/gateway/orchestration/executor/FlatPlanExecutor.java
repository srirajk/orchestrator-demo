package ai.meridian.gateway.orchestration.executor;

import ai.meridian.gateway.orchestration.harness.AgentHarness;
import ai.meridian.gateway.orchestration.model.NodeResult;
import ai.meridian.gateway.orchestration.model.Plan;
import ai.meridian.gateway.orchestration.model.PlanNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private final AgentHarness harness;
    private final long overallDeadlineMs;

    public FlatPlanExecutor(
            AgentHarness harness,
            @Value("${meridian.orchestration.fan-out-deadline-ms:60000}") long overallDeadlineMs) {
        this.harness = harness;
        this.overallDeadlineMs = overallDeadlineMs;
    }

    /**
     * Execute all plan nodes in parallel, returning one result per node.
     *
     * <p>Nodes that haven't completed by {@code overallDeadlineMs} are represented
     * as {@link NodeResult.Status#TIMEOUT} results; no exception is ever thrown.
     */
    public List<NodeResult> execute(Plan plan) {
        if (plan == null || plan.nodes() == null || plan.nodes().isEmpty()) {
            log.warn("FlatPlanExecutor received an empty plan");
            return List.of();
        }

        log.info("FlatPlanExecutor: fanning out {} nodes in parallel (deadline={}ms)",
                plan.nodes().size(), overallDeadlineMs);

        // One virtual thread per node — no pool starvation under load.
        var exec = Executors.newVirtualThreadPerTaskExecutor();

        List<CompletableFuture<NodeResult>> futures = plan.nodes().stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    log.debug("Executing node {} (agent={}, protocol={})",
                            node.nodeId(), node.agent().agentId(), node.agent().protocol());
                    return harness.execute(node, exec);
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
