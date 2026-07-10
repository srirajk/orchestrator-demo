package ai.conduit.gateway.registry.service;

import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only view of the agent registry.
 *
 * <p>Storage layout (Redis):
 * <pre>
 *   agent:{id}       → the full manifest as JSON
 *   registry:agents  → set of registered agent_ids
 * </pre>
 *
 * <p><b>This class cannot write.</b> Producing the registry — validating manifests, introspecting
 * live agents, embedding their example prompts, and writing both the manifests and the vector
 * index — is {@link AgentRegistrar}, which exists only in the {@code registry} profile.
 *
 * <p>The gateway resolves against registry data; it does not create it. Ingestion used to run
 * inside the gateway on {@code ApplicationReadyEvent}, which meant every gateway start re-embedded
 * the entire agent corpus, and any process that booted a gateway context — including the test
 * suite — rewrote live routing data. Separating the producer from the consumer removes both.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    static final String KEY_PREFIX = "agent:";
    static final String AGENTS_SET = "registry:agents";

    private final JedisPooled jedis;
    private final ObjectMapper mapper;

    public AgentRegistry(JedisPooled jedis, ObjectMapper mapper) {
        this.jedis  = jedis;
        this.mapper = mapper;
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

    /** How many agents are registered. Used by the gateway's startup readiness check. */
    public long count() {
        try {
            return jedis.scard(AGENTS_SET);
        } catch (Exception e) {
            return 0;
        }
    }
}
