package ai.conduit.gateway.infrastructure.tenancy;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Timed, out-of-band client that keeps the {@link SnapshotProvisionedTenantDirectory} fresh from a
 * {@link TenantSnapshotSource} (Axiom A2, contract #4).
 *
 * <p>Fetches an initial snapshot synchronously at startup so the directory is populated (and readiness
 * can go up) before any request is served, then re-fetches on a background daemon thread at the
 * configured cadence and swaps atomically. Never touches the request path. If the initial fetch fails,
 * no snapshot is installed — the directory stays empty and the readiness probe stays down, which is the
 * intended fail-closed behaviour (the gateway is not ready without an initial snapshot).
 */
@Component
public class TenantDirectorySnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(TenantDirectorySnapshotClient.class);

    private final TenantSnapshotSource source;
    private final SnapshotProvisionedTenantDirectory directory;
    private final long refreshIntervalMs;

    private ScheduledExecutorService scheduler;

    public TenantDirectorySnapshotClient(
            TenantSnapshotSource source,
            SnapshotProvisionedTenantDirectory directory,
            @Value("${conduit.tenancy.snapshot.refresh-interval-ms:60000}") long refreshIntervalMs) {
        this.source = source;
        this.directory = directory;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    @PostConstruct
    void start() {
        // Initial fetch is synchronous: the directory must be populated before we accept traffic.
        // A failure here is intentionally not swallowed into a default — the directory stays empty.
        refreshOnce("initial");

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tenant-snapshot-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> refreshOnce("scheduled"),
                refreshIntervalMs, refreshIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void refreshOnce(String trigger) {
        try {
            directory.install(source.fetch());
        } catch (RuntimeException e) {
            // Keep the previously-installed snapshot (if any); never fall back to a default tenant.
            log.warn("Tenant snapshot {} refresh failed ({}); keeping snapshot v{}",
                    trigger, e.getMessage(), directory.snapshotVersion());
        }
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
