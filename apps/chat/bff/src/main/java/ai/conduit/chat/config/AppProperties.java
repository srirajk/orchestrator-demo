package ai.conduit.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Strongly-typed configuration for the Conduit Chat BFF, bound from
 * {@code conduit.chat.*}. Nothing in the codebase reads env vars directly; all
 * tunables flow through here so there are no magic numbers/strings in code.
 *
 * @param gateway Conduit gateway (OpenAI-compatible) connection settings.
 * @param context Client-owned context window / compaction thresholds.
 * @param summary Facts-free rolling-summary LLM settings (optional; no-op if unset).
 * @param storage Object-store (MinIO/S3) settings for the files seam.
 */
@Validated
@ConfigurationProperties(prefix = "conduit.chat")
public record AppProperties(
        Gateway gateway,
        Context context,
        Summary summary,
        Storage storage
) {

    /**
     * @param baseUrl base URL of the Conduit gateway, e.g. {@code http://gateway:8080}.
     * @param model   model id sent on every completion request (e.g. {@code conduit-assistant}).
     */
    public record Gateway(
            @NotBlank String baseUrl,
            @NotBlank String model
    ) {}

    /**
     * The client owns memory. These thresholds decide what context is sent to the
     * (stateless) gateway each turn.
     *
     * @param recentMessages       number of most-recent messages sent each turn.
     * @param summaryAfterMessages once the transcript exceeds this, a rolling summary is used.
     * @param summaryMaxTokens     soft cap on the facts-free summary length.
     */
    public record Context(
            @Min(1) int recentMessages,
            @Min(1) int summaryAfterMessages,
            @Min(1) int summaryMaxTokens
    ) {}

    /**
     * Optional LLM used to (re)generate the facts-free topical summary. When
     * {@code baseUrl} or {@code apiKey} is blank the summary service is a safe no-op.
     */
    public record Summary(
            String baseUrl,
            String apiKey,
            String model,
            double temperature
    ) {
        /** @return true when an LLM endpoint is fully configured. */
        public boolean isEnabled() {
            return baseUrl != null && !baseUrl.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && model != null && !model.isBlank();
        }
    }

    /**
     * @param endpoint  S3/MinIO endpoint URL.
     * @param accessKey access key.
     * @param secretKey secret key.
     * @param bucket    target bucket for uploads.
     */
    public record Storage(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket
    ) {}
}
