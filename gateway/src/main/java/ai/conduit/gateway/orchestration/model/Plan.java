package ai.conduit.gateway.orchestration.model;

import ai.conduit.gateway.orchestration.invoke.InvocationContext;

import java.util.List;

/**
 * An execution plan: an ordered (but for Phase 4, independent) list of {@link PlanNode}s
 * that the {@code FlatPlanExecutor} / {@code DagPlanExecutor} fan out through the governed invoker.
 *
 * <p>{@code context} carries the per-request authorization envelope
 * ({@link InvocationContext}) the executors thread into the {@code GovernedInvoker}. It is nullable so
 * every legacy {@code new Plan(nodes)} call site compiles unchanged; a null context means the
 * fail-closed checkpoint has NO grants to verify and will deny every hop — the safe default for any
 * path that forgets to mint.
 */
public record Plan(List<PlanNode> nodes, InvocationContext context) {

    /** Legacy constructor — no authorization context (fail-closed at the checkpoint). */
    public Plan(List<PlanNode> nodes) {
        this(nodes, null);
    }

    /** This plan with an authorization context attached (grants minted from real verdicts). */
    public Plan withContext(InvocationContext context) {
        return new Plan(nodes, context);
    }
}
