package ai.meridian.gateway.domain.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubDomainManifest(
    @JsonProperty("sub_domain_id") String subDomainId,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("parent_domain") String parentDomain,
    @JsonProperty("required_context") List<String> requiredContext,
    @JsonProperty("resource_scoped") boolean resourceScoped,
    @JsonProperty("clarification_schema") Map<String, ClarificationSchema> clarificationSchema,
    List<String> agents
) {}
