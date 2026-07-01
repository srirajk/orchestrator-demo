package ai.conduit.gateway.orchestration.model;

import java.util.List;

/**
 * An execution plan: an ordered (but for Phase 4, independent) list of {@link PlanNode}s
 * that the {@code FlatPlanExecutor} will fan out in parallel.
 */
public record Plan(List<PlanNode> nodes) {}
