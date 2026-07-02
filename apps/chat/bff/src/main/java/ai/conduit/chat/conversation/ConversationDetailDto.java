package ai.conduit.chat.conversation;

import ai.conduit.chat.message.MessageDto;

import java.util.List;

/** {@code GET /api/conversations/{id}} response: the conversation plus its messages. */
public record ConversationDetailDto(ConversationDto conversation, List<MessageDto> messages) {
}
