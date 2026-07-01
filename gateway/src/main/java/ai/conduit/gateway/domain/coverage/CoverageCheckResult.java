package ai.conduit.gateway.domain.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageCheckResult(
    boolean allowed,
    String reason
) {
    /** Factory: create a denied result with a machine-readable reason code. */
    public static CoverageCheckResult denied(String reason) {
        return new CoverageCheckResult(false, reason);
    }

    /** Factory: create an allowed result. */
    public static CoverageCheckResult ofAllowed() {
        return new CoverageCheckResult(true, null);
    }
}
