package ai.meridian.gateway.synthesis.input;

import ai.meridian.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Input synthesis pipeline: Extract → Resolve → Bind.
 *
 * Given the user's plain-English prompt and the set of selected agents,
 * produces a per-agent input JsonNode for every agent that can be bound,
 * and a list of agents that must be dropped because a required field is missing.
 */
public interface InputSynthesizer {

    /**
     * Synthesizes inputs for each selected agent from the given prompt.
     *
     * @param prompt   the user's raw prompt
     * @param selected agents chosen by the resolver
     * @return a {@link SynthesisResult} containing bound inputs, dropped agents,
     *         and any clarification needed from the user
     */
    SynthesisResult synthesize(String prompt, List<AgentManifest> selected);

    /**
     * The result produced by the synthesis pipeline.
     *
     * @param inputs               agent_id → fully-bound input JSON (only for agents that could be bound)
     * @param droppedAgents        agent IDs that were selected but could not be bound (required field null)
     * @param needsClarification   true when an essential entity reference could not be resolved
     * @param clarificationMessage a user-facing question to resolve the ambiguity, or null
     */
    record SynthesisResult(
            Map<String, JsonNode> inputs,
            List<String> droppedAgents,
            boolean needsClarification,
            String clarificationMessage
    ) {}
}
