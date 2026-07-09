package ai.conduit.gateway.orchestration.planner;

import java.util.List;

/**
 * A single, machine-classified reason a {@link DagResolver} run could not produce a valid plan.
 *
 * <p>Messages are built purely from manifest-supplied symbols (capability ids, produced/consumed
 * type strings, entity keys) — the resolver embeds no domain vocabulary of its own. {@code details}
 * carries the offending symbols (e.g. the candidate producer ids for an ambiguous match, or the
 * capability ids forming a cycle) so callers can render or log them without re-parsing the message.
 */
public record ResolutionError(Code code, String message, List<String> details) {

    public enum Code {
        /** A consumed {@code from} type has no capability declaring it in {@code io.produces}. */
        MISSING_PRODUCER,
        /** A consumed {@code from} type is produced by more than one candidate — no deterministic pick. */
        AMBIGUOUS_PRODUCER,
        /** The dependency edges contain a cycle; no topological order exists. */
        CYCLE,
        /** A required {@code entity} input is not present in the available set and no producer supplies it. */
        UNMET_REQUIRED_INPUT,
        /** A referenced capability id (the goal, or a dependency edge target) is not in the candidate set. */
        UNKNOWN_NODE
    }

    public ResolutionError {
        details = details == null ? List.of() : List.copyOf(details);
    }

    static ResolutionError of(Code code, String message, List<String> details) {
        return new ResolutionError(code, message, details);
    }

    static ResolutionError of(Code code, String message, String... details) {
        return new ResolutionError(code, message, List.of(details));
    }
}
