package ai.meridian.gateway.domain.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClarificationSchema(
    String question,
    @JsonProperty("options_source") String optionsSource,
    @JsonProperty("clarification_priority") List<String> clarificationPriority,
    @JsonProperty("default_value") String defaultValue
) {}
