package ai.conduit.gateway.insights;

/**
 * Seam over a queryable metrics backend (hard-rule g: build the simple path, leave the
 * seam). The Insights engine talks to backends only through concrete implementations of
 * this interface — {@link PrometheusMetricsSource} (ops) and {@link LangfuseMetricsSource}
 * (cost/eval) — so a backend can later be swapped or the whole module extracted into its
 * own service without touching the executor or controller.
 *
 * <p>Implementations MUST be non-throwing at the query surface: a backend outage returns an
 * empty/absent result, never an exception that could abort a sibling panel. The Insights
 * executor treats an empty result as {@code status:"unavailable"}.
 */
public interface MetricsSource {

    /** Stable identifier for logs/metrics, e.g. {@code "prometheus"} / {@code "langfuse"}. */
    String id();

    /** Cheap liveness probe used by diagnostics; never throws. */
    boolean isHealthy();
}
