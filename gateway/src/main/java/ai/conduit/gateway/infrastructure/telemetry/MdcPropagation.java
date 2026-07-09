package ai.conduit.gateway.infrastructure.telemetry;

import org.slf4j.MDC;

import java.util.Map;

/**
 * Re-applies a captured MDC context map (requestId/conversationId/userId — set by
 * {@link RequestCorrelationFilter} on the servlet thread) on a different thread, then clears it.
 *
 * <p><b>Why this exists:</b> SLF4J's {@link MDC} is thread-local. {@code RequestCorrelationFilter}
 * populates it once, on the servlet thread. Every subsequent hop in the request pipeline runs on a
 * FRESH virtual thread — {@code ChatCompletionsController}'s {@code pipelineExecutor}, {@code
 * FlatPlanExecutor}/{@code DagPlanExecutor}'s per-node executor, and {@code AgentHarness}'s own
 * {@code vtExecutor} — none of which inherit the servlet thread's MDC. Left unfixed, every log line
 * from those hops (including the security-relevant "propagating JWT to agent" line in {@code
 * HttpAdapter}/{@code McpAdapter}) prints an empty {@code [rid= cid= uid=]}, making per-request log
 * correlation unreliable for exactly the hop this matters most for.
 *
 * <p>This is the SAME class of bug as the caller-identity propagation gap (F-IDENTITY) — a
 * thread-local that silently does not survive a virtual-thread hop — fixed the same way: capture on
 * the sending side, thread through explicitly, re-apply on the receiving side. Each hop that starts a
 * new virtual thread captures {@link MDC#getCopyOfContextMap()} on its OWN thread (already
 * re-populated by the previous hop) before handing off, so the context threads all the way down to
 * the agent invocation.
 */
public final class MdcPropagation {

    private MdcPropagation() {}

    /** Runs {@code task} with {@code mdcContext} applied on the current thread, then clears MDC. */
    public static void run(Map<String, String> mdcContext, Runnable task) {
        if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
        }
        try {
            task.run();
        } finally {
            MDC.clear();
        }
    }
}
