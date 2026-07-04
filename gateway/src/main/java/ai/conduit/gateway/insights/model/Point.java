package ai.conduit.gateway.insights.model;

/**
 * One point in a time series: {@code t} = epoch-seconds, {@code v} = value.
 * Serialized as {@code {"t":...,"v":...}} for {@code area}/{@code line} panels.
 */
public record Point(long t, double v) {}
