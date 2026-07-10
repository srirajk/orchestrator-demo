package ai.conduit.gateway.domain.insights.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A single rendered panel in a board.
 *
 * <p>Wire shape (nulls omitted): {@code {id,title,type,unit,status,value?,delta?,series?,rows?}}
 * where {@code type ∈ {stat, area, line, donut, bars, table, waterfall}} and
 * {@code status ∈ {"ok","unavailable"}}.
 *
 * <ul>
 *   <li>{@code stat}                 → {@code value} (+ optional {@code delta})</li>
 *   <li>{@code area} / {@code line}  → {@code series} (list of {@link Point})</li>
 *   <li>{@code donut} / {@code bars} → {@code rows} (list of {@link LabeledValue})</li>
 *   <li>{@code table} / {@code waterfall} → {@code rows} (list of column maps)</li>
 * </ul>
 *
 * <p>A panel whose backing query failed, timed out, was shed under backpressure, or
 * returned no data is emitted with {@code status:"unavailable"} and no payload — the
 * board still renders (partial-tolerant harvest), it never fabricates a number.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Panel(
        String id,
        String title,
        String type,
        String unit,
        String status,
        Object value,
        Object delta,
        List<Point> series,
        List<?> rows
) {
    public static final String OK = "ok";
    public static final String UNAVAILABLE = "unavailable";

    /** A single-figure {@code stat} panel. */
    public static Panel stat(String id, String title, String unit, double value) {
        return new Panel(id, title, "stat", unit, OK, round(value), null, null, null);
    }

    /** A {@code stat} panel with a period-over-period delta. */
    public static Panel stat(String id, String title, String unit, double value, double delta) {
        return new Panel(id, title, "stat", unit, OK, round(value), round(delta), null, null);
    }

    /** A raw-string {@code stat} panel (e.g. a state label). */
    public static Panel statText(String id, String title, String unit, String value) {
        return new Panel(id, title, "stat", unit, OK, value, null, null, null);
    }

    /** An {@code area} or {@code line} time-series panel. */
    public static Panel series(String id, String title, String type, String unit, List<Point> series) {
        return new Panel(id, title, type, unit, OK, null, null, series, null);
    }

    /** A {@code donut} or {@code bars} categorical panel. */
    public static Panel categorical(String id, String title, String type, String unit, List<LabeledValue> rows) {
        return new Panel(id, title, type, unit, OK, null, null, null, rows);
    }

    /** A {@code table} or {@code waterfall} panel (arbitrary column maps). */
    public static Panel table(String id, String title, String type, String unit, List<Map<String, Object>> rows) {
        return new Panel(id, title, type, unit, OK, null, null, null, rows);
    }

    /** The graceful empty state — survivors still render around it. */
    public static Panel unavailable(String id, String title, String type, String unit) {
        return new Panel(id, title, type, unit, UNAVAILABLE, null, null, null, null);
    }

    private static double round(double v) {
        // 4 significant decimals is plenty for exec figures and keeps the JSON tidy.
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
