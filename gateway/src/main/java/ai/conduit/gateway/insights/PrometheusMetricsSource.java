package ai.conduit.gateway.insights;

import ai.conduit.gateway.insights.model.LabeledValue;
import ai.conduit.gateway.insights.model.Point;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * {@link MetricsSource} over the Prometheus HTTP API (PromQL).
 *
 * <p>Uses a <strong>blocking</strong> {@link java.net.http.HttpClient}: each query is issued
 * from a virtual thread that simply parks on the socket read, so a slow Prometheus costs a
 * few KB of parked VT, not a platform thread. A per-request {@code .timeout(...)} bounds every
 * call independently (the per-query timeout of INSIGHTS-SPEC §VT).
 *
 * <p><strong>World B:</strong> this class hardcodes zero domain knowledge. "By domain" / "by
 * agent" panels are produced by grouping on a metric <em>label</em> ({@code agentId},
 * {@code outcome}, {@code decision}, {@code protocol}) whose values are read dynamically off
 * the series returned by Prometheus — there is no domain/agent/entity list in the source.
 *
 * <p>Every query method is non-throwing: a failure logs and returns empty, which the executor
 * renders as {@code status:"unavailable"}. {@code NaN}/{@code Inf} values are dropped.
 */
@Component
public class PrometheusMetricsSource implements MetricsSource {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsSource.class);

    private final String baseUrl;
    private final Duration perQueryTimeout;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public PrometheusMetricsSource(
            ObjectMapper mapper,
            @Value("${conduit.insights.prometheus-url:http://prometheus:9090}") String baseUrl,
            @Value("${conduit.insights.per-query-timeout-ms:3000}") long perQueryTimeoutMs,
            @Value("${conduit.insights.connect-timeout-ms:2000}") long connectTimeoutMs) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.perQueryTimeout = Duration.ofMillis(perQueryTimeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        log.info("PrometheusMetricsSource: url={} per-query-timeout={}ms", this.baseUrl, perQueryTimeoutMs);
    }

    @Override public String id() { return "prometheus"; }

    @Override
    public boolean isHealthy() {
        try {
            HttpResponse<String> r = get("/-/healthy", perQueryTimeout);
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Instant scalar ────────────────────────────────────────────────────────

    /** First value of an instant query, dropping NaN/Inf. Empty when no finite result. */
    public OptionalDouble scalar(String promql) {
        List<LabeledValue> vs = instant(promql, null);
        return vs.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(vs.get(0).value());
    }

    // ── Instant vector grouped by one label ─────────────────────────────────────

    /**
     * Instant query returning one {@link LabeledValue} per series. When {@code labelKey} is
     * non-null the label is that series' value for the label; otherwise the label is empty.
     * Values that are NaN/Inf are dropped so panels never render a fabricated figure.
     */
    public List<LabeledValue> instant(String promql, String labelKey) {
        List<LabeledValue> out = new ArrayList<>();
        try {
            JsonNode data = query("/api/v1/query", "query=" + enc(promql)).path("data");
            if (!"vector".equals(data.path("resultType").asText())) {
                // scalar result type: {"resultType":"scalar","result":[ts, "value"]}
                JsonNode res = data.path("result");
                if (res.isArray() && res.size() == 2) {
                    double v = parse(res.get(1).asText());
                    if (finite(v)) out.add(new LabeledValue("", v));
                }
                return out;
            }
            for (JsonNode series : data.path("result")) {
                double v = parse(series.path("value").get(1).asText());
                if (!finite(v)) continue;
                String label = labelKey == null ? "" : series.path("metric").path(labelKey).asText("");
                out.add(new LabeledValue(label, v));
            }
        } catch (Exception e) {
            log.warn("Prometheus instant query failed [{}]: {}", promql, e.getMessage());
        }
        return out;
    }

    /**
     * Instant query returning, per series, the selected label values plus the numeric value
     * under the {@code "value"} key. Used to build {@code table}/{@code waterfall} rows. Label
     * keys are supplied by the panel spec and echoed from the metric — no hardcoded values.
     */
    public List<Map<String, Object>> instantRows(String promql, List<String> labelKeys) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (JsonNode series : query("/api/v1/query", "query=" + enc(promql)).path("data").path("result")) {
                double v = parse(series.path("value").get(1).asText());
                if (!finite(v)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                for (String k : labelKeys) row.put(k, series.path("metric").path(k).asText(""));
                row.put("value", round(v));
                out.add(row);
            }
        } catch (Exception e) {
            log.warn("Prometheus instant-rows query failed [{}]: {}", promql, e.getMessage());
        }
        return out;
    }

    // ── Range vector (single aggregated series) ─────────────────────────────────

    /** Range query over {@code window} ending now, sampled every {@code step}; first series. */
    public List<Point> range(String promql, Duration window, Duration step) {
        List<Point> out = new ArrayList<>();
        try {
            long end = System.currentTimeMillis() / 1000L;
            long start = end - window.toSeconds();
            String q = "query=" + enc(promql)
                    + "&start=" + start + "&end=" + end
                    + "&step=" + Math.max(1, step.toSeconds());
            JsonNode result = query("/api/v1/query_range", q).path("data").path("result");
            if (result.isArray() && result.size() > 0) {
                for (JsonNode pt : result.get(0).path("values")) {
                    double v = parse(pt.get(1).asText());
                    if (finite(v)) out.add(new Point(pt.get(0).asLong(), round(v)));
                }
            }
        } catch (Exception e) {
            log.warn("Prometheus range query failed [{}]: {}", promql, e.getMessage());
        }
        return out;
    }

    // ── HTTP plumbing ───────────────────────────────────────────────────────────

    private JsonNode query(String path, String form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(perQueryTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Prometheus HTTP " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        if (!"success".equals(root.path("status").asText())) {
            throw new IllegalStateException("Prometheus status=" + root.path("status").asText());
        }
        return root;
    }

    private HttpResponse<String> get(String path, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path)).timeout(timeout).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static double parse(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; }
    }

    private static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    private static double round(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
