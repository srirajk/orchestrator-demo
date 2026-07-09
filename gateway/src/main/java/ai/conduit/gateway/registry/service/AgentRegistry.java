package ai.conduit.gateway.registry.service;

import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.introspection.AgentIntrospector;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Core registry service.
 *
 * Storage layout (Redis):
 *   agent:{id}       → RedisJSON full manifest
 *   registry:agents  → Redis set of registered agent_ids
 *
 * The registration pipeline mirrors the spec:
 *   1. Validate against schema
 *   2. Introspect (derive input/output schemas)
 *   3. Persist as RedisJSON
 *   4. Index example prompts in the HNSW vector index
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private static final String KEY_PREFIX    = "agent:";
    private static final String AGENTS_SET    = "registry:agents";

    private final JedisPooled      jedis;
    private final ObjectMapper     mapper;
    private final ManifestValidator validator;
    private final AgentIntrospector introspector;
    private final SelectContractValidator selectValidator;
    private final VectorIndex       vectorIndex;
    private final MeterRegistry     meterRegistry;

    public AgentRegistry(
            JedisPooled jedis,
            ObjectMapper mapper,
            ManifestValidator validator,
            AgentIntrospector introspector,
            SelectContractValidator selectValidator,
            VectorIndex vectorIndex,
            MeterRegistry meterRegistry) {
        this.jedis         = jedis;
        this.mapper        = mapper;
        this.validator     = validator;
        this.introspector  = introspector;
        this.selectValidator = selectValidator;
        this.vectorIndex   = vectorIndex;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Full registration pipeline: validate → introspect → persist → index.
     *
     * @param submissionNode raw JSON from the domain team
     * @return the fully-derived manifest stored in Redis
     */
    public AgentManifest register(JsonNode submissionNode) {
        AgentManifest derived = derive(submissionNode);
        List<AgentManifest> context = new ArrayList<>(listAll());
        context.removeIf(m -> m.agentId().equals(derived.agentId()));
        context.add(derived);
        SelectContractValidator.Summary summary = validateSelectContracts(derived, context);
        log.info("select validation: {} validated, {} UNVALIDATED (no output schema)",
                summary.validated(), summary.unvalidated());

        // 3. Stamp and persist
        return storeAndIndex(derived);
    }

    /**
     * Re-register an existing agent (update + re-introspect).
     */
    public AgentManifest update(String agentId, JsonNode submissionNode) {
        if (!exists(agentId)) {
            throw new IllegalArgumentException("Agent '" + agentId + "' not found");
        }
        return register(submissionNode);
    }

    public AgentManifest derive(JsonNode submissionNode) {
        validator.validate(submissionNode);

        AgentManifest submission;
        try {
            submission = mapper.treeToValue(submissionNode, AgentManifest.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse manifest: " + e.getMessage(), e);
        }

        return introspector.introspect(submission);
    }

    public SelectContractValidator.Summary validateSelectContracts(
            AgentManifest manifest,
            List<AgentManifest> allManifests) {
        return selectValidator.validateOne(manifest, allManifests);
    }

    public AgentManifest storeAndIndex(AgentManifest derived) {
        AgentManifest stored = stampAndStore(derived);
        vectorIndex.index(stored);

        meterRegistry.counter("registry.registrations", "protocol", stored.protocol()).increment();
        log.info("Registered agent '{}' (protocol={}, domain={})",
                stored.agentId(), stored.protocol(), stored.domain());
        return stored;
    }

    /**
     * Remove an agent from the registry and vector index.
     */
    public void deregister(String agentId) {
        jedis.del(KEY_PREFIX + agentId);
        jedis.srem(AGENTS_SET, agentId);
        vectorIndex.removeAgent(agentId);
        log.info("Deregistered agent '{}'", agentId);
    }

    public Optional<AgentManifest> find(String agentId) {
        String json = jedis.get(KEY_PREFIX + agentId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, AgentManifest.class));
        } catch (Exception e) {
            log.warn("Failed to deserialize manifest for '{}': {}", agentId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<AgentManifest> listAll() {
        Set<String> ids = jedis.smembers(AGENTS_SET);
        List<AgentManifest> result = new ArrayList<>();
        for (String id : ids) {
            find(id).ifPresent(result::add);
        }
        return result;
    }

    public boolean exists(String agentId) {
        return jedis.exists(KEY_PREFIX + agentId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private AgentManifest stampAndStore(AgentManifest manifest) {
        AgentManifest stamped = new AgentManifest(
                manifest.agentId(),
                manifest.name(),
                manifest.description(),
                manifest.version(),
                manifest.provider(),
                manifest.domain(),
                manifest.audience(),
                manifest.subDomain(),
                manifest.maxResponseTokens(),
                manifest.protocol(),
                manifest.connection(),
                manifest.capabilities(),
                manifest.skills(),
                manifest.constraints(),
                manifest.io(),
                manifest.inputSchema(),
                manifest.outputSchema(),
                manifest.resolvedConnection(),
                true,
                Instant.now()
        );

        try {
            String json = mapper.writeValueAsString(stamped);
            jedis.set(KEY_PREFIX + stamped.agentId(), json);
            jedis.sadd(AGENTS_SET, stamped.agentId());
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist manifest: " + e.getMessage(), e);
        }

        return stamped;
    }
}
