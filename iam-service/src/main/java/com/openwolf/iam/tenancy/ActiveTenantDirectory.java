package com.openwolf.iam.tenancy;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The authoritative active-tenant directory (Axiom Story B4). Membership here means a tenant is
 * fully provisioned and visible; a tenant is added ONLY by the activation compare-and-set (the last
 * step of a provision, after all four artifacts verify) and removed FIRST on deprovision.
 *
 * <p>This is the IAM-side twin of the gateway's A2
 * {@code SnapshotProvisionedTenantDirectory}: an immutable, versioned {@code tenantId → policyVersion}
 * map behind an {@link AtomicReference}. The gateway obtains its read replica by polling the
 * directory snapshot out of band ({@code TenantSnapshotSource}); a whole, consistent snapshot is
 * published atomically and a stale version is ignored. The {@link #find} lookup performs <b>zero
 * I/O</b> — it reads only the in-memory reference — so a half-provisioned or deprovisioned tenant
 * (absent from the map) resolves as unknown and fails closed without ever touching Postgres or Redis.
 *
 * <p>{@link ActiveTenantRepository} is the durable backing, loaded once at startup and written only
 * on the activate/deactivate CAS — never on a read.
 */
@Component
public class ActiveTenantDirectory {

    private static final Logger log = LoggerFactory.getLogger(ActiveTenantDirectory.class);

    /** Immutable, versioned view — the exact shape the gateway's A2 snapshot mirrors. */
    public record Snapshot(long version, Map<String, String> tenantPolicyVersions) {
        public Snapshot {
            tenantPolicyVersions = Map.copyOf(tenantPolicyVersions);
        }
    }

    private final ActiveTenantRepository repository;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(new Snapshot(0L, Map.of()));
    private final AtomicLong versionSeq = new AtomicLong(0L);

    public ActiveTenantDirectory(ActiveTenantRepository repository) {
        this.repository = repository;
    }

    /** Seed the in-memory snapshot from the durable set (startup). Control-plane I/O, never on a read. */
    @PostConstruct
    public void reload() {
        Map<String, String> map = new LinkedHashMap<>();
        for (ActiveTenant t : repository.findAll()) {
            map.put(t.getTenantId(), t.getPolicyVersion());
        }
        long version = nextVersion();
        current.set(new Snapshot(version, map));
        log.info("Active-tenant directory loaded: {} active tenant(s) (snapshot v{})", map.size(), version);
    }

    /**
     * The activation compare-and-set — the LAST step of a provision. Durably records the tenant then
     * atomically publishes a new snapshot that includes it. Idempotent: re-activating an
     * already-active tenant with the same policy version republishes the same membership.
     *
     * @return the published snapshot version.
     */
    public long activate(String tenantId, String policyVersion) {
        long version = nextVersion();
        repository.save(new ActiveTenant(tenantId, policyVersion, version));
        Snapshot published = current.updateAndGet(prev -> {
            Map<String, String> next = new LinkedHashMap<>(prev.tenantPolicyVersions());
            next.put(tenantId, policyVersion);
            return new Snapshot(version, next);
        });
        log.info("Activated tenant '{}' (policyVersion={}) — directory snapshot v{} ({} tenants)",
                tenantId, policyVersion, published.version(), published.tenantPolicyVersions().size());
        return published.version();
    }

    /**
     * The deactivation compare-and-set — the FIRST step of a deprovision. Removes the tenant from the
     * durable set and publishes a snapshot without it, so every subsequent gateway request under the
     * tenant fails closed. Non-audit artifact cleanup happens only AFTER this and the directory-version
     * ack. Idempotent: deactivating an absent tenant still bumps the version (a no-op membership).
     *
     * @return the published snapshot version (the version gateway instances must ack before cleanup).
     */
    public long deactivate(String tenantId) {
        long version = nextVersion();
        repository.deleteById(tenantId);
        Snapshot published = current.updateAndGet(prev -> {
            Map<String, String> next = new LinkedHashMap<>(prev.tenantPolicyVersions());
            next.remove(tenantId);
            return new Snapshot(version, next);
        });
        log.info("Deactivated tenant '{}' — directory snapshot v{} ({} tenants); gateway now fails closed",
                tenantId, published.version(), published.tenantPolicyVersions().size());
        return published.version();
    }

    /** Zero-I/O lookup — reads only the in-memory snapshot. Absent ⇒ unknown ⇒ fail closed. */
    public Optional<String> find(String tenantId) {
        if (tenantId == null) return Optional.empty();
        return Optional.ofNullable(current.get().tenantPolicyVersions().get(tenantId));
    }

    public boolean isActive(String tenantId) {
        return find(tenantId).isPresent();
    }

    /** The current published snapshot — what the gateway's out-of-band poll installs. */
    public Snapshot snapshot() {
        return current.get();
    }

    public long version() {
        return current.get().version();
    }

    private long nextVersion() {
        // Monotonic and unique even across restarts: seed from wall clock, then always advance.
        long candidate = System.currentTimeMillis();
        return versionSeq.updateAndGet(prev -> Math.max(prev + 1, candidate));
    }
}
