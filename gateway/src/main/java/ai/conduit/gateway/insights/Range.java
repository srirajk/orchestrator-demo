package ai.conduit.gateway.insights;

/**
 * Selectable analytics time-range for an Insights board — the three windows the UI's timeframe
 * selector offers. Parsing is centralized and <strong>fail-safe</strong>: an absent, blank, or
 * unrecognized value falls back to {@link #H24} (the default), so a bad {@code ?range=} query
 * param yields the default board rather than a 400/500.
 *
 * <p>Each range carries two backend windows:
 * <ul>
 *   <li>{@link #promWindow()} — the PromQL range-vector lookback token ({@code 24h}/{@code 7d}/
 *       {@code 30d}) threaded into {@code increase(...[w])} / {@code rate(...[w])} aggregations.</li>
 *   <li>{@link #langfuseDays()} — the {@code /metrics/daily?limit=} day count for board 7.</li>
 * </ul>
 *
 * <p><strong>World B:</strong> these are infrastructure query windows only; they carry zero
 * domain knowledge (no domain/agent/entity names, no ID patterns, no user-facing domain copy).
 */
public enum Range {

    H24("24h", 1),
    D7("7d", 7),
    D30("30d", 30);

    /** Wire value (query param) and PromQL lookback token — the two coincide by design. */
    private final String window;
    /** Langfuse {@code /metrics/daily?limit=} day count. */
    private final int langfuseDays;

    Range(String window, int langfuseDays) {
        this.window = window;
        this.langfuseDays = langfuseDays;
    }

    /** PromQL range-vector window token, e.g. {@code "7d"} → used as {@code increase(m[7d])}. */
    public String promWindow() { return window; }

    /** Langfuse lookback in days for board 7's daily-metrics queries. */
    public int langfuseDays() { return langfuseDays; }

    /** The wire/query-param value ({@code 24h}/{@code 7d}/{@code 30d}). */
    public String key() { return window; }

    /** Fail-safe parse: {@code null}/blank/unknown → {@link #H24} (default). Case-insensitive. */
    public static Range from(String raw) {
        if (raw != null) {
            String v = raw.trim().toLowerCase();
            for (Range r : values()) {
                if (r.window.equals(v)) return r;
            }
        }
        return H24;
    }
}
