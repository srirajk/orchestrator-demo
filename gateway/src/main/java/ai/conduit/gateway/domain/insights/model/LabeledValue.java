package ai.conduit.gateway.domain.insights.model;

/**
 * A labelled scalar: {@code {"label":...,"value":...}}.
 * Backs {@code donut} / {@code bars} panel rows. The {@code label} is always a
 * <em>metric label value</em> read straight off Prometheus (e.g. an {@code agentId},
 * {@code outcome}, {@code decision}) — never a value the gateway hardcodes (World B).
 */
public record LabeledValue(String label, double value) {}
