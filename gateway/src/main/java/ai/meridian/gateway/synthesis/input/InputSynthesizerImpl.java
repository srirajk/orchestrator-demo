package ai.meridian.gateway.synthesis.input;

import ai.meridian.gateway.registry.model.AgentManifest;
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

    public InputSynthesizerImpl(EntityExtractor extractor,
                                EntityResolver resolver,
                                ObjectMapper mapper) {
        this.extractor = extractor;
        this.resolver  = resolver;
        this.mapper    = mapper;
    }

    @Override
    public SynthesisResult synthesize(EntityBag preExtracted, List<AgentManifest> selected) {
        // Pre-extracted bag from the combined intent+entity LLM call — skip extractor
        log.debug("Skipping LLM extraction — using pre-extracted bag: relRef={}, fundRef={}, period={}",
                preExtracted.relationshipReference(), preExtracted.fundReference(), preExtracted.period());
        return resolveAndBind(preExtracted, selected);
    }

    @Override
    public SynthesisResult synthesize(String prompt, List<AgentManifest> selected) {
        // ── Stage 1: Extract ─────────────────────────────────────────────────
        EntityBag rawBag = extractor.extract(prompt);
        log.debug("Extracted: relRef={}, fundRef={}, tickers={}, period={}",
                rawBag.relationshipReference(), rawBag.fundReference(),
                rawBag.tickerReferences(), rawBag.period());
        return resolveAndBind(rawBag, selected);
    }

    /** Shared Resolve→Bind logic used by both synthesize() overloads. */
    private SynthesisResult resolveAndBind(EntityBag rawBag, List<AgentManifest> selected) {
        // ── Stage 2: Resolve ─────────────────────────────────────────────────
        EntityBag resolvedBag = resolver.resolve(rawBag);
        log.debug("Resolved:  relId={}, fundId={}, needsClarification={}",
                resolvedBag.relationshipId(), resolvedBag.fundId(),
                resolvedBag.needsClarification());

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

    /**
     * Maps a schema field name to the corresponding value from the resolved entity bag.
     * Returns {@code null} when this extractor doesn't know that field — the agent
     * schema may declare optional fields we don't populate; that is fine.
     */
    private Object resolveField(String fieldName, EntityBag bag) {
        return switch (fieldName) {
            case "relationship_id"   -> bag.relationshipId();
            case "fund_id"           -> bag.fundId();
            case "period"            -> bag.period();
            case "tickers",
                 "ticker_references" -> bag.tickerReferences().isEmpty() ? null
                                                                         : bag.tickerReferences();
            // Fields the gateway doesn't supply (e.g. agent-specific params) → null
            default                  -> null;
        };
    }

    // ── Clarification message builder ─────────────────────────────────────────

    private String buildClarificationMessage(EntityBag raw, EntityBag resolved) {
        if (raw.relationshipReference() != null && resolved.relationshipId() == null) {
            return String.format(
                    "I couldn't find a relationship matching \"%s\" in your book. " +
                    "Could you clarify which client you meant? " +
                    "Known relationships include: Whitman Family Office, Chen Family Trust, Patterson.",
                    raw.relationshipReference());
        }
        if (raw.relationshipReference() == null) {
            return "Which client relationship should I pull data for? " +
                   "Please specify the relationship name (e.g. Whitman Family Office).";
        }
        return "I need a little more information to answer your question. " +
               "Could you clarify which relationship or fund you are asking about?";
    }
}
