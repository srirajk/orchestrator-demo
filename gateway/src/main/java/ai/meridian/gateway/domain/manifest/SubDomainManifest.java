package ai.meridian.gateway.domain.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubDomainManifest(
    @JsonProperty("sub_domain_id") String subDomainId,
    String name,
    @JsonProperty("parent_domain") String parentDomain,
    @JsonProperty("required_context") List<String> requiredContext,
    @JsonProperty("clarification_priority") List<String> clarificationPriority,
    @JsonProperty("clarification_schema") Map<String, ClarificationSchema> clarificationSchema,
    @JsonProperty("authorization_contract") DomainManifest.AuthorizationContract authorizationContract,
    @JsonProperty("entity_registry") DomainManifest.EntityRegistry entityRegistry,
    List<String> agents
) {}
