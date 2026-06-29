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
    List<String> agents,
    @JsonProperty("entity_types") List<EntityType> entityTypes,
    @JsonProperty("denial_messages") Map<String, String> denialMessages,
    @JsonProperty("messages") Map<String, String> messages
) {

    /** Normalise missing optional collections so callers never NPE. */
    public SubDomainManifest {
        if (entityTypes == null)    entityTypes = List.of();
        if (denialMessages == null) denialMessages = Map.of();
        if (messages == null)       messages = Map.of();
    }

    /**
     * Backwards-compatible constructor (pre-{@code entity_types}/{@code messages}). Used by
     * hand-built test fixtures; defaults the manifest-driven collections to empty.
     */
    public SubDomainManifest(String subDomainId, String displayName, String parentDomain,
                             List<String> requiredContext, boolean resourceScoped,
                             Map<String, ClarificationSchema> clarificationSchema, List<String> agents) {
        this(subDomainId, displayName, parentDomain, requiredContext, resourceScoped,
             clarificationSchema, agents, List.of(), Map.of(), Map.of());
    }

    /** Backwards-compatible constructor with explicit entity_types (pre-{@code messages}). */
    public SubDomainManifest(String subDomainId, String displayName, String parentDomain,
                             List<String> requiredContext, boolean resourceScoped,
                             Map<String, ClarificationSchema> clarificationSchema, List<String> agents,
                             List<EntityType> entityTypes) {
        this(subDomainId, displayName, parentDomain, requiredContext, resourceScoped,
             clarificationSchema, agents, entityTypes, Map.of(), Map.of());
    }
}
