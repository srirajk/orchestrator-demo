package ai.conduit.gateway.domain.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageResolveResult(
    boolean resolved,
    String id,
    @JsonProperty("canonical_name") String canonicalName,
    List<ResolveCandidate> candidates
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolveCandidate(
        String id,
        String name
    ) {}

    /** True if multiple candidates matched and disambiguation is needed. */
    public boolean isAmbiguous() {
        return !resolved && candidates != null && candidates.size() > 1;
    }

    /** True if no candidates were found at all. */
    public boolean isNotFound() {
        return !resolved && (candidates == null || candidates.isEmpty());
    }
}
