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
            @NotBlank String model,
            @Min(1) int maxReplyChars,
            @Min(1) long connectTimeoutMs,
            @Min(1) long requestTimeoutMs
    ) {}

    /**
     * The client owns memory. These <b>token-budget</b> thresholds decide what context is
     * sent to the (stateless) gateway each turn. Compaction is driven by estimated token
     * size, not message count.
     *
     * @param maxTokens            token budget for the window sent to the gateway. Recent
     *                             messages are included newest-first until the next would
     *                             exceed this (the latest user message is always included).
     * @param summaryTriggerTokens once the full transcript's estimated tokens exceed this,
     *                             the facts-free rolling summary is (re)generated and, when
     *                             the window actually drops older messages, prepended as a
     *                             leading {@code system} message (counted against
     *                             {@code maxTokens}).
     * @param summaryRefreshMessages once the transcript has grown by this many messages beyond
     *                             the count captured when the summary was last generated, the
     *                             summary is regenerated (fire-and-forget) so it keeps rolling
     *                             rather than freezing after the first generation.
     * @param tokenEncoding        tiktoken encoding used for estimation (e.g.
     *                             {@code cl100k_base}, {@code o200k_base}); falls back to a
     *                             {@code chars/4} heuristic if unavailable.
     */
    public record Context(
            @Min(1) int maxTokens,
            @Min(1) int summaryTriggerTokens,
            @Min(1) int summaryRefreshMessages,
            @NotBlank String tokenEncoding
    ) {}

    /**
     * Optional LLM used to (re)generate the facts-free topical summary. Defaults to OpenAI.
     * When {@code baseUrl} or {@code apiKey} is blank the summary service is a safe no-op.
     *
     * @param baseUrl     OpenAI-compatible base URL (default {@code https://api.openai.com/v1}).
     * @param apiKey      API key; blank → summary is a no-op.
     * @param model       completion model (default {@code gpt-4o-mini}).
     * @param temperature sampling temperature.
     * @param maxTokens   soft cap on the facts-free summary length.
     */
    public record Summary(
            String baseUrl,
            String apiKey,
            String model,
            double temperature,
            @Min(1) int maxTokens
    ) {
        /** @return true when an LLM endpoint is fully configured. */
        public boolean isEnabled() {
            return baseUrl != null && !baseUrl.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && model != null && !model.isBlank();
        }
    }

    /**
     * @param endpoint    S3/MinIO endpoint URL.
     * @param accessKey   access key.
     * @param secretKey   secret key.
     * @param bucket      target bucket for uploads.
     * @param maxFileSize maximum accepted upload size in bytes.
     */
    public record Storage(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            @Min(1) long maxFileSize
    ) {}
}
