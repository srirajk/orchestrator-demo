package ai.conduit.gateway.registry.loader;

import ai.conduit.gateway.registry.index.EmbeddingClient;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.service.AgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads agent manifests from the external registry location at startup.
 *
 * <p>World B: manifests are a domain team's deliverable, NOT gateway code. The location is
 * configurable via {@code conduit.registry.location} — defaulting to {@code classpath:}
 * (bundled, for tests/local) and overridden to {@code file:/registry/} in the container,
 * where {@code ./registry} is mounted as a volume. Onboarding a domain = drop JSON in that
 * folder and restart; no image rebuild.
 *
 * <p>Uses the same registration pipeline as the live POST /admin/agents endpoint —
 * no special bootstrap code path. Waits for the embedding service to be ready before
 * registration. Invalid manifests are logged and skipped; they do not abort startup.
 */
@Component
public class RegistryBootstrapLoader {

    private static final Logger log = LoggerFactory.getLogger(RegistryBootstrapLoader.class);

    private static final int    PROBE_RETRIES  = 15;
    private static final long   PROBE_DELAY_MS = 3_000;

    private final AgentRegistry  registry;
    private final VectorIndex    vectorIndex;
    private final EmbeddingClient embedding;
    private final ObjectMapper   mapper;
    private final String         registryLocation;

    public RegistryBootstrapLoader(AgentRegistry registry, VectorIndex vectorIndex,
                                   EmbeddingClient embedding, ObjectMapper mapper,
                                   @Value("${conduit.registry.location:classpath:}") String registryLocation) {
        this.registry  = registry;
        this.vectorIndex = vectorIndex;
        this.embedding = embedding;
        this.mapper    = mapper;
        this.registryLocation = registryLocation;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        waitForEmbeddingService();
        vectorIndex.ensureIndex();

        int loaded = 0;
        int failed = 0;

        try {
            String pattern = registryLocation + "manifests/**/*.json";
            log.info("Loading agent manifests from {}", pattern);
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(pattern);

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                try {
                    JsonNode node = mapper.readTree(resource.getInputStream());
                    registry.register(node);
                    loaded++;
                    log.info("Loaded manifest: {}", filename);
                } catch (Exception e) {
                    failed++;
                    log.warn("Skipping invalid manifest '{}': {}", filename, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan manifests directory: {}", e.getMessage());
        }

        log.info("Registry bootstrap complete: {} loaded, {} failed", loaded, failed);
    }

    private void waitForEmbeddingService() {
        for (int attempt = 1; attempt <= PROBE_RETRIES; attempt++) {
            try {
                float[] probe = embedding.embed("probe");
                if (probe.length > 0) {
                    log.info("Embedding service ready (dim={})", probe.length);
                    return;
                }
            } catch (Exception e) {
                log.info("Waiting for embedding service — attempt {}/{}: {}", attempt, PROBE_RETRIES, e.getMessage());
            }
            try {
                Thread.sleep(PROBE_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Embedding service did not become ready after {} attempts — proceeding without it", PROBE_RETRIES);
    }
}
