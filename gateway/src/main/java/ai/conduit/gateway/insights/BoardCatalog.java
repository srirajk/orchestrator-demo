package ai.conduit.gateway.insights;

import ai.conduit.gateway.insights.model.LabeledValue;
import ai.conduit.gateway.insights.model.Panel;
import ai.conduit.gateway.insights.model.Point;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Declarative catalog of the 7 Insights boards and their panels, each bound to a PromQL query
 * (boards 1–6, ops — Prometheus) or a Langfuse query (board 7, cost/quality). One board = one
 * {@code GET /v1/insights/boards/{id}}.
 *
 * <h3>World B</h3>
 * Not one domain/agent/entity name appears here. Every "by X" breakdown aggregates on a metric
 * <em>label</em> ({@code agentId}, {@code outcome}, {@code decision}, {@code protocol},
 * {@code type}, {@code resource_type}, {@code role}) whose values are discovered dynamically from
 * the series Prometheus returns. Onboarding a new domain adds label values automatically; nothing
 * here changes. The only literals are query windows and metric names — infrastructure, not domain.
 *
 * <p>A panel whose metric has no data yet (e.g. a just-added counter, or Langfuse with no traces)
 * degrades to {@code status:"unavailable"} at render time rather than showing a fabricated value.
 */
@Component
public class BoardCatalog {

    // Query windows (infrastructure constants, not domain knowledge).
    private static final String LOOKBACK    = "24h";          // instant increase() window
    private static final String RATE_WIN    = "5m";           // rate() window for series
    private static final Duration RANGE_WIN = Duration.ofHours(6);   // range-query span
    private static final Duration STEP       = Duration.ofMinutes(15); // ~24 points over 6h
    private static final int LF_DAYS        = 30;             // Langfuse lookback (days)

    private final Map<Integer, List<PanelSpec>> boards = Map.of(
            1, boardOverview(),
            2, boardTrafficIntent(),
            3, boardGovernance(),
            4, boardAgentPerformance(),
            5, boardReliability(),
            6, boardLiveTrace(),
            7, boardCostQuality()
    );

    /** Panels for a board id, or {@code null} if the id is not 1–7. */
    public List<PanelSpec> panelsFor(int boardId) {
        return boards.get(boardId);
    }

    public boolean exists(int boardId) {
        return boards.containsKey(boardId);
    }

    // ── Board 1 — Executive Overview ─────────────────────────────────────────────
    private List<PanelSpec> boardOverview() {
        return List.of(
                stat("requests_24h", "Requests (24h)", "count",
                        "sum(increase(conduit_request_outcome_total[" + LOOKBACK + "]))"),
                stat("answered_rate", "Answered rate", "%",
                        "sum(increase(conduit_request_outcome_total{outcome=\"ANSWERED\"}[" + LOOKBACK + "]))"
                      + " / clamp_min(sum(increase(conduit_request_outcome_total[" + LOOKBACK + "])),1) * 100"),
                stat("agent_calls_24h", "Agent calls (24h)", "count",
                        "sum(increase(conduit_agent_calls_total[" + LOOKBACK + "]))"),
                stat("fanout_avg_ms", "Avg fan-out", "ms",
                        "sum(rate(conduit_fanout_duration_seconds_sum[" + LOOKBACK + "]))"
                      + " / clamp_min(sum(rate(conduit_fanout_duration_seconds_count[" + LOOKBACK + "])),1) * 1000"),
                area("request_volume", "Request volume", "req/s",
                        "sum(rate(conduit_request_outcome_total[" + RATE_WIN + "]))"),
                donut("outcome_mix", "Outcome mix", "count",
                        "sum by (outcome) (increase(conduit_request_outcome_total[" + LOOKBACK + "]))", "outcome")
        );
    }

    // ── Board 2 — Traffic & Intent ───────────────────────────────────────────────
    private List<PanelSpec> boardTrafficIntent() {
        return List.of(
                stat("questions_24h", "Questions (24h)", "count",
                        "sum(increase(chat_intent_total[" + LOOKBACK + "]))"),
                statNoData("ttft_p95", "Time-to-first-token p95", "s", "stat",
                        s -> {
                            OptionalDouble v = s.prom().scalar(
                                    "histogram_quantile(0.95, sum(rate(conduit_ttft_seconds_bucket[" + LOOKBACK + "])) by (le))");
                            return v.isPresent()
                                    ? Panel.stat("ttft_p95", "Time-to-first-token p95", "s", v.getAsDouble())
                                    : Panel.unavailable("ttft_p95", "Time-to-first-token p95", "stat", "s");
                        }),
                area("questions_over_time", "Questions over time", "q/s",
                        "sum(rate(chat_intent_total[" + RATE_WIN + "]))"),
                donut("intent_mix", "Intent mix", "count",
                        "sum by (type) (increase(chat_intent_total[" + LOOKBACK + "]))", "type"),
                bars("adoption_by_role", "Adoption by role", "count",
                        "sum by (role) (increase(conduit_adoption_total[" + LOOKBACK + "]))", "role")
        );
    }

    // ── Board 3 — Governance / Authorization ─────────────────────────────────────
    private List<PanelSpec> boardGovernance() {
        return List.of(
                stat("allow_rate", "Allow rate", "%",
                        "sum(increase(conduit_authz_decisions_total{decision=\"ALLOW\"}[" + LOOKBACK + "]))"
                      + " / clamp_min(sum(increase(conduit_authz_decisions_total[" + LOOKBACK + "])),1) * 100"),
                stat("decisions_24h", "Authorization checks (24h)", "count",
                        "sum(increase(conduit_authz_decisions_total[" + LOOKBACK + "]))"),
                donut("decision_mix", "Allow vs deny", "count",
                        "sum by (decision) (increase(conduit_authz_decisions_total[" + LOOKBACK + "]))", "decision"),
                bars("decisions_by_resource", "Checks by resource type", "count",
                        "sum by (resource_type) (increase(conduit_authz_decisions_total[" + LOOKBACK + "]))", "resource_type"),
                bars("denials_by_agent", "Denials by agent", "count",
                        "sum by (agentId) (increase(conduit_agent_denials_total[" + LOOKBACK + "]))", "agentId"),
                table("authz_ledger", "Authorization ledger", "count",
                        "sum by (decision, resource_type, source) (increase(conduit_authz_decisions_total[" + LOOKBACK + "]))",
                        List.of("decision", "resource_type", "source"))
        );
    }

    // ── Board 4 — Agent Performance ──────────────────────────────────────────────
    private List<PanelSpec> boardAgentPerformance() {
        return List.of(
                bars("latency_by_agent", "Avg latency by agent", "ms",
                        "sum by (agentId) (rate(conduit_agent_latency_seconds_sum[" + LOOKBACK + "]))"
                      + " / clamp_min(sum by (agentId) (rate(conduit_agent_latency_seconds_count[" + LOOKBACK + "])),0.0001) * 1000",
                        "agentId"),
                bars("selection_by_agent", "Selection frequency by agent", "count",
                        "sum by (agentId) (increase(conduit_resolver_selection_total[" + LOOKBACK + "]))", "agentId"),
                donut("calls_by_protocol", "Calls by protocol", "count",
                        "sum by (protocol) (increase(conduit_agent_calls_total[" + LOOKBACK + "]))", "protocol"),
                line("fanout_trend", "Fan-out avg (trend)", "ms",
                        "sum(rate(conduit_fanout_duration_seconds_sum[" + RATE_WIN + "]))"
                      + " / clamp_min(sum(rate(conduit_fanout_duration_seconds_count[" + RATE_WIN + "])),0.0001) * 1000"),
                table("agent_calls_table", "Agent call outcomes", "count",
                        "sum by (agentId, protocol, status) (increase(conduit_agent_calls_total[" + LOOKBACK + "]))",
                        List.of("agentId", "protocol", "status"))
        );
    }

    // ── Board 5 — Reliability / Resilience ───────────────────────────────────────
    private List<PanelSpec> boardReliability() {
        return List.of(
                stat("breakers_open", "Circuit breakers open", "count",
                        "count(conduit_circuit_breaker_state == 2) or vector(0)"),
                stat("error_rate", "Agent error rate", "%",
                        "(sum(increase(conduit_agent_calls_total{status!=\"OK\"}[" + LOOKBACK + "])) or vector(0))"
                      + " / clamp_min(sum(increase(conduit_agent_calls_total[" + LOOKBACK + "])),1) * 100"),
                stat("jvm_threads", "JVM live threads", "count",
                        "sum(jvm_threads_live_threads)"),
                bars("bulkhead_executing", "Bulkhead executing by agent", "count",
                        "sum by (agentId) (conduit_bulkhead_executing)", "agentId"),
                bars("bulkhead_queued", "Bulkhead queued by agent", "count",
                        "sum by (agentId) (conduit_bulkhead_queued)", "agentId"),
                table("breaker_states", "Circuit breaker states", "state",
                        "conduit_circuit_breaker_state", List.of("agentId"))
        );
    }

    // ── Board 6 — Live Trace (latency waterfall) ─────────────────────────────────
    // The live per-request glass-box trace streams over the existing /trace/** SSE endpoint
    // (Tempo-backed). This board renders the aggregate per-agent latency waterfall from
    // Prometheus so the board is self-contained; the UI's live gantt consumes /trace/**.
    private List<PanelSpec> boardLiveTrace() {
        return List.of(
                statNoData("trace_waterfall", "Per-agent latency waterfall", "ms", "waterfall",
                        s -> {
                            List<Map<String, Object>> rows = s.prom().instantRows(
                                    "sum by (agentId) (rate(conduit_agent_latency_seconds_sum[" + LOOKBACK + "]))"
                                  + " / clamp_min(sum by (agentId) (rate(conduit_agent_latency_seconds_count[" + LOOKBACK + "])),0.0001) * 1000",
                                    List.of("agentId"));
                            return rows.isEmpty()
                                    ? Panel.unavailable("trace_waterfall", "Per-agent latency waterfall", "waterfall", "ms")
                                    : Panel.table("trace_waterfall", "Per-agent latency waterfall", "waterfall", "ms", rows);
                        }),
                stat("fanout_p_now", "Avg fan-out (5m)", "ms",
                        "sum(rate(conduit_fanout_duration_seconds_sum[" + RATE_WIN + "]))"
                      + " / clamp_min(sum(rate(conduit_fanout_duration_seconds_count[" + RATE_WIN + "])),0.0001) * 1000"),
                bars("calls_by_agent_now", "Recent calls by agent", "count",
                        "sum by (agentId) (increase(conduit_agent_calls_total[1h]))", "agentId")
        );
    }

    // ── Board 7 — Cost & Quality (Langfuse) ──────────────────────────────────────
    private List<PanelSpec> boardCostQuality() {
        return List.of(
                langfuse("total_cost", "Model cost (30d)", "USD", "stat",
                        s -> asStat("total_cost", "Model cost (30d)", "USD", s.langfuse().totalCost(LF_DAYS))),
                langfuse("total_tokens", "Tokens (30d)", "count", "stat",
                        s -> asStat("total_tokens", "Tokens (30d)", "count", s.langfuse().totalTokens(LF_DAYS))),
                langfuse("total_traces", "Traces (30d)", "count", "stat",
                        s -> asStat("total_traces", "Traces (30d)", "count", s.langfuse().totalTraces(LF_DAYS))),
                langfuse("cost_by_day", "Cost over time", "USD", "line",
                        s -> asSeries("cost_by_day", "Cost over time", "line", "USD", s.langfuse().costByDay(LF_DAYS))),
                langfuse("tokens_by_day", "Token usage over time", "count", "area",
                        s -> asSeries("tokens_by_day", "Token usage over time", "area", "count", s.langfuse().tokensByDay(LF_DAYS))),
                langfuse("eval_scores", "Eval scores", "score", "bars",
                        s -> asCategorical("eval_scores", "Eval scores", "bars", "score", s.langfuse().evalScores(50)))
        );
    }

    // ── PanelSpec builders (Prometheus) ──────────────────────────────────────────

    private static PanelSpec stat(String id, String title, String unit, String promql) {
        return new PanelSpec(id, title, "stat", unit, s ->
                asStat(id, title, unit, s.prom().scalar(promql)));
    }

    private static PanelSpec donut(String id, String title, String unit, String promql, String labelKey) {
        return categoricalSpec(id, title, "donut", unit, promql, labelKey);
    }

    private static PanelSpec bars(String id, String title, String unit, String promql, String labelKey) {
        return categoricalSpec(id, title, "bars", unit, promql, labelKey);
    }

    private static PanelSpec categoricalSpec(String id, String title, String type, String unit,
                                             String promql, String labelKey) {
        return new PanelSpec(id, title, type, unit, s ->
                asCategorical(id, title, type, unit, s.prom().instant(promql, labelKey)));
    }

    private static PanelSpec area(String id, String title, String unit, String promql) {
        return seriesSpec(id, title, "area", unit, promql);
    }

    private static PanelSpec line(String id, String title, String unit, String promql) {
        return seriesSpec(id, title, "line", unit, promql);
    }

    private static PanelSpec seriesSpec(String id, String title, String type, String unit, String promql) {
        return new PanelSpec(id, title, type, unit, s ->
                asSeries(id, title, type, unit, s.prom().range(promql, RANGE_WIN, STEP)));
    }

    private static PanelSpec table(String id, String title, String unit, String promql, List<String> labelKeys) {
        return new PanelSpec(id, title, "table", unit, s -> {
            List<Map<String, Object>> rows = s.prom().instantRows(promql, labelKeys);
            return rows.isEmpty()
                    ? Panel.unavailable(id, title, "table", unit)
                    : Panel.table(id, title, "table", unit, rows);
        });
    }

    /** Escape hatch for panels with a bespoke producer (custom shaping / Langfuse). */
    private static PanelSpec statNoData(String id, String title, String unit, String type,
                                        Function<Sources, Panel> producer) {
        return new PanelSpec(id, title, type, unit, producer);
    }

    private static PanelSpec langfuse(String id, String title, String unit, String type,
                                      Function<Sources, Panel> producer) {
        return new PanelSpec(id, title, type, unit, producer);
    }

    // ── Shared shaping helpers (empty/NaN → unavailable, never fabricated) ────────

    private static Panel asStat(String id, String title, String unit, OptionalDouble v) {
        return v.isPresent()
                ? Panel.stat(id, title, unit, v.getAsDouble())
                : Panel.unavailable(id, title, "stat", unit);
    }

    private static Panel asCategorical(String id, String title, String type, String unit, List<LabeledValue> rows) {
        return rows.isEmpty()
                ? Panel.unavailable(id, title, type, unit)
                : Panel.categorical(id, title, type, unit, rows);
    }

    private static Panel asSeries(String id, String title, String type, String unit, List<Point> series) {
        return series.isEmpty()
                ? Panel.unavailable(id, title, type, unit)
                : Panel.series(id, title, type, unit, series);
    }
}
