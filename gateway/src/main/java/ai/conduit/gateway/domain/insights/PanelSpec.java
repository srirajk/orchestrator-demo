package ai.conduit.gateway.domain.insights;

import ai.conduit.gateway.domain.insights.model.Panel;

import java.util.function.Function;

/**
 * A declarative panel: its identity/type/unit plus a {@code producer} that queries the
 * {@link Sources} and returns a rendered {@link Panel}. The producer runs on its own virtual
 * thread inside {@link InsightsExecutor}; if it throws, times out, or is shed under
 * backpressure, the executor substitutes the panel's {@link #unavailable()} shell — so a single
 * slow query never fails the board (partial-tolerant harvest).
 *
 * <p>Producers read aggregations off metric <em>labels</em> (dynamic) and never embed a
 * domain/agent/entity list, keeping the catalog World-B clean.
 */
public record PanelSpec(
        String id,
        String title,
        String type,
        String unit,
        Function<Sources, Panel> producer
) {
    public Panel unavailable() {
        return Panel.unavailable(id, title, type, unit);
    }
}
