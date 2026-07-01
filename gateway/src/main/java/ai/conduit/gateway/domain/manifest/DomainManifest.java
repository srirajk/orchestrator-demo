package ai.conduit.gateway.domain.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DomainManifest(
    @JsonProperty("domain_id") String domainId,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("coverage") Coverage coverage,
    @JsonProperty("memory_compaction") MemoryCompaction memoryCompaction
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Coverage(
        @JsonProperty("discover_url") String discoverUrl,
        @JsonProperty("check_url") String checkUrl,
        @JsonProperty("resolve_url") String resolveUrl,
        @JsonProperty("cache_ttl_seconds") int cacheTtlSeconds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryCompaction(
        @JsonProperty("must_preserve") List<String> mustPreserve,
        @JsonProperty("can_drop") List<String> canDrop
    ) {}
}
