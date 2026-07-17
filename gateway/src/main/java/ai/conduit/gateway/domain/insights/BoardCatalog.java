package ai.conduit.gateway.domain.insights;

import ai.conduit.gateway.domain.insights.model.LabeledValue;
import ai.conduit.gateway.domain.insights.model.Panel;
import ai.conduit.gateway.domain.insights.model.Point;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Declarative catalog of the 8 Insights boards and their panels, each bound to a PromQL query
 * (boards 1–6 + 8, ops — Prometheus) or a Langfuse query (board 7, cost/quality). One board = one
 * {@code GET /v1/insights/boards/{id}}.
 *
 * <h3>World B</h3>
 * Not one domain/agent/entity name appears here. Every "by X" breakdown aggregates on a metric
 * <em>label</em> ({@code agentId}, {@code outcome}, {@code decision}, {@code protocol},
 * {@code type}, {@code resource_type}, {@code role}, {@code agent_count}) whose values are
 * discovered dynamically from the series Prometheus returns. Onboarding a new domain adds label
 * values automatically; nothing here changes. The only literals are query windows and metric
 * names — infrastructure, not domain.
 *
 * <p>A panel whose metric has no data yet (e.g. a just-added counter, or Langfuse with no traces)
 * degrades to {@code status:"unavailable"} at render time rather than showing a fabricated value.
 */
@Component
public class BoardCatalog {

    // Query windows (infrastructure constants, not domain knowledge). The aggregation lookback
    // (increase()/rate() over trend/table/stat panels) is now the caller-selected {@link Range};
    // the short rate window and the range-query span/step below stay fixed.
    private static final String RATE_WIN    = "5m";           // rate() window for "over time" series
    private static final Duration RANGE_WIN = Duration.ofHours(6);   // range-query span
    private static final Duration STEP       = Duration.ofMinutes(15); // ~24 points over 6h

    // Board id → builder. Panels are built per request so the selected {@link Range} threads into
    // every lookback window; the shape/type of each panel is identical across ranges.
    private final Map<Integer, Function<Range, List<PanelSpec>>> boards = Map.of(
            1, this::boardOverview,
            2, this::boardTrafficIntent,
            3, this::boardGovernance,
            4, this::boardAgentPerformance,
            5, this::boardReliability,
            6, this::boardLiveTrace,
            7, this::boardCostQuality,
            8, this::boardOrchestration
    );

    /** Panels for a board id at the given {@link Range}, or {@code null} if the id is not 1–8. */
    public List<PanelSpec> panelsFor(int boardId, Range range) {
        Function<Range, List<PanelSpec>> builder = boards.get(boardId);
        return builder == null ? null : builder.apply(range);
    }

    public boolean exists(int boardId) {
        return boards.containsKey(boardId);
    }

    // ── Board 1 — Executive Overview ─────────────────────────────────────────────
    private List<PanelSpec> boardOverview(Range range) {
        String w = range.promWindow();
        return List.of(
                statDelta(range, "requests_24h", "Requests (24h)", "count",
                        "sum(increase(conduit_request_outcome_total[" + w + "]))"),
                statDelta(range, "answered_rate", "Answered rate", "%",
                        "sum(increase(conduit_request_outcome_total{outcome=\"ANSWERED\"}[" + w + "]))"
                      + " / clamp_min(sum(increase(conduit_request_outcome_total[" + w + "])),1) * 100"),
                statDelta(range, "agent_calls_24h", "Agent calls (24h)", "count",
                        "sum(increase(conduit_agent_calls_total[" + w + "]))"),
                // Pipeline p95 latency. Feeds the Overview "p95 latency" KPI (panel id retained
                // for the web contract). Computed off the agent-latency histogram so it is a real
                // p95, not an average: histogram_quantile needs `le` buckets spanning the observed
                // latencies, which conduit_agent_latency_seconds_bucket provides. The prior query
                // divided by clamp_min(rate(count),1); with a sub-1/s call rate the denominator was
                // pinned to the floor, collapsing the value toward 0 ms.
                stat("fanout_avg_ms", "P95 latency", "ms",
                        "histogram_quantile(0.95,"
                      + " sum(rate(conduit_agent_latency_seconds_bucket[" + w + "])) by (le)) * 1000"),
                area("request_volume", "Request volume", "req/s",
                        "sum(rate(conduit_request_outcome_total[" + RATE_WIN + "]))"),
                donut("outcome_mix", "Outcome mix", "count",
                        "sum by (outcome) (increase(conduit_request_outcome_total[" + w + "]))", "outcome")
        );
    }

    // ── Board 2 — Traffic & Intent ───────────────────────────────────────────────
    private List<PanelSpec> boardTrafficIntent(Range range) {
        String w = range.promWindow();
        return List.of(
                statDelta(range, "questions_24h", "Questions (24h)", "count",
                        "sum(increase(chat_intent_total[" + w + "]))"),
                // Pipeline latency (time-to-first-token): avg + p50/p95/p99 off the TTFT histogram.
                // NaN (no data yet) is dropped by the source → the panel degrades to unavailable.
                stat("ttft_avg", "TTFT avg", "s",
                        "sum(rate(conduit_ttft_seconds_sum[" + w + "]))"
                      + " / clamp_min(sum(rate(conduit_ttft_seconds_count[" + w + "])),0.0001)"),
                stat("ttft_p50", "TTFT p50", "s",
                        "histogram_quantile(0.50, sum(rate(conduit_ttft_seconds_bucket[" + w + "])) by (le))"),
                stat("ttft_p95", "TTFT p95", "s",
                        "histogram_quantile(0.95, sum(rate(conduit_ttft_seconds_bucket[" + w + "])) by (le))"),
                stat("ttft_p99", "TTFT p99", "s",
                        "histogram_quantile(0.99, sum(rate(conduit_ttft_seconds_bucket[" + w + "])) by (le))"),
                area("questions_over_time", "Questions over time", "q/s",
                        "sum(rate(chat_intent_total[" + RATE_WIN + "]))"),
                donut("intent_mix", "Intent mix", "count",
                        "sum by (type) (increase(chat_intent_total[" + w + "]))", "type"),
                bars("adoption_by_role", "Adoption by role", "count",
                        "sum by (role) (increase(conduit_adoption_total[" + w + "]))", "role")
        );
    }

    // ── Board 3 — Governance / Authorization ─────────────────────────────────────
    private List<PanelSpec> boardGovernance(Range range) {
        String w = range.promWindow();
        return List.of(
                statDelta(range, "allow_rate", "Allow rate", "%",
                        "sum(increase(conduit_authz_decisions_total{decision=\"ALLOW\"}[" + w + "]))"
                      + " / clamp_min(sum(increase(conduit_authz_decisions_total[" + w + "])),1) * 100"),
                statDelta(range, "decisions_24h", "Authorization checks (24h)", "count",
                        "sum(increase(conduit_authz_decisions_total[" + w + "]))"),
                // Coverage gaps: turns that ended in a clarification or an access denial — the
                // "we couldn't just answer" tail. Outcome labels are metric values, not domain
                // literals (World B).
                statDelta(range, "coverage_gaps", "Coverage gaps (clarify + denied)", "count",
                        "sum(increase(conduit_request_outcome_total{outcome=~\"CLARIFIED|FORM_CLARIFY|DENIED\"}[" + w + "]))"),
                donut("decision_mix", "Allow vs deny", "count",
                        "sum by (decision) (increase(conduit_authz_decisions_total[" + w + "]))", "decision"),
                bars("decisions_by_resource", "Checks by resource type", "count",
                        "sum by (resource_type) (increase(conduit_authz_decisions_total[" + w + "]))", "resource_type"),
                bars("denials_by_agent", "Denials by agent", "count",
                        "sum by (agentId) (increase(conduit_agent_denials_total[" + w + "]))", "agentId"),
                table("authz_ledger", "Authorization ledger", "count",
                        "sum by (decision, resource_type, source) (increase(conduit_authz_decisions_total[" + w + "]))",
                        List.of("decision", "resource_type", "source"))
        );
    }

    // ── Board 4 — Agent Performance ──────────────────────────────────────────────
    private List<PanelSpec> boardAgentPerformance(Range range) {
        String w = range.promWindow();
        return List.of(
                bars("latency_by_agent", "Avg latency by agent", "ms",
                        "sum by (agentId) (rate(conduit_agent_latency_seconds_sum[" + w + "]))"
                      + " / clamp_min(sum by (agentId) (rate(conduit_agent_latency_seconds_count[" + w + "])),0.0001) * 1000",
                        "agentId"),
                bars("selection_by_agent", "Selection frequency by agent", "count",
                        "sum by (agentId) (increase(conduit_resolver_selection_total[" + w + "]))", "agentId"),
                donut("calls_by_protocol", "Calls by protocol", "count",
                        "sum by (protocol) (increase(conduit_agent_calls_total[" + w + "]))", "protocol"),
                line("fanout_trend", "Fan-out avg (trend)", "ms",
                        "sum(rate(conduit_fanout_duration_seconds_sum[" + RATE_WIN + "]))"
                      + " / clamp_min(sum(rate(conduit_fanout_duration_seconds_count[" + RATE_WIN + "])),0.0001) * 1000"),
                table("agent_calls_table", "Agent call outcomes", "count",
                        "sum by (agentId, protocol, status) (increase(conduit_agent_calls_total[" + w + "]))",
                        List.of("agentId", "protocol", "status"))
        );
    }

    // ── Board 5 — Reliability / Resilience ───────────────────────────────────────
    // Instant gauges (breakers/threads/bulkheads) have no lookback window and are range-invariant;
    // only the error-rate ratio aggregates over the selected window.
    private List<PanelSpec> boardReliability(Range range) {
        String w = range.promWindow();
        return List.of(
                stat("breakers_open", "Circuit breakers open", "count",
                        "count(conduit_circuit_breaker_state == 2) or vector(0)"),
                stat("error_rate", "Agent error rate", "%",
                        "(sum(increase(conduit_agent_calls_total{status!=\"OK\"}[" + w + "])) or vector(0))"
                      + " / clamp_min(sum(increase(conduit_agent_calls_total[" + w + "])),1) * 100"),
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
    private List<PanelSpec> boardLiveTrace(Range range) {
        String w = range.promWindow();
        return List.of(
                stat("dag_share", "Multi-step share", "%",
                        "(sum(increase(conduit_dag_plan_total[" + w + "])) or vector(0))"
                      + " / clamp_min(sum(increase(chat_intent_total{type=\"FETCH_DATA\"}[" + w + "])),1) * 100"),
                bars("dag_node_count", "DAG node count", "count",
                        "sum by (node_count) (increase(conduit_dag_plan_total[" + w + "]))", "node_count"),
                bars("dag_fallbacks", "DAG fallbacks", "count",
                        "sum by (reason) (increase(conduit_dag_fallback_total[" + w + "]))", "reason"),
                statNoData("trace_waterfall", "Per-agent latency waterfall", "ms", "waterfall",
                        s -> {
                            List<Map<String, Object>> rows = s.prom().instantRows(
                                    "sum by (agentId) (rate(conduit_agent_latency_seconds_sum[" + w + "]))"
                                  + " / clamp_min(sum by (agentId) (rate(conduit_agent_latency_seconds_count[" + w + "])),0.0001) * 1000",
                                    List.of("agentId"));
                            return rows.isEmpty()
                                    ? Panel.unavailable("trace_waterfall", "Per-agent latency waterfall", "waterfall", "ms")
                                    : Panel.table("trace_waterfall", "Per-agent latency waterfall", "waterfall", "ms", rows);
                        }),
                // "now" panels are deliberately recent-window and stay fixed (like the 5m rate series).
                stat("fanout_p_now", "Avg fan-out (5m)", "ms",
                        "sum(rate(conduit_fanout_duration_seconds_sum[" + RATE_WIN + "]))"
                      + " / clamp_min(sum(rate(conduit_fanout_duration_seconds_count[" + RATE_WIN + "])),0.0001) * 1000"),
                bars("calls_by_agent_now", "Recent calls by agent", "count",
                        "sum by (agentId) (increase(conduit_agent_calls_total[1h]))", "agentId")
        );
    }

    // ── Board 7 — Cost & Quality (Langfuse + compaction telemetry) ───────────────
    // Score page size for the grounding panels (infrastructure constant, not domain knowledge).
    private static final int SCORE_PAGE = 100;
    // Grounding distribution buckets: SCORE_BINS equal bins over the [0,1] score range.
    private static final int SCORE_BINS = 10;

    private List<PanelSpec> boardCostQuality(Range range) {
        int days = range.langfuseDays();
        String w = range.promWindow();
        return List.of(
                langfuse("total_cost", "Model cost (30d)", "USD", "stat",
                        s -> asStat("total_cost", "Model cost (30d)", "USD", s.langfuse().totalCost(days))),
                langfuse("total_tokens", "Tokens (30d)", "count", "stat",
                        s -> asStat("total_tokens", "Tokens (30d)", "count", s.langfuse().totalTokens(days))),
                langfuse("total_traces", "Traces (30d)", "count", "stat",
                        s -> asStat("total_traces", "Traces (30d)", "count", s.langfuse().totalTraces(days))),
                langfuse("cost_by_day", "Cost over time", "USD", "line",
                        s -> asSeries("cost_by_day", "Cost over time", "line", "USD", s.langfuse().costByDay(days))),
                langfuse("tokens_by_day", "Token usage over time", "count", "area",
                        s -> asSeries("tokens_by_day", "Token usage over time", "area", "count", s.langfuse().tokensByDay(days))),
                langfuse("eval_scores", "Eval scores", "score", "bars",
                        s -> asCategorical("eval_scores", "Eval scores", "bars", "score", s.langfuse().evalScores(50))),
                // Grounding distribution: bucket the raw grounding scores into SCORE_BINS bins over
                // [0,1] and render as a histogram (bars). Empty scores → unavailable.
                langfuse("grounding_distribution", "Grounding distribution", "count", "bars",
                        s -> asCategorical("grounding_distribution", "Grounding distribution", "bars", "count",
                                histogram(s.langfuse().groundingScores(SCORE_PAGE), SCORE_BINS))),
                // Grounding by model: avg grounding score per generating model (procurement view).
                langfuse("grounding_by_model", "Grounding by model", "score", "bars",
                        s -> asCategorical("grounding_by_model", "Grounding by model", "bars", "score",
                                s.langfuse().groundingByModel(SCORE_PAGE))),
                // Memory compaction: summary-attached ratio + tokens saved, read from the BFF's
                // compaction counters as scraped into Prometheus (never a gateway→BFF call). These
                // are cumulative lifetime totals (range-invariant, like the reliability gauges).
                langfuse("compaction", "Memory compaction", "count", "table",
                        s -> compaction(s))
        );
    }

    /** Build the compaction panel from the BFF's Prometheus counters; empty → unavailable. */
    private Panel compaction(Sources s) {
        OptionalDouble events        = s.prom().scalar("sum(chat_compaction_context_messages_count)");
        OptionalDouble attachedTotal = s.prom().scalar("sum(chat_compaction_summary_attached_total)");
        OptionalDouble attachedTrue  = s.prom().scalar("sum(chat_compaction_summary_attached_total{attached=\"true\"})");
        OptionalDouble ctxMsgSum     = s.prom().scalar("sum(chat_compaction_context_messages_sum)");
        OptionalDouble fullTokens    = s.prom().scalar("sum(chat_compaction_tokens_sum{kind=\"full\"})");
        OptionalDouble ctxTokens     = s.prom().scalar("sum(chat_compaction_tokens_sum{kind=\"context\"})");

        // No compaction telemetry at all → graceful empty state (never a fabricated figure).
        if (events.isEmpty() && attachedTotal.isEmpty()) {
            return Panel.unavailable("compaction", "Memory compaction", "table", "count");
        }
        double evts   = events.orElse(0);
        double total  = attachedTotal.orElse(0);
        double attPct = total > 0 ? attachedTrue.orElse(0) / total * 100.0 : 0.0;
        double saved  = Math.max(0, fullTokens.orElse(0) - ctxTokens.orElse(0));
        double avgMsg = evts > 0 ? ctxMsgSum.orElse(0) / evts : 0.0;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("events", round1(evts));
        row.put("attachedPct", round1(attPct));
        row.put("tokensSaved", round1(saved));
        row.put("avgMessages", round1(avgMsg));
        return Panel.table("compaction", "Memory compaction", "table", "count", List.of(row));
    }

    /**
     * Bucket raw scores in [0,1] into {@code bins} equal-width bins, returning one
     * {@link LabeledValue} per bin ({@code label} = the bin's range, {@code value} = its count).
     * Every bin is emitted (including empty ones) so the histogram keeps its full 0→1 x-axis.
     * An empty input yields an empty list → the panel degrades to {@code unavailable}.
     */
    private static List<LabeledValue> histogram(List<Double> values, int bins) {
        if (values == null || values.isEmpty()) return List.of();
        int[] counts = new int[bins];
        for (double v : values) {
            double clamped = Math.max(0.0, Math.min(1.0, v));
            int idx = (int) (clamped * bins);
            if (idx >= bins) idx = bins - 1;   // 1.0 falls into the top bin
            counts[idx]++;
        }
        List<LabeledValue> out = new ArrayList<>(bins);
        for (int i = 0; i < bins; i++) {
            double lo = (double) i / bins;
            double hi = (double) (i + 1) / bins;
            String label = String.format("%.1f–%.1f", lo, hi);
            out.add(new LabeledValue(label, counts[i]));
        }
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // ── Board 8 — Orchestration (decision intelligence) ──────────────────────────
    // The gateway's routing brain, surfaced from meters it already emits (chat.intent,
    // conduit.request.outcome, conduit.fanout.duration, conduit.request.partial,
    // conduit.dag.plan). Every breakdown aggregates on a metric label whose values are
    // discovered from the series Prometheus returns — no domain/agent literal appears here.
    // A panel whose counter has not incremented yet degrades to "unavailable" (honest empty
    // state), never a fabricated figure.
    private List<PanelSpec> boardOrchestration(Range range) {
        String w = range.promWindow();
        return List.of(
                // Graceful-degradation rate: answered-from-a-partial-fan-out as a share of all
                // resolved requests. `or vector(0)` keeps the numerator defined before the first
                // partial fires; the whole panel is unavailable only when no request has resolved.
                statDelta(range, "degradation_rate", "Graceful-degradation rate", "%",
                        "(sum(increase(conduit_request_partial_total[" + w + "])) or vector(0))"
                      + " / clamp_min(sum(increase(conduit_request_outcome_total[" + w + "])),1) * 100"),
                // Multi-turn signal: FOLLOW_UP as a fraction of all classified intents.
                statDelta(range, "followup_share", "Follow-up share", "%",
                        "sum(increase(chat_intent_total{type=\"FOLLOW_UP\"}[" + w + "]))"
                      + " / clamp_min(sum(increase(chat_intent_total[" + w + "])),1) * 100"),
                // Composable-orchestration share: count of multi-step DAG plans that actually fired.
                // The counter only exists once a DAG has executed, so no traffic → unavailable.
                stat("dag_plans", "Multi-step plans executed", "count",
                        "sum(increase(conduit_dag_plan_total[" + w + "]))"),
                // Intent mix: FETCH_DATA / FOLLOW_UP / CLARIFY / CHITCHAT over the window.
                bars("intent_mix", "Intent mix", "count",
                        "sum by (type) (increase(chat_intent_total[" + w + "]))", "type"),
                // Outcome taxonomy: how requests resolve (label values are metric data, not domain
                // literals) — served, denied, clarified, failed.
                bars("outcome_taxonomy", "Outcome taxonomy", "count",
                        "sum by (outcome) (increase(conduit_request_outcome_total[" + w + "]))", "outcome"),
                // Fan-out shape: how many agents a question fans out to (1 vs 2 vs 3+), read off the
                // fan-out timer's per-agent_count sample count.
                bars("fanout_shape", "Fan-out shape (agents per question)", "count",
                        "sum by (agent_count) (increase(conduit_fanout_duration_seconds_count[" + w + "]))",
                        "agent_count")
        );
    }

    // ── PanelSpec builders (Prometheus) ──────────────────────────────────────────

    /**
     * A {@code stat} panel carrying a period-over-period {@code delta}: the same query evaluated
     * now vs one {@link Range} window earlier (Prometheus {@code time=} at now − window). The
     * delta is omitted when there is no prior-window value; the whole panel is unavailable when
     * the current value is absent. Instance method — it captures the request's {@link Range}.
     */
    private PanelSpec statDelta(Range range, String id, String title, String unit, String promql) {
        long windowSec = range.windowSeconds();
        return new PanelSpec(id, title, "stat", unit, s -> {
            OptionalDouble cur = s.prom().scalar(promql);
            if (cur.isEmpty()) return Panel.unavailable(id, title, "stat", unit);
            long priorEval = System.currentTimeMillis() / 1000L - windowSec;
            OptionalDouble prior = s.prom().scalarAt(promql, priorEval);
            return prior.isPresent()
                    ? Panel.stat(id, title, unit, cur.getAsDouble(), cur.getAsDouble() - prior.getAsDouble())
                    : Panel.stat(id, title, unit, cur.getAsDouble());
        });
    }

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
