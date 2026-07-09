package ai.conduit.gateway.synthesis.input;

import ai.conduit.gateway.domain.manifest.ClarificationSchema;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 3 — Bind, and orchestrates the full Extract → Resolve → Bind pipeline.
 *
 * For each selected agent the binder:
 * <ol>
 *   <li>Looks at the agent's {@code inputSchema.properties} to discover which
 *       fields are expected.</li>
 *   <li>Maps schema field names to resolved entity-bag values using a fixed
 *       field-name table (relationship_id, fund_id, period, tickers).</li>
 *   <li>Checks the {@code inputSchema.required} array: if any required field
 *       resolved to null, the agent is dropped rather than inventing a value.</li>
 * </ol>
 *
 * Hard invariant: <strong>no fabricated identifiers</strong>.  A null required
 * field → agent dropped.  Zero guessing.
 */
@Service
public class InputSynthesizerImpl implements InputSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(InputSynthesizerImpl.class);

    private final EntityExtractor extractor;
    private final EntityResolver  resolver;
    private final ObjectMapper    mapper;
    private final DomainManifestStore manifestStore;

    public InputSynthesizerImpl(EntityExtractor extractor,
                                EntityResolver resolver,
                                ObjectMapper mapper,
                                DomainManifestStore manifestStore) {
        this.extractor = extractor;
        this.resolver  = resolver;
        this.mapper    = mapper;
        this.manifestStore = manifestStore;
    }

    @Override
    public SynthesisResult synthesize(EntityBag preExtracted, List<AgentManifest> selected) {
        // Pre-extracted bag from the combined intent+entity LLM call — skip extractor
        log.debug("Skipping LLM extraction — using pre-extracted bag: references={}",
                preExtracted.references());
        return resolveAndBind(preExtracted, selected);
    }

    @Override
    public SynthesisResult synthesize(String prompt, List<AgentManifest> selected) {
        // ── Stage 1: Extract ─────────────────────────────────────────────────
        EntityBag rawBag = extractor.extract(prompt);
        log.debug("Extracted: references={}, lists={}", rawBag.references(), rawBag.lists());
        return resolveAndBind(rawBag, selected);
    }

    /** Shared Resolve→Bind logic used by both synthesize() overloads. */
    private SynthesisResult resolveAndBind(EntityBag rawBag, List<AgentManifest> selected) {
        // ── Stage 2: Resolve ─────────────────────────────────────────────────
        EntityBag resolvedBag = resolver.resolve(rawBag);
        log.debug("Resolved:  resolved={}, needsClarification={}",
                resolvedBag.resolved(), resolvedBag.needsClarification());

        // ── Stage 3: Bind ────────────────────────────────────────────────────
        Map<String, JsonNode> inputs        = new LinkedHashMap<>();
        List<String>          droppedAgents = new ArrayList<>();

        for (AgentManifest agent : selected) {
            JsonNode bound = bind(agent, resolvedBag);
            if (bound != null) {
                inputs.put(agent.agentId(), bound);
                log.debug("Bound agent '{}': {}", agent.agentId(), bound);
            } else {
                droppedAgents.add(agent.agentId());
                log.warn("Dropped agent '{}' — required field is null and cannot be fabricated.",
                        agent.agentId());
            }
        }

        // ── Clarification ─────────────────────────────────────────────────────
        boolean needsClarification = resolvedBag.needsClarification()
                || (inputs.isEmpty() && !selected.isEmpty());

        String clarificationMessage = null;
        if (needsClarification) {
            clarificationMessage = buildClarificationMessage(rawBag, resolvedBag);
        }

        return new SynthesisResult(inputs, droppedAgents, needsClarification, clarificationMessage);
    }

    // ── Bind logic ────────────────────────────────────────────────────────────

    /**
     * Produces a JsonNode input object for {@code agent} from {@code bag},
     * or returns {@code null} when a required field cannot be filled.
     */
    private JsonNode bind(AgentManifest agent, EntityBag bag) {
        JsonNode inputSchema = agent.inputSchema();
        if (isUpstreamMapConsumer(agent)) {
            log.debug("Agent '{}' is a map consumer fed by upstream producers; using DAG placeholder input.",
                    agent.agentId());
            return mapper.createObjectNode();
        }

        if (inputSchema == null || inputSchema.isNull() || inputSchema.isMissingNode()) {
            // No schema → agent accepts an empty body (e.g. a list-all endpoint)
            log.debug("Agent '{}' has no inputSchema; using empty input.", agent.agentId());
            return mapper.createObjectNode();
        }

        JsonNode properties = inputSchema.path("properties");
        JsonNode required   = inputSchema.path("required");

        ObjectNode output = mapper.createObjectNode();

        // Walk every declared property and fill it if we have a value
        if (properties.isObject()) {
            properties.fieldNames().forEachRemaining(fieldName -> {
                Object value = resolveField(fieldName, bag);
                if (value instanceof String s) {
                    output.put(fieldName, s);
                } else if (value instanceof List<?> list) {
                    var arr = output.putArray(fieldName);
                    list.forEach(item -> arr.add(item.toString()));
                }
                // null → field omitted; checked against required array below
            });
        }

        // Enforce required fields: if any is missing → drop this agent
        if (required.isArray()) {
            for (JsonNode reqField : required) {
                String fieldName = reqField.asText();
                if (!output.has(fieldName) || output.get(fieldName).isNull()) {
                    log.debug("Agent '{}' requires '{}' but it resolved to null — dropping.",
                            agent.agentId(), fieldName);
                    return null;
                }
            }
        }

        return output;
    }

    private boolean isUpstreamMapConsumer(AgentManifest agent) {
        AgentManifest.Io io = agent == null ? null : agent.io();
        if (io == null || !io.hasMap() || io.consumes() == null) return false;
        return io.consumes().stream()
                .anyMatch(c -> c != null && c.isProducedRef() && c.isRequired());
    }

    /**
     * Maps an agent-schema field name to a value from the resolved entity bag. Agent input
     * fields are expected to match entity-type {@code key}s (resolvable/literal) or list
     * {@code extract_as} fields. Returns {@code null} for fields the gateway does not supply.
     */
    private Object resolveField(String fieldName, EntityBag bag) {
        String resolvedValue = bag.resolved(fieldName);   // resolvable keys → resolved id
        if (resolvedValue != null) return resolvedValue;

        String reference = bag.reference(fieldName);       // literal keys → raw value
        if (reference != null) return reference;

        List<String> list = bag.list(fieldName);           // list keys → list value
        if (list != null && !list.isEmpty()) return list;

        return null;
    }

    // ── Clarification message builder ─────────────────────────────────────────

    /**
     * Builds a clarification message from the manifest. Finds the required resolvable entity
     * that could not be resolved and returns its declared clarification question; falls back to
     * a generic question built from the entity's display label. No hardcoded domain copy.
     */
    private String buildClarificationMessage(EntityBag raw, EntityBag resolved) {
        for (EntityType et : manifestStore.entityTypes()) {
            if (!et.isResolvable() || !et.required()) continue;
            if (resolved.resolved(et.key()) != null) continue; // this one resolved fine

            ClarificationSchema cs = manifestStore.clarificationFor(et.key());
            if (cs != null && cs.question() != null && !cs.question().isBlank()) {
                return cs.question();
            }
            return "Which " + et.display() + " are you asking about?";
        }
        return "I need a little more information to answer your question.";
    }
}
