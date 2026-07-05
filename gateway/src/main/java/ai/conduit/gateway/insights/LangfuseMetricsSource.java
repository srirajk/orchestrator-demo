package ai.conduit.gateway.insights;

import ai.conduit.gateway.insights.model.LabeledValue;
import ai.conduit.gateway.insights.model.Point;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link MetricsSource} over the Langfuse public API (cost, token usage, eval scores).
 *
 * <p>Blocking {@link java.net.http.HttpClient} on a virtual thread, Basic-auth with the project
 * public/secret keys (config, never hardcoded). Langfuse is comparatively slow and its numbers
 * move on the minute scale, so responses are held in a <strong>short-TTL cache</strong>
 * (INSIGHTS-SPEC §VT, 15–30s) to keep board 7 snappy and shield Langfuse from board refreshes.
 *
 * <p><strong>No pinning:</strong> the cache is guarded by a {@link ReentrantLock} (never
 * {@code synchronized}), and the lock is <em>released before</em> any network I/O — the blocking
 * HTTP call always runs outside the monitor, so a carrier thread is never pinned. A rare
 * concurrent double-fetch is harmless (idempotent GET).
 *
 * <p>Non-throwing: any failure returns empty and the panel renders {@code unavailable}. Nothing
 * here is domain-specific (World B) — it reports cost/tokens/eval names exactly as Langfuse
 * stores them.
 */
@Component
public class LangfuseMetricsSource implements MetricsSource {

    private static final Logger log = LoggerFactory.getLogger(LangfuseMetricsSource.class);

    private final String baseUrl;
    private final String authHeader;
    private final boolean enabled;
    private final Duration perQueryTimeout;
    private final long ttlMillis;
    private final String chatTraceName;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private final ReentrantLock cacheLock = new ReentrantLock();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(JsonNode body, long expiresAt) {}

    public LangfuseMetricsSource(
            ObjectMapper mapper,
            @Value("${conduit.insights.langfuse-url:http://langfuse:3000}") String baseUrl,
            @Value("${conduit.insights.langfuse-public-key:}") String publicKey,
            @Value("${conduit.insights.langfuse-secret-key:}") String secretKey,
            @Value("${conduit.insights.per-query-timeout-ms:3000}") long perQueryTimeoutMs,
            @Value("${conduit.insights.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${conduit.insights.cache-ttl-seconds:20}") long cacheTtlSeconds,
            // The Langfuse trace name the gateway assigns to a chat turn (ChatService sets
            // langfuse.trace.name). Config, not a domain literal — it's the unit-economics
            // denominator (one chat turn = one "question"), excluding infra/eval/agent traces.
            @Value("${conduit.insights.chat-trace-name:chat-turn}") String chatTraceName) {
        this.mapper = mapper;
        this.chatTraceName = chatTraceName;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.enabled = !publicKey.isBlank() && !secretKey.isBlank();
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        this.perQueryTimeout = Duration.ofMillis(perQueryTimeoutMs);
        this.ttlMillis = cacheTtlSeconds * 1000L;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        log.info("LangfuseMetricsSource: url={} enabled={} cache-ttl={}s", this.baseUrl, enabled, cacheTtlSeconds);
    }

    @Override public String id() { return "langfuse"; }

    @Override
    public boolean isHealthy() {
        if (!enabled) return false;
        JsonNode j = cachedGet("/api/public/health");
        return j != null;
    }

    // ── Derived metrics for board 7 ─────────────────────────────────────────────

    private JsonNode daily(int days) {
        if (!enabled) return null;
        return cachedGet("/api/public/metrics/daily?limit=" + days);
    }

    /** Total token usage across the last {@code days} days. */
    public OptionalDouble totalTokens(int days) {
        JsonNode d = daily(days);
        if (d == null) return OptionalDouble.empty();
        double total = 0;
        for (JsonNode day : d.path("data")) {
            for (JsonNode u : day.path("usage")) total += u.path("totalUsage").asDouble(0);
        }
        return OptionalDouble.of(total);
    }

    /** Total model cost (USD) across the last {@code days} days. */
    public OptionalDouble totalCost(int days) {
        JsonNode d = daily(days);
        if (d == null) return OptionalDouble.empty();
        double total = 0;
        for (JsonNode day : d.path("data")) total += day.path("totalCost").asDouble(0);
        return OptionalDouble.of(total);
    }

    /** Trace count across the last {@code days} days. */
    public OptionalDouble totalTraces(int days) {
        JsonNode d = daily(days);
        if (d == null) return OptionalDouble.empty();
        double total = 0;
        for (JsonNode day : d.path("data")) total += day.path("countTraces").asDouble(0);
        return OptionalDouble.of(total);
    }

    /** Cost per day (chronological) for the last {@code days} days. */
    public List<Point> costByDay(int days) { return byDay(days, "totalCost", true); }

    /** Token usage per day (chronological) for the last {@code days} days. */
    public List<Point> tokensByDay(int days) { return byDay(days, null, false); }

    private List<Point> byDay(int days, String field, boolean cost) {
        List<Point> out = new ArrayList<>();
        JsonNode d = daily(days);
        if (d == null) return out;
        // Langfuse returns most-recent-first; reverse for a chronological series.
        List<JsonNode> rows = new ArrayList<>();
        d.path("data").forEach(rows::add);
        for (int i = rows.size() - 1; i >= 0; i--) {
            JsonNode day = rows.get(i);
            long epoch = epochOf(day.path("date").asText(null));
            if (epoch < 0) continue;
            double v;
            if (cost) {
                v = day.path(field).asDouble(0);
            } else {
                v = 0;
                for (JsonNode u : day.path("usage")) v += u.path("totalUsage").asDouble(0);
            }
            out.add(new Point(epoch, v));
        }
        return out;
    }

    /** Average eval score per score name (e.g. groundedness/faithfulness/relevancy). */
    public List<LabeledValue> evalScores(int limit) {
        if (!enabled) return List.of();
        JsonNode j = cachedGet("/api/public/scores?limit=" + limit);
        if (j == null) return List.of();
        Map<String, double[]> agg = new LinkedHashMap<>(); // name -> [sum, count]
        for (JsonNode s : j.path("data")) {
            if (!s.path("value").isNumber()) continue;   // only numeric scores aggregate
            String name = s.path("name").asText("score");
            double[] a = agg.computeIfAbsent(name, k -> new double[2]);
            a[0] += s.path("value").asDouble(0);
            a[1] += 1;
        }
        List<LabeledValue> out = new ArrayList<>();
        agg.forEach((name, a) -> { if (a[1] > 0) out.add(new LabeledValue(name, a[0] / a[1])); });
        return out;
    }

    // ── Cost/token slicing via the Langfuse custom-metrics API ──────────────────
    // GET /api/public/metrics?query={view,metrics,dimensions,filters,from,to}. Lets the
    // insights module report cost & tokens grouped BY MODEL / BY USER / BY SEGMENT. Every
    // dimension is a Langfuse-native field (providedModelName / userId / trace tags); nothing
    // here is domain-specific (World B). Non-throwing: any failure → empty list.

    /** One cost/tokens/count row for a slice (model, user, or segment). */
    public record CostSlice(String label, double costUsd, double tokens, long count) {}

    /** Cost + tokens grouped by model name, over the last {@code days}. */
    public List<CostSlice> costByModel(int days) {
        return slicesByDimension("providedModelName", days);
    }

    /** Cost + tokens grouped by principal ({@code user_id} on the trace), over the last {@code days}. */
    public List<CostSlice> costByUser(int days) {
        return slicesByDimension("userId", days);
    }

    /**
     * Cost + tokens grouped by business segment — discovered from the {@code segment:*} trace
     * tags present in the window, then one filtered aggregate per segment. Segment names are read
     * off the tags, never hardcoded (World B).
     */
    public List<CostSlice> costBySegment(int days) {
        List<CostSlice> out = new ArrayList<>();
        for (String tag : distinctTags("segment:", days)) {
            ObjectNode q = baseQuery("observations", days);
            costMetrics(q);
            q.putArray("dimensions");
            ArrayNode filters = q.putArray("filters");
            ObjectNode f = filters.addObject();
            f.put("column", "tags");
            f.put("operator", "any of");
            f.put("type", "arrayOptions");
            f.putArray("value").add(tag);
            JsonNode data = metricsGet(q);
            double cost = 0, tokens = 0; long count = 0;
            if (data != null) {
                for (JsonNode r : data.path("data")) {
                    cost   += r.path("sum_totalCost").asDouble(0);
                    tokens += r.path("sum_totalTokens").asDouble(0);
                    count  += r.path("count_count").asLong(0);
                }
            }
            out.add(new CostSlice(tag.substring("segment:".length()), cost, tokens, count));
        }
        return out;
    }

    /** Aggregate cost/tokens/question-count over the window (for totals + unit economics). */
    public CostSlice costTotals(int days) {
        ObjectNode q = baseQuery("observations", days);
        costMetrics(q);
        q.putArray("dimensions");
        JsonNode data = metricsGet(q);
        double cost = 0, tokens = 0;
        if (data != null) {
            for (JsonNode r : data.path("data")) {
                cost   += r.path("sum_totalCost").asDouble(0);
                tokens += r.path("sum_totalTokens").asDouble(0);
            }
        }
        long questions = questionCount(days);
        return new CostSlice("total", cost, tokens, questions);
    }

    /** Chat-turn trace count over the window — the denominator for unit economics. */
    public long questionCount(int days) {
        ObjectNode q = baseQuery("traces", days);
        q.putArray("metrics").add(measure("count", "count"));
        q.putArray("dimensions");
        if (chatTraceName != null && !chatTraceName.isBlank()) {
            ObjectNode f = q.putArray("filters").addObject();
            f.put("column", "name");
            f.put("operator", "=");
            f.put("type", "string");
            f.put("value", chatTraceName);
        }
        JsonNode data = metricsGet(q);
        long total = 0;
        if (data != null) for (JsonNode r : data.path("data")) total += r.path("count_count").asLong(0);
        return total;
    }

    private List<CostSlice> slicesByDimension(String field, int days) {
        ObjectNode q = baseQuery("observations", days);
        costMetrics(q);
        q.putArray("dimensions").add(dimension(field));
        JsonNode data = metricsGet(q);
        List<CostSlice> out = new ArrayList<>();
        if (data == null) return out;
        for (JsonNode r : data.path("data")) {
            String label = r.path(field).asText(null);
            if (label == null || label.isBlank() || "null".equals(label)) continue; // skip the non-LLM aggregate row
            out.add(new CostSlice(label,
                    r.path("sum_totalCost").asDouble(0),
                    r.path("sum_totalTokens").asDouble(0),
                    r.path("count_count").asLong(0)));
        }
        return out;
    }

    /** Distinct trace tags starting with {@code prefix} in the window (e.g. {@code segment:*}). */
    private List<String> distinctTags(String prefix, int days) {
        ObjectNode q = baseQuery("traces", days);
        q.putArray("metrics").add(measure("count", "count"));
        q.putArray("dimensions").add(dimension("tags"));
        JsonNode data = metricsGet(q);
        Set<String> set = new LinkedHashSet<>();
        if (data != null) {
            for (JsonNode row : data.path("data")) {
                JsonNode tags = row.path("tags");
                if (tags.isArray()) {
                    for (JsonNode t : tags) {
                        String s = t.asText("");
                        if (s.startsWith(prefix)) set.add(s);
                    }
                }
            }
        }
        return new ArrayList<>(set);
    }

    private ObjectNode baseQuery(String view, int days) {
        ObjectNode q = mapper.createObjectNode();
        q.put("view", view);
        Instant now = Instant.now();
        q.put("fromTimestamp", now.minus(Duration.ofDays(Math.max(1, days))).toString());
        q.put("toTimestamp", now.toString());
        return q;
    }

    private void costMetrics(ObjectNode q) {
        ArrayNode metrics = q.putArray("metrics");
        metrics.add(measure("totalCost", "sum"));
        metrics.add(measure("totalTokens", "sum"));
        metrics.add(measure("count", "count"));
    }

    private ObjectNode measure(String measure, String aggregation) {
        ObjectNode m = mapper.createObjectNode();
        m.put("measure", measure);
        m.put("aggregation", aggregation);
        return m;
    }

    private ObjectNode dimension(String field) {
        ObjectNode d = mapper.createObjectNode();
        d.put("field", field);
        return d;
    }

    private JsonNode metricsGet(ObjectNode query) {
        if (!enabled) return null;
        String enc = URLEncoder.encode(query.toString(), StandardCharsets.UTF_8);
        return cachedGet("/api/public/metrics?query=" + enc);
    }

    // ── Cache + HTTP ────────────────────────────────────────────────────────────

    /**
     * TTL-cached GET. The lock guards only the map read/expiry check and the write-back; the
     * blocking HTTP fetch happens with the lock RELEASED, so a carrier is never pinned across
     * I/O. Returns {@code null} on any failure (→ panel unavailable).
     */
    private JsonNode cachedGet(String path) {
        long now = System.currentTimeMillis();

        cacheLock.lock();
        try {
            CacheEntry hit = cache.get(path);
            if (hit != null && hit.expiresAt > now) return hit.body;
        } finally {
            cacheLock.unlock();
        }

        JsonNode fetched = fetch(path);   // I/O OUTSIDE the lock — no pinning
        if (fetched == null) return null;

        cacheLock.lock();
        try {
            cache.put(path, new CacheEntry(fetched, System.currentTimeMillis() + ttlMillis));
        } finally {
            cacheLock.unlock();
        }
        return fetched;
    }

    private JsonNode fetch(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(perQueryTimeout)
                    .header("Authorization", authHeader)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Langfuse HTTP {} for {}", resp.statusCode(), path);
                return null;
            }
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            log.warn("Langfuse fetch failed [{}]: {}", path, e.getMessage());
            return null;
        }
    }

    private static long epochOf(String isoDate) {
        try {
            return LocalDate.parse(isoDate).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        } catch (Exception e) {
            return -1;
        }
    }
}
