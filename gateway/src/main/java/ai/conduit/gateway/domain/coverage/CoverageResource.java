package ai.conduit.gateway.domain.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageResource(
    String id,
    String label,
    @JsonProperty("sub_domain") String subDomain
) {}
