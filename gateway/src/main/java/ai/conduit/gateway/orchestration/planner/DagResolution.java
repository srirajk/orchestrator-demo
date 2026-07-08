package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.Plan;

import java.util.List;

/**
 * Outcome of a {@link DagResolver} run.
 *
 * <p>{@code ok == true}  → {@code plan} is a topologically ordered, dependency-populated {@link Plan}
 * and {@code errors} is empty. {@code ok == false} → {@code plan} is {@code null} and {@code errors}
 * lists every classified failure found (the resolver reports all it can detect, not just the first).
 */
public record DagResolution(boolean ok, Plan plan, List<ResolutionError> errors) {

    public DagResolution {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    static DagResolution success(Plan plan) {
        return new DagResolution(true, plan, List.of());
    }

    static DagResolution failure(List<ResolutionError> errors) {
        return new DagResolution(false, null, errors);
    }

    /** True if any error carries the given code — convenience for callers and tests. */
    public boolean hasError(ResolutionError.Code code) {
        return errors.stream().anyMatch(e -> e.code() == code);
    }
}
