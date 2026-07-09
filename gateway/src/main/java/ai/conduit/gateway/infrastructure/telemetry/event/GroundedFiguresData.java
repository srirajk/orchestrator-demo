package ai.conduit.gateway.infrastructure.telemetry.event;

import java.util.List;

/**
 * Payload for {@code grounded_figures}: the manifest-declared figures rendered from
 * agent data before answer synthesis. Values are generic and attribution is by
 * source agent id so the harness can prove the answer's load-bearing numbers came
 * from data, not generated prose.
 */
public record GroundedFiguresData(List<Figure> figures) {
    public record Figure(
            String label,
            String renderedValue,
            String rawValue,
            String format,
            String sourceAgent
    ) {}
}
