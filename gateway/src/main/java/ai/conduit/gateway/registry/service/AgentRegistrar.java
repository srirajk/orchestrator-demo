package ai.conduit.gateway.registry.service;

import ai.conduit.gateway.registry.index.VectorIndexWriter;
import ai.conduit.gateway.registry.introspection.AgentIntrospector;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ai.conduit.gateway.registry.service.AgentRegistry.AGENTS_SET;
import static ai.conduit.gateway.registry.service.AgentRegistry.KEY_PREFIX;

/**
 * Writes the agent registry. Exists only in the {@code registry} profile.
 *
 * <p>The registration pipeline:
 * <ol>
 *   <li>validate the submission against the manifest schema</li>
 *   <li>introspect the live agent to derive its input/output schemas</li>
 *   <li>validate its select contracts against the rest of the registry</li>
 *   <li>persist the manifest and index its example prompts</li>
 * </ol>
 *
 * <p>Steps 2 and 4 talk to the outside world — the agent, the embedding model — and step 4 rewrites
 * routing data. None of that belongs in a service whose job at runtime is to answer questions,
 * which is why this bean is absent from the gateway. See {@link AgentRegistry} for the read side.
 */
@Service
@Profile("registry")
public class AgentRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistrar.class);

    private final JedisPooled jedis;
    private final ObjectMapper mapper;
    private final ManifestValidator validator;
    private final AgentIntrospector introspector;
    private final SelectContractValidator selectValidator;
    private final VectorIndexWriter vectorIndexWriter;
    private final AgentRegistry catalog;
    private final MeterRegistry meterRegistry;

    public AgentRegistrar(
            JedisPooled jedis,
            ObjectMapper mapper,
            ManifestValidator validator,
            AgentIntrospector introspector,
            SelectContractValidator selectValidator,
            VectorIndexWriter vectorIndexWriter,
            AgentRegistry catalog,
            MeterRegistry meterRegistry) {
        this.jedis             = jedis;
        this.mapper            = mapper;
        this.validator         = validator;
        this.introspector      = introspector;
        this.selectValidator   = selectValidator;
        this.vectorIndexWriter = vectorIndexWriter;
        this.catalog           = catalog;
        this.meterRegistry     = meterRegistry;
    }

    /** Full registration pipeline: validate → introspect → persist → index. */
    public AgentManifest register(JsonNode submissionNode) {
        AgentManifest derived = derive(submissionNode);
        List<AgentManifest> context = new ArrayList<>(catalog.listAll());
        context.removeIf(m -> m.agentId().equals(derived.agentId()));
        context.add(derived);
        SelectContractValidator.Summary summary = validateSelectContracts(derived, context);
        log.info("select validation: {} validated, {} UNVALIDATED (no output schema)",
                summary.validated(), summary.unvalidated());
        return storeAndIndex(derived);
    }

    /** Validate and introspect a submission without persisting it. */
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
            AgentManifest manifest, List<AgentManifest> allManifests) {
        return selectValidator.validateOne(manifest, allManifests);
    }

    public AgentManifest storeAndIndex(AgentManifest derived) {
        AgentManifest stored = stampAndStore(derived);
        vectorIndexWriter.index(stored);

        meterRegistry.counter("registry.registrations", "protocol", stored.protocol()).increment();
        log.info("Registered agent '{}' (protocol={}, domain={})",
                stored.agentId(), stored.protocol(), stored.domain());
        return stored;
    }

    /** Every agent id currently registered in Redis, whether or not a manifest still describes it. */
    public java.util.Set<String> registeredAgentIds() {
        return new java.util.HashSet<>(jedis.smembers(AGENTS_SET));
    }

    /** Remove an agent from the registry and the vector index. */
    public void deregister(String agentId) {
        jedis.del(KEY_PREFIX + agentId);
        jedis.srem(AGENTS_SET, agentId);
        vectorIndexWriter.removeAgent(agentId);
        log.info("Deregistered agent '{}'", agentId);
    }

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
