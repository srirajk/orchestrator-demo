package ai.meridian.gateway.domain.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClarificationSchema(
    String question,
    @JsonProperty("options_source") String optionsSource,
    @JsonProperty("priority") int priority,
    @JsonProperty("default") String defaultValue
) {}
