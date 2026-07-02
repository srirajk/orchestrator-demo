package ai.conduit.chat.chat;

import jakarta.validation.constraints.NotBlank;

/** Body for {@code POST /api/conversations/{id}/messages}. */
public record SendMessageRequest(@NotBlank(message = "is required") String content) {
}
