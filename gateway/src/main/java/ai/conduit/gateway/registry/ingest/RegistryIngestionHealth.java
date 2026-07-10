package ai.conduit.gateway.registry.ingest;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports the registry service unhealthy until the agent manifests have been fully ingested.
 *
 * <p>This is what sequences the platform. The gateway waits on this service's health before it
 * starts, so it can never come up against a half-built or absent routing index. Without it, the
 * gateway would start, find nothing to search, answer every question with a clarification, and
 * report itself perfectly healthy.
 */
@Component("registryIngestion")
@Profile("registry")
public class RegistryIngestionHealth implements HealthIndicator {

    private enum State { INGESTING, READY, FAILED }

    private final AtomicReference<State> state = new AtomicReference<>(State.INGESTING);
    private final AtomicReference<String> failure = new AtomicReference<>();

    void markIngested() {
        state.set(State.READY);
    }

    void markFailed(String reason) {
        failure.set(reason);
        state.set(State.FAILED);
    }

    @Override
    public Health health() {
        return switch (state.get()) {
            case READY -> Health.up().withDetail("registry", "ingested").build();
            case INGESTING -> Health.down().withDetail("registry", "ingesting").build();
            case FAILED -> Health.down()
                    .withDetail("registry", "ingestion failed")
                    .withDetail("reason", failure.get())
                    .build();
        };
    }
}
