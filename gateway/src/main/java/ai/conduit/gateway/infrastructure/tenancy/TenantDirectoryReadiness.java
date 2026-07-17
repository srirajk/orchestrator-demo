package ai.conduit.gateway.infrastructure.tenancy;

import ai.conduit.gateway.domain.auth.ProvisionedTenantDirectory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the gateway not-ready until an initial provisioned-tenant snapshot has loaded (Axiom A2,
 * contract #4: "snapshot absence ⇒ readiness fails").
 *
 * <p>Without a snapshot the {@code TenantContextResolver} fails every request closed, so the gateway
 * must not advertise itself ready. This indicator is contributed to the readiness group in
 * {@code application.yml} so the platform (and container orchestration) hold traffic until the tenant
 * directory is populated — the same sequencing the registry-ingestion health provides for routing data.
 */
@Component("tenantDirectory")
public class TenantDirectoryReadiness implements HealthIndicator {

    private final ProvisionedTenantDirectory directory;

    public TenantDirectoryReadiness(ProvisionedTenantDirectory directory) {
        this.directory = directory;
    }

    @Override
    public Health health() {
        if (directory.hasSnapshot()) {
            return Health.up()
                    .withDetail("snapshotVersion", directory.snapshotVersion())
                    .build();
        }
        return Health.down()
                .withDetail("tenantDirectory", "no provisioned-tenant snapshot loaded")
                .build();
    }
}
