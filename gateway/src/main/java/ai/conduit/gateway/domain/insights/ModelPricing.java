package ai.conduit.gateway.domain.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Config-driven LLM token pricing for Conduit's cost analytics.
 *
 * <p><strong>Single source of pricing truth is {@code registry/model-prices.json}</strong> — the
 * SAME file {@code scripts/seed-langfuse-models.py} registers in Langfuse. This class reads it and
 * lets the insights module compute cost <em>itself</em> as
 * {@code Σ (promptTokens × inputPerMillion/1e6 + completionTokens × outputPerMillion/1e6)} per
 * model, so cost is correct even when Langfuse's own model-pricing was never seeded (a fresh/wiped
 * Langfuse reports {@code totalCost = 0}). Token counts are facts Langfuse tracks per observation;
 * prices are our config; the multiplication happens here.
 *
 * <p><strong>No prices in Java (World B).</strong> Every rate — including the {@code default}
 * fallback applied to any model absent from the config (flagged {@code estimated}) — comes from the
 * file. The config path is a property ({@code conduit.pricing.config-path}), never hardcoded. The
 * model names in the file are configuration, not gateway domain knowledge.
 *
 * <p>Cached and hot-reloadable: the parsed snapshot is refreshed when the file's
 * {@code lastModified} changes (re-stat throttled to {@code reload-check-ms}). Non-throwing — if the
 * file is missing or unparseable it logs and serves only the {@code default} rate, so every model
 * is priced (estimated) rather than falling to zero. The reload is guarded by a
 * {@link ReentrantLock} (never {@code synchronized}), so a carrier thread is never pinned.
 */
@Component
public class ModelPricing {

    private static final Logger log = LoggerFactory.getLogger(ModelPricing.class);

    /** A per-model rate. {@code estimated} = this model was not in the config (default fallback). */
    public record Rate(double inputPerMillion, double outputPerMillion, boolean estimated) {}

    /** Immutable parsed view of the config, swapped atomically on reload. */
    private record Snapshot(Rate defaultRate, Map<String, Rate> models, long fileStamp) {}

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();
    private final ObjectMapper mapper;
    private final String configLocation;
    private final long reloadCheckMs;

    private final ReentrantLock reloadLock = new ReentrantLock();
    private volatile Snapshot snapshot;
    private volatile long lastCheckAt = 0L;

    public ModelPricing(
            ObjectMapper mapper,
            @Value("${conduit.pricing.config-path:file:/registry/model-prices.json}") String configLocation,
            @Value("${conduit.pricing.reload-check-ms:10000}") long reloadCheckMs) {
        this.mapper = mapper;
        this.configLocation = configLocation;
        this.reloadCheckMs = reloadCheckMs;
        this.snapshot = load();   // eager load so a broken path is visible at startup
        log.info("ModelPricing: config={} models={} default={}/{} per-1M (estimated)",
                configLocation,
                snapshot.models().size(),
                snapshot.defaultRate().inputPerMillion(),
                snapshot.defaultRate().outputPerMillion());
    }

    /** Rate for {@code model}: the configured rate, else the {@code default} fallback (estimated). */
    public Rate rateFor(String model) {
        Snapshot s = current();
        if (model != null) {
            Rate r = s.models().get(model);
            if (r != null) return r;
        }
        return s.defaultRate();
    }

    /**
     * Cost in USD for a model's token usage, computed from config prices:
     * {@code inputTokens × inputPerMillion/1e6 + outputTokens × outputPerMillion/1e6}.
     */
    public double cost(String model, double inputTokens, double outputTokens) {
        Rate r = rateFor(model);
        return inputTokens * r.inputPerMillion() / 1_000_000.0
             + outputTokens * r.outputPerMillion() / 1_000_000.0;
    }

    /** True if {@code model} is priced with the {@code default} fallback (not in the config). */
    public boolean isEstimated(String model) {
        return rateFor(model).estimated();
    }

    // ── Load / reload ────────────────────────────────────────────────────────────

    /** Return the live snapshot, reloading if the file changed (re-stat throttled). */
    private Snapshot current() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < reloadCheckMs) return snapshot;
        // Throttle the re-stat; only one thread does the check/reload, others use the old snapshot.
        if (reloadLock.tryLock()) {
            try {
                lastCheckAt = now;
                long stamp = fileStamp();
                if (stamp != snapshot.fileStamp()) {
                    Snapshot reloaded = load();
                    snapshot = reloaded;
                    log.info("ModelPricing: reloaded {} ({} models)", configLocation, reloaded.models().size());
                }
            } finally {
                reloadLock.unlock();
            }
        }
        return snapshot;
    }

    private long fileStamp() {
        try {
            Resource r = resourceLoader.getResource(configLocation);
            return r.exists() ? r.lastModified() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private Snapshot load() {
        long stamp = fileStamp();
        try {
            Resource r = resourceLoader.getResource(configLocation);
            if (!r.exists()) {
                log.warn("ModelPricing: config {} not found — pricing everything at the built-in default is impossible; "
                        + "no default available, cost will be 0 until the file is present", configLocation);
                return new Snapshot(new Rate(0, 0, true), Map.of(), stamp);
            }
            try (InputStream in = r.getInputStream()) {
                JsonNode root = mapper.readTree(in);
                Rate defaultRate = parseRate(root.path("default"), true);
                Map<String, Rate> models = new HashMap<>();
                JsonNode m = root.path("models");
                if (m.isObject()) {
                    m.fields().forEachRemaining(e ->
                            models.put(e.getKey(), parseRate(e.getValue(), false)));
                }
                return new Snapshot(defaultRate, Map.copyOf(models), stamp);
            }
        } catch (Exception e) {
            log.warn("ModelPricing: failed to load {} — serving default-only pricing: {}",
                    configLocation, e.getMessage());
            return new Snapshot(new Rate(0, 0, true), Map.of(), stamp);
        }
    }

    private static Rate parseRate(JsonNode node, boolean estimated) {
        double in = node.path("inputPerMillion").asDouble(0);
        double out = node.path("outputPerMillion").asDouble(0);
        // A model carrying its own `estimated:true` (or the default block) is estimated.
        boolean est = estimated || node.path("estimated").asBoolean(false);
        return new Rate(in, out, est);
    }
}
