package ai.conduit.gateway.registry.ingest;

import ai.conduit.gateway.registry.embedding.TextEmbedder;
import ai.conduit.gateway.registry.index.VectorIndexWriter;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.service.AgentRegistrar;
import ai.conduit.gateway.registry.service.SelectContractValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Ingests the agent registry at startup of the registry service.
 *
 * <p>It reads the manifests from the registry folder, validates each against the manifest schema,
 * introspects the live agent for its input/output schemas, checks its select contracts against the
 * rest of the registry, then writes the manifests and their example-prompt vectors into Redis.
 * Only once that succeeds does {@link RegistryIngestionHealth} report the service healthy, which is
 * what releases the gateway to start.
 *
 * <p>This used to run inside the gateway on {@code ApplicationReadyEvent}. Two consequences
 * followed. Every gateway start re-embedded the whole agent corpus, so a restart's cost scaled with
 * the registry and depended on the embedding service being reachable. And any process that booted a
 * gateway context wrote to the routing index — the test suite included, against a developer's live
 * Redis.
 *
 * <p><b>An invalid manifest fails ingestion.</b> The old loader logged {@code "Skipping invalid
 * manifest"} and carried on, which is how a widened {@code transport} enum in one of three copies of
 * the schema let the gateway boot with seven of eighteen agents missing and report itself healthy.
 * A registry that does not fully load is not a degraded registry, it is an unknown one. Set
 * {@code conduit.registry.ingest.fail-on-invalid=false} to return to the old behaviour.
 */
@Component
@Profile("registry")
@ConditionalOnProperty(name = "conduit.registry.ingest.enabled", havingValue = "true", matchIfMissing = true)
public class RegistryIngestor {

    private static final Logger log = LoggerFactory.getLogger(RegistryIngestor.class);

    private static final int  PROBE_RETRIES  = 15;
    private static final long PROBE_DELAY_MS = 3_000;

    private final AgentRegistrar registrar;
    private final VectorIndexWriter vectorIndexWriter;
    private final TextEmbedder embedding;
    private final ObjectMapper mapper;
    private final RegistryIngestionHealth health;
    private final String registryLocation;
    private final boolean failOnInvalid;
    private final boolean pruneOrphans;
    private final AtomicBoolean ran = new AtomicBoolean(false);

    public RegistryIngestor(AgentRegistrar registrar,
                            VectorIndexWriter vectorIndexWriter,
                            TextEmbedder embedding,
                            ObjectMapper mapper,
                            RegistryIngestionHealth health,
                            @Value("${conduit.registry.location:classpath:}") String registryLocation,
                            @Value("${conduit.registry.ingest.fail-on-invalid:true}") boolean failOnInvalid,
                            @Value("${conduit.registry.ingest.prune-orphans:true}") boolean pruneOrphans) {
        this.registrar         = registrar;
        this.vectorIndexWriter = vectorIndexWriter;
        this.embedding         = embedding;
        this.mapper            = mapper;
        this.health            = health;
        this.registryLocation  = registryLocation;
        this.failOnInvalid     = failOnInvalid;
        this.pruneOrphans      = pruneOrphans;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        if (!ran.compareAndSet(false, true)) {
            return;
        }
        try {
            ingest();
            health.markIngested();
        } catch (Exception e) {
            // Staying up but unhealthy would leave the gateway blocked on a healthcheck with no
            // explanation. Exit non-zero so the orchestrator surfaces the failure.
            log.error("Registry ingestion failed — the gateway must not start against this registry: {}",
                    e.getMessage(), e);
            health.markFailed(e.getMessage());
            System.exit(1);
        }
    }

    private void ingest() throws Exception {
        waitForEmbeddingService();
        vectorIndexWriter.ensureIndex();

        String pattern = registryLocation + "manifests/**/*.json";
        log.info("Ingesting agent manifests from {}", pattern);
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
        if (resources.length == 0) {
            throw new IllegalStateException("No manifests found at " + pattern
                    + " — refusing to leave the gateway with an empty registry");
        }

        List<DerivedResource> derived = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            try {
                JsonNode node = mapper.readTree(resource.getInputStream());
                derived.add(new DerivedResource(filename, registrar.derive(node)));
            } catch (Exception e) {
                rejected.add(filename + ": " + e.getMessage());
                log.error("Invalid manifest '{}': {}", filename, e.getMessage());
            }
        }

        int loaded = 0;
        SelectContractValidator.Summary selectSummary = new SelectContractValidator.Summary(0, 0);
        List<AgentManifest> all = derived.stream().map(DerivedResource::manifest).toList();
        for (DerivedResource resource : derived) {
            try {
                selectSummary = selectSummary.plus(
                        registrar.validateSelectContracts(resource.manifest(), all));
                registrar.storeAndIndex(resource.manifest());
                loaded++;
                log.info("Ingested manifest: {}", resource.filename());
            } catch (Exception e) {
                rejected.add(resource.filename() + ": " + e.getMessage());
                log.error("Failed to ingest '{}': {}", resource.filename(), e.getMessage());
            }
        }

        log.info("select validation: {} validated, {} UNVALIDATED (no output schema)",
                selectSummary.validated(), selectSummary.unvalidated());
        log.info("Registry ingestion complete: {} loaded, {} rejected", loaded, rejected.size());

        if (!rejected.isEmpty()) {
            rejected.forEach(r -> log.error("  rejected: {}", r));
            if (failOnInvalid) {
                throw new IllegalStateException("Rejected " + rejected.size() + " manifest(s). "
                        + "A partially-loaded registry routes silently to whichever agents happened to survive.");
            }
        }
        if (loaded == 0) {
            throw new IllegalStateException("No manifest was ingested — the routing index would be empty");
        }

        reconcile(derived.stream().map(d -> d.manifest().agentId()).collect(Collectors.toSet()));
    }

    /**
     * Deregister every agent that is present in Redis but described by no manifest in the folder.
     *
     * <p>Ingestion only ever added. Registering an agent wrote it to Redis; deleting its manifest
     * did nothing, so the agent stayed registered — and visible to {@code GET /admin/agents} and to
     * the multi-step planner, which asks the catalogue for every agent it might chain — indefinitely.
     * A live registry accumulated five agents from an earlier naming generation, carrying a
     * {@code transport} value the current schema no longer accepts. They could never have been
     * re-ingested, and nothing would ever have removed them.
     *
     * <p>The folder is the source of truth. If it does not describe an agent, that agent is not
     * registered. Set {@code conduit.registry.ingest.prune-orphans=false} to keep the old
     * append-only behaviour.
     */
    void reconcile(Set<String> ingestedIds) {
        Set<String> orphans = registrar.registeredAgentIds();
        orphans.removeAll(ingestedIds);
        if (orphans.isEmpty()) {
            log.info("Registry reconciled — no orphaned agents");
            return;
        }
        if (!pruneOrphans) {
            log.warn("{} agent(s) are registered but described by no manifest, and pruning is "
                    + "disabled. They remain visible to the catalogue and the planner: {}",
                    orphans.size(), orphans);
            return;
        }
        log.warn("Pruning {} orphaned agent(s) — registered but described by no manifest in {}",
                orphans.size(), registryLocation);
        for (String agentId : orphans) {
            registrar.deregister(agentId);
            log.warn("  pruned orphan: {}", agentId);
        }
    }

    private void waitForEmbeddingService() {
        for (int attempt = 1; attempt <= PROBE_RETRIES; attempt++) {
            try {
                float[] probe = embedding.embed("probe");
                if (probe.length > 0) {
                    // A vector of the wrong width is not a slow start, it is a misconfiguration:
                    // the index would be built in one space and searched in another. Fail loudly.
                    if (probe.length != embedding.dimension()) {
                        throw new IllegalStateException("Embedding service returned " + probe.length
                                + "-dim vectors but conduit.embedding.dimension is " + embedding.dimension());
                    }
                    log.info("Embedding service ready (model={}, dim={})", embedding.id(), probe.length);
                    return;
                }
            } catch (IllegalStateException e) {
                throw e;
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
        throw new IllegalStateException("Embedding service never became ready after "
                + PROBE_RETRIES + " attempts — cannot ingest the registry");
    }

    private record DerivedResource(String filename, AgentManifest manifest) {}
}
