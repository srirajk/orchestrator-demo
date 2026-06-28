package ai.meridian.gateway.domain.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DomainManifest(
    @JsonProperty("domain_id") String domainId,
    String name,
    @JsonProperty("entity_registry") EntityRegistry entityRegistry,
    @JsonProperty("authorization_contract") AuthorizationContract authorizationContract,
    @JsonProperty("memory_compaction") MemoryCompaction memoryCompaction
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntityRegistry(
        String url,
        List<String> resolves,
        @JsonProperty("cache_ttl_seconds") int cacheTtlSeconds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorizationContract(
        @JsonProperty("url_template") String urlTemplate,
        @JsonProperty("allow_field") String allowField,
        @JsonProperty("cache_ttl_seconds") int cacheTtlSeconds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryCompaction(
        @JsonProperty("must_preserve") List<String> mustPreserve,
        @JsonProperty("can_drop") List<String> canDrop
    ) {}
}
