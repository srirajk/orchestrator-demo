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
 * here is domain-specific (World B) — it reports tokens/eval names exactly as Langfuse stores them.
 *
 * <p><strong>Cost is computed here, not read from Langfuse.</strong> Langfuse tracks per-model
 * prompt/completion token counts regardless of whether its own model-pricing was seeded; this
 * source multiplies those token facts by {@link ModelPricing} config rates
 * ({@code registry/model-prices.json}) — {@code Σ (inputTokens × inputPerMillion + outputTokens ×
 * outputPerMillion) / 1e6} per model. So cost is real even on a fresh Langfuse whose {@code
 * totalCost} is 0 because the seed step never ran. Prices are never hardcoded in Java (World B);
 * a model absent from the config is priced at the {@code default} rate and flagged {@code estimated}.
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
    private final String groundingScoreName;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final ModelPricing pricing;

    private final ReentrantLock cacheLock = new ReentrantLock();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(JsonNode body, long expiresAt) {}

    public LangfuseMetricsSource(
            ObjectMapper mapper,
            ModelPricing pricing,
            @Value("${conduit.insights.langfuse-url:http://langfuse:3000}") String baseUrl,
            @Value("${conduit.insights.langfuse-public-key:}") String publicKey,
            @Value("${conduit.insights.langfuse-secret-key:}") String secretKey,
            @Value("${conduit.insights.per-query-timeout-ms:3000}") long perQueryTimeoutMs,
            @Value("${conduit.insights.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${conduit.insights.cache-ttl-seconds:20}") long cacheTtlSeconds,
            // The Langfuse trace name the gateway assigns to a chat turn (ChatService sets
            // langfuse.trace.name). Config, not a domain literal — it's the unit-economics
            // denominator (one chat turn = one "question"), excluding infra/eval/agent traces.
            @Value("${conduit.insights.chat-trace-name:chat-turn}") String chatTraceName,
            // The Langfuse score name the independent evaluator writes for answer-grounding.
            // Config, not a domain literal — it names an eval metric, not a business concept.
            @Value("${conduit.insights.grounding-score-name:grounding}") String groundingScoreName) {
        this.mapper = mapper;
        this.pricing = pricing;
        this.chatTraceName = chatTraceName;
        this.groundingScoreName = groundingScoreName;
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

    /**
     * Total model cost (USD) across the last {@code days} days — computed from the per-model token
     * usage the daily endpoint reports × {@link ModelPricing} config rates (NOT Langfuse's
     * pre-computed {@code totalCost}, which is 0 when its model-pricing was never seeded).
     */
    public OptionalDouble totalCost(int days) {
        JsonNode d = daily(days);
        if (d == null) return OptionalDouble.empty();
        double total = 0;
        for (JsonNode day : d.path("data")) {
            for (JsonNode u : day.path("usage")) total += costOfUsage(u);
        }
        return OptionalDouble.of(total);
    }

    /** Cost (USD) of one daily-endpoint usage row, from its model + input/output tokens × config. */
    private double costOfUsage(JsonNode usage) {
        String model = usage.path("model").asText(null);
        double in  = usage.path("inputUsage").asDouble(0);
        double out = usage.path("outputUsage").asDouble(0);
        return pricing.cost(model, in, out);
    }

    /** Trace count across the last {@code days} days. */
    public OptionalDouble totalTraces(int days) {
        JsonNode d = daily(days);
        if (d == null) return OptionalDouble.empty();
        double total = 0;
        for (JsonNode day : d.path("data")) total += day.path("countTraces").asDouble(0);
        return OptionalDouble.of(total);
    }

    /** Cost per day (chronological) for the last {@code days} days — computed from tokens × config. */
    public List<Point> costByDay(int days) { return byDay(days, true); }

    /** Token usage per day (chronological) for the last {@code days} days. */
    public List<Point> tokensByDay(int days) { return byDay(days, false); }

    private List<Point> byDay(int days, boolean cost) {
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
            double v = 0;
            for (JsonNode u : day.path("usage")) {
                // Cost is OUR computation (tokens × config), not Langfuse's zero-when-unseeded totalCost.
                v += cost ? costOfUsage(u) : u.path("totalUsage").asDouble(0);
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

    // ── Grounding score distribution + by-model breakdown ───────────────────────
    // The grounding score is the answer-grounding eval the independent judge writes per chat
    // turn. Its NAME is config ({@code grounding-score-name}); nothing here embeds a domain
    // concept. Both methods read the raw scores page and shape in-code; empty → empty list,
    // which the catalog renders as {@code unavailable} (never a fabricated bar).

    /**
     * Raw values of the configured grounding score across the most recent {@code limit} scores —
     * the input the catalog buckets into a distribution histogram. Only numeric values count.
     */
    public List<Double> groundingScores(int limit) {
        if (!enabled || groundingScoreName.isBlank()) return List.of();
        JsonNode j = cachedGet("/api/public/scores?limit=" + limit);
        if (j == null) return List.of();
        List<Double> out = new ArrayList<>();
        for (JsonNode s : j.path("data")) {
            if (!groundingScoreName.equals(s.path("name").asText())) continue;
            if (!s.path("value").isNumber()) continue;
            out.add(s.path("value").asDouble());
        }
        return out;
    }

    /**
     * Average grounding score grouped by the model that generated the answer — a real procurement
     * question ("is one model less trustworthy?"). The score is written at the trace level, so we
     * join each grounding score's {@code traceId} to that trace's generation model via a
     * {@code traceId → model} map built from the recent generation observations. A score whose
     * trace has no known generation model is skipped (never bucketed under a fabricated model).
     * Model names are read straight off Langfuse (World B) — never hardcoded here.
     */
    public List<LabeledValue> groundingByModel(int limit) {
        if (!enabled || groundingScoreName.isBlank()) return List.of();
        JsonNode scores = cachedGet("/api/public/scores?limit=" + limit);
        if (scores == null) return List.of();
        Map<String, String> modelByTrace = modelByTrace(limit);
        Map<String, double[]> agg = new LinkedHashMap<>(); // model -> [sum, count]
        for (JsonNode s : scores.path("data")) {
            if (!groundingScoreName.equals(s.path("name").asText())) continue;
            if (!s.path("value").isNumber()) continue;
            String model = modelByTrace.get(s.path("traceId").asText(""));
            if (model == null || model.isBlank()) continue;
            double[] a = agg.computeIfAbsent(model, k -> new double[2]);
            a[0] += s.path("value").asDouble(0);
            a[1] += 1;
        }
        List<LabeledValue> out = new ArrayList<>();
        agg.forEach((model, a) -> { if (a[1] > 0) out.add(new LabeledValue(model, a[0] / a[1])); });
        return out;
    }

    /**
     * {@code traceId → model name} of that trace's (first) generation observation, over the most
     * recent {@code limit} generations. Prefers Langfuse's {@code providedModelName}, falling back
     * to the resolved {@code model}. Non-throwing: any failure → empty map.
     */
    private Map<String, String> modelByTrace(int limit) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!enabled) return map;
        JsonNode j = cachedGet("/api/public/observations?type=GENERATION&limit=" + limit);
        if (j == null) return map;
        for (JsonNode o : j.path("data")) {
            String trace = o.path("traceId").asText(null);
            if (trace == null || trace.isBlank()) continue;
            String model = firstNonBlank(o.path("providedModelName").asText(null), o.path("model").asText(null));
            if (model != null) map.putIfAbsent(trace, model);
        }
        return map;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank() && !"null".equals(a)) return a;
        if (b != null && !b.isBlank() && !"null".equals(b)) return b;
        return null;
    }

    // ── Cost/token slicing via the Langfuse custom-metrics API ──────────────────
    // GET /api/public/metrics?query={view,metrics,dimensions,filters,from,to}. Lets the
    // insights module report cost & tokens grouped BY MODEL / BY USER / BY SEGMENT. Every
    // dimension is a Langfuse-native field (providedModelName / userId / trace tags); nothing
    // here is domain-specific (World B). Non-throwing: any failure → empty list.

    /**
     * One cost/tokens/count row for a slice (model, user, or segment). {@code costUsd} is computed
     * here from token counts × {@link ModelPricing} config; {@code estimated} is true when any
     * priced token in the slice used the config {@code default} fallback (model not in the config).
     */
    public record CostSlice(String label, double costUsd, double tokens, long count, boolean estimated) {}

    /**
     * Cost + tokens grouped by model name, over the last {@code days}. Cost is
     * {@code inputTokens × inputPerMillion + outputTokens × outputPerMillion) / 1e6} using the
     * config rate for that exact model; a model absent from the config is flagged {@code estimated}.
     */
    public List<CostSlice> costByModel(int days) {
        ObjectNode q = baseQuery("observations", days);
        costMetrics(q);
        q.putArray("dimensions").add(dimension("providedModelName"));
        JsonNode data = metricsGet(q);
        List<CostSlice> out = new ArrayList<>();
        if (data == null) return out;
        for (JsonNode r : data.path("data")) {
            String model = r.path("providedModelName").asText(null);
            if (model == null || model.isBlank() || "null".equals(model)) continue; // skip non-LLM aggregate
            double tokens = r.path("sum_totalTokens").asDouble(0);
            out.add(new CostSlice(model, costOf(r, model), tokens,
                    r.path("count_count").asLong(0), pricing.isEstimated(model)));
        }
        return out;
    }

    /** Cost + tokens grouped by principal ({@code user_id} on the trace), over the last {@code days}. */
    public List<CostSlice> costByUser(int days) {
        // Group by [principal × model] so each row can be priced at its model's config rate, then
        // aggregate up to the principal — cost cannot be priced on a userId-only aggregate.
        return aggregatedSlices("userId", days);
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
            // Group by model WITHIN the segment filter so each row is priced at its config rate.
            q.putArray("dimensions").add(dimension("providedModelName"));
            ArrayNode filters = q.putArray("filters");
            ObjectNode f = filters.addObject();
            f.put("column", "tags");
            f.put("operator", "any of");
            f.put("type", "arrayOptions");
            f.putArray("value").add(tag);
            JsonNode data = metricsGet(q);
            double cost = 0, tokens = 0; long count = 0; boolean estimated = false;
            if (data != null) {
                for (JsonNode r : data.path("data")) {
                    String model = r.path("providedModelName").asText(null);
                    double tk = r.path("sum_totalTokens").asDouble(0);
                    cost   += costOf(r, model);
                    tokens += tk;
                    count  += r.path("count_count").asLong(0);
                    if (tk > 0 && pricing.isEstimated(model)) estimated = true;
                }
            }
            out.add(new CostSlice(tag.substring("segment:".length()), cost, tokens, count, estimated));
        }
        return out;
    }

    /** Aggregate cost/tokens/question-count over the window (for totals + unit economics). */
    public CostSlice costTotals(int days) {
        ObjectNode q = baseQuery("observations", days);
        costMetrics(q);
        // Group by model so total cost is Σ per-model (tokens × config rate).
        q.putArray("dimensions").add(dimension("providedModelName"));
        JsonNode data = metricsGet(q);
        double cost = 0, tokens = 0; boolean estimated = false;
        if (data != null) {
            for (JsonNode r : data.path("data")) {
                String model = r.path("providedModelName").asText(null);
                double tk = r.path("sum_totalTokens").asDouble(0);
                cost   += costOf(r, model);
                tokens += tk;
                if (tk > 0 && pricing.isEstimated(model)) estimated = true;
            }
        }
        long questions = questionCount(days);
        return new CostSlice("total", cost, tokens, questions, estimated);
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

    /**
     * Group observations by {@code field × model}, price each row at its model's config rate, then
     * aggregate up to {@code field}. Needed because cost cannot be derived from a single-dimension
     * (field-only) aggregate — the model is what carries the price. Insertion order of the first
     * time a label appears is preserved.
     */
    private List<CostSlice> aggregatedSlices(String field, int days) {
        ObjectNode q = baseQuery("observations", days);
        costMetrics(q);
        ArrayNode dims = q.putArray("dimensions");
        dims.add(dimension(field));
        dims.add(dimension("providedModelName"));
        JsonNode data = metricsGet(q);
        // label -> [cost, tokens, count, estimatedFlag(0/1)]
        Map<String, double[]> agg = new LinkedHashMap<>();
        if (data != null) {
            for (JsonNode r : data.path("data")) {
                String label = r.path(field).asText(null);
                if (label == null || label.isBlank() || "null".equals(label)) continue; // skip null-principal
                String model = r.path("providedModelName").asText(null);
                double tk = r.path("sum_totalTokens").asDouble(0);
                double[] a = agg.computeIfAbsent(label, k -> new double[4]);
                a[0] += costOf(r, model);
                a[1] += tk;
                a[2] += r.path("count_count").asLong(0);
                if (tk > 0 && pricing.isEstimated(model)) a[3] = 1;
            }
        }
        List<CostSlice> out = new ArrayList<>();
        agg.forEach((label, a) -> out.add(new CostSlice(label, a[0], a[1], (long) a[2], a[3] == 1)));
        return out;
    }

    /** Cost of one metrics row: config rate for {@code model} × its summed input/output tokens. */
    private double costOf(JsonNode row, String model) {
        double in  = row.path("sum_inputTokens").asDouble(0);
        double out = row.path("sum_outputTokens").asDouble(0);
        return pricing.cost(model, in, out);
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
        // Token FACTS Langfuse tracks per observation, split input/output so we can price each side
        // ourselves. We deliberately do NOT request Langfuse's own `totalCost` — it is 0 on a
        // fresh/unseeded Langfuse; cost is computed here from these tokens × config (ModelPricing).
        metrics.add(measure("inputTokens", "sum"));
        metrics.add(measure("outputTokens", "sum"));
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
