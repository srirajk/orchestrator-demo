package ai.conduit.gateway.domain.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DomainManifest(
    @JsonProperty("domain_id") String domainId,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("coverage") Coverage coverage,
    @JsonProperty("memory_compaction") MemoryCompaction memoryCompaction,
    // Clarification STYLE policy (governance knob). "template" (default) serves the byte-for-byte
    // deterministic question; "composed" lets the ClarificationComposer phrase a natural question
    // over the grounded candidate set. The clarify DECISION stays deterministic in gateway code —
    // this only affects WORDING. clarifyTone is an optional style hint passed to the composer.
    @JsonProperty("clarify_style") String clarifyStyle,
    @JsonProperty("clarify_tone") String clarifyTone
) {
    /** Clarification style, defaulting to the safe deterministic template when unset. */
    public String clarifyStyleOrDefault() {
        return (clarifyStyle == null || clarifyStyle.isBlank()) ? "template" : clarifyStyle.trim().toLowerCase();
    }
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
