package ai.conduit.gateway.api.v1.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Subset of the OpenAI /v1/chat/completions request body.
 * Unknown fields are ignored so LibreChat's extra params pass through without error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String model,
        List<Message> messages,
        Boolean stream,
        @JsonProperty("max_tokens") Integer maxTokens,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        String user
) {}
