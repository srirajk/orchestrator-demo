package ai.conduit.gateway.domain.insights;

import ai.conduit.gateway.domain.insights.model.Board;
import ai.conduit.gateway.domain.insights.model.Panel;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Structured, deadline-bounded, partial-tolerant fan-out engine for a board's panels — the
 * heart of Conduit Insights and where virtual threads earn their keep.
 *
 * <h3>Concurrency discipline (INSIGHTS-SPEC §"Virtual threads")</h3>
 * <ul>
 *   <li><b>Fan-out on virtual threads.</b> Every panel producer is submitted to a
 *       <em>dedicated</em> {@code newVirtualThreadPerTaskExecutor} owned by this component and
 *       isolated from the request/chat pipeline. One board = N cheap VTs, each parking on
 *       blocking Prometheus/Langfuse I/O — no reactive plumbing.</li>
 *   <li><b>Structured, deadline-bounded join.</b> {@link #render} opens a scope, forks all
 *       panels, then harvests each within a single board deadline (mirroring the proven
 *       {@code AgentHarness}/{@code FlatPlanExecutor} idiom in this repo). Stragglers are
 *       cancelled at the deadline — the board never hangs on one query.</li>
 *   <li><b>Partial-tolerant harvest.</b> A producer that throws, times out, or is shed under
 *       backpressure yields {@code status:"unavailable"} for THAT panel only; every survivor
 *       still renders. A failed panel never cancels its siblings.</li>
 *   <li><b>Bounded downstream concurrency.</b> A {@link Semaphore} caps concurrent <em>outbound</em>
 *       queries so a board refresh can't stampede Prometheus/Langfuse. VTs make waiting free;
 *       the upstream still needs backpressure.</li>
 *   <li><b>Per-query timeout via Resilience4j {@link TimeLimiter}.</b> Each producer is bounded
 *       independently and its future cancelled on timeout, in addition to the board deadline.</li>
 *   <li><b>No pinning.</b> No {@code synchronized} anywhere on the I/O path — the semaphore and
 *       the Langfuse cache's {@link java.util.concurrent.locks.ReentrantLock} keep carriers free
 *       (JEP-491-friendly).</li>
 * </ul>
 */
@Component
public class InsightsExecutor {

    private static final Logger log = LoggerFactory.getLogger(InsightsExecutor.class);

    /** Dedicated VT executor — isolated from the chat request pipeline's executors. */
    private final ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();

    /** Bounds concurrent OUTBOUND queries (backpressure to Prometheus/Langfuse). */
    private final Semaphore downstream;

    /** Per-query wall-clock bound, enforced by Resilience4j and used to size the panel timeout. */
    private final TimeLimiter perQuery;
    private final long perQueryTimeoutMs;

    /** Whole-board deadline: survivors up to here render, the rest go unavailable. */
    private final long boardDeadlineMs;

    /** How long a producer will wait for a downstream permit before shedding (→ unavailable). */
    private final long permitWaitMs;

    private final Sources sources;
    private final MeterRegistry meterRegistry;

    public InsightsExecutor(
            PrometheusMetricsSource prom,
            LangfuseMetricsSource langfuse,
            MeterRegistry meterRegistry,
            @Value("${conduit.insights.max-concurrent-queries:8}") int maxConcurrentQueries,
            @Value("${conduit.insights.per-query-timeout-ms:3000}") long perQueryTimeoutMs,
            @Value("${conduit.insights.board-deadline-ms:4000}") long boardDeadlineMs,
            @Value("${conduit.insights.permit-wait-ms:2000}") long permitWaitMs) {
        this.sources = new Sources(prom, langfuse);
        this.meterRegistry = meterRegistry;
        this.downstream = new Semaphore(maxConcurrentQueries);
        this.perQueryTimeoutMs = perQueryTimeoutMs;
        this.boardDeadlineMs = boardDeadlineMs;
        this.permitWaitMs = permitWaitMs;
        this.perQuery = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(perQueryTimeoutMs))
                .cancelRunningFuture(true)
                .build());
        log.info("InsightsExecutor: max-concurrent={} per-query-timeout={}ms board-deadline={}ms",
                maxConcurrentQueries, perQueryTimeoutMs, boardDeadlineMs);
    }

    /**
     * Render one board: fork every panel onto a virtual thread, then harvest within the board
     * deadline. Returns as many {@code ok} panels as completed in time; the rest are
     * {@code unavailable}. Never throws.
     */
    public Board render(int boardId, List<PanelSpec> specs) {
        long start = System.currentTimeMillis();
        Instant deadline = Instant.ofEpochMilli(start).plusMillis(boardDeadlineMs);

        // ── Fork: one VT per panel. Submission returns immediately. ──
        List<Map.Entry<PanelSpec, Future<Panel>>> forks = new ArrayList<>(specs.size());
        for (PanelSpec spec : specs) {
            forks.add(Map.entry(spec, vt.submit(() -> runGuarded(spec))));
        }

        // ── Join: harvest each panel within the remaining board deadline. ──
        List<Panel> panels = new ArrayList<>(specs.size());
        int ok = 0;
        for (Map.Entry<PanelSpec, Future<Panel>> fork : forks) {
            PanelSpec spec = fork.getKey();
            Future<Panel> f = fork.getValue();
            long remaining = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            try {
                Panel p = f.get(remaining, TimeUnit.MILLISECONDS);
                panels.add(p);
                if (Panel.OK.equals(p.status())) ok++;
            } catch (TimeoutException te) {
                f.cancel(true);                       // stop the straggler; harvest survivors
                panels.add(spec.unavailable());
            } catch (Exception e) {
                panels.add(spec.unavailable());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        Timer.builder("conduit.insights.board.render")
                .description("Insights board render time")
                .tag("board", Integer.toString(boardId))
                .register(meterRegistry)
                .record(elapsed, TimeUnit.MILLISECONDS);
        log.debug("Insights board {} rendered {}/{} panels ok in {}ms", boardId, ok, specs.size(), elapsed);
        return new Board(panels);
    }

    /**
     * Run one panel producer under backpressure + per-query timeout. Any failure mode collapses
     * to the panel's {@code unavailable} shell — the executor's harvest stays partial-tolerant.
     */
    private Panel runGuarded(PanelSpec spec) {
        boolean acquired = false;
        try {
            acquired = downstream.tryAcquire(permitWaitMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("Insights panel {} shed — no downstream permit within {}ms", spec.id(), permitWaitMs);
                return spec.unavailable();
            }
            // Resilience4j bounds the producer independently and cancels it on timeout. The work
            // runs on the same dedicated VT executor; the guarded VT parks on future.get, so the
            // downstream permit is correctly held for the query's lifetime.
            Panel p = perQuery.executeFutureSupplier(() -> CompletableFuture.supplyAsync(
                    () -> safeProduce(spec), vt));
            return p != null ? p : spec.unavailable();
        } catch (Exception e) {
            log.warn("Insights panel {} unavailable: {}", spec.id(), e.getMessage());
            return spec.unavailable();
        } finally {
            if (acquired) downstream.release();
        }
    }

    private Panel safeProduce(PanelSpec spec) {
        try {
            Panel p = spec.producer().apply(sources);
            return p != null ? p : spec.unavailable();
        } catch (Exception e) {
            log.warn("Insights panel {} producer error: {}", spec.id(), e.getMessage());
            return spec.unavailable();
        }
    }

    @PreDestroy
    void shutdown() {
        vt.shutdown();
    }
}
