package ai.meridian.gateway.registry.loader;

import ai.meridian.gateway.registry.index.EmbeddingClient;
import ai.meridian.gateway.registry.index.VectorIndex;
import ai.meridian.gateway.registry.service.AgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads all bundled manifests from classpath:/manifests/*.json at startup.
 * Uses the same registration pipeline as the live POST /admin/agents endpoint —
 * no special bootstrap code path.
 *
 * Waits for the embedding service to be ready before starting registration,
 * retrying up to PROBE_RETRIES times with PROBE_DELAY_MS intervals.
 * Invalid manifests are logged and skipped; they do not abort startup.
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

    public RegistryBootstrapLoader(AgentRegistry registry, VectorIndex vectorIndex,
                                   EmbeddingClient embedding, ObjectMapper mapper) {
        this.registry  = registry;
        this.vectorIndex = vectorIndex;
        this.embedding = embedding;
        this.mapper    = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        waitForEmbeddingService();
        vectorIndex.ensureIndex();

        int loaded = 0;
        int failed = 0;

        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:manifests/*.json");

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
