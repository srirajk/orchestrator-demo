package ai.conduit.gateway.insights;

/** The metric backends a panel producer may query. Passed to every {@link PanelSpec} producer. */
public record Sources(PrometheusMetricsSource prom, LangfuseMetricsSource langfuse) {}
