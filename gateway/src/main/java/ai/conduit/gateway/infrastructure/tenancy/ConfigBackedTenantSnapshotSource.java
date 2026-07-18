package ai.conduit.gateway.infrastructure.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The config-backed provisioned-tenant snapshot for the single-tenant demo, dev, and tests
 * (Axiom A2, contract #4: a {@code @Profile} config-backed snapshot is fine for the demo and MUST
 * contain {@code default}). Active whenever the real IAM-backed source is not — i.e. unless the
 * {@code multi-tenant} profile is on.
 *
 * <p>Config lives in {@code application.yml} via {@code @Value} (this codebase does not use
 * {@code @ConfigurationProperties} — CLAUDE.md §5): {@code conduit.tenancy.provisioned-tenants} is a
 * comma-separated list of {@code tenantId:policyVersion} pairs (policy version optional). It always
 * guarantees the {@code default} tenant is present so every single-tenant request (A1 + the Python
 * seeder mint {@code tenant_id=default}) passes the fail-closed gate; fail-closed fires only for a
 * genuinely missing or unknown tenant.
 */
@Component
@Profile("!multi-tenant")
public class ConfigBackedTenantSnapshotSource implements TenantSnapshotSource {

    private static final Logger log = LoggerFactory.getLogger(ConfigBackedTenantSnapshotSource.class);

    /** The tenant every single-tenant demo request carries; always provisioned by this source. */
    static final String DEFAULT_TENANT = "default";
    static final String DEFAULT_POLICY_VERSION = "default";

    private final TenantSnapshotSource.TenantSnapshot snapshot;

    public ConfigBackedTenantSnapshotSource(
            @Value("${conduit.tenancy.provisioned-tenants:default:default}") String provisionedSpec) {
        Map<String, String> tenants = new LinkedHashMap<>();
        if (provisionedSpec != null) {
            for (String pair : provisionedSpec.split(",")) {
                String entry = pair.trim();
                if (entry.isEmpty()) continue;
                int colon = entry.indexOf(':');
                String tenantId = colon < 0 ? entry : entry.substring(0, colon).trim();
                String policyVersion = colon < 0 ? DEFAULT_POLICY_VERSION : entry.substring(colon + 1).trim();
                if (tenantId.isEmpty()) continue;
                if (policyVersion.isEmpty()) policyVersion = DEFAULT_POLICY_VERSION;
                tenants.put(tenantId, policyVersion);
            }
        }
        // The demo/test snapshot MUST contain the default tenant, even if config omitted it.
        tenants.putIfAbsent(DEFAULT_TENANT, DEFAULT_POLICY_VERSION);
        this.snapshot = new TenantSnapshot(1L, tenants);
        log.info("Config-backed tenant snapshot: {} provisioned tenant(s) {}",
                tenants.size(), tenants.keySet());
    }

    @Override
    public TenantSnapshot fetch() {
        return snapshot;
    }
}
