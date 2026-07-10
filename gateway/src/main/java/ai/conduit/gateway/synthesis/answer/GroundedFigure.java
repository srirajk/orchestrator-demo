package ai.conduit.gateway.synthesis.answer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

public record GroundedFigure(
        String label,
        String placeholder,
        String renderedValue,
        JsonNode rawValue,
        String format,
        String sourceAgent,
        Set<Double> numericValues) {
}
