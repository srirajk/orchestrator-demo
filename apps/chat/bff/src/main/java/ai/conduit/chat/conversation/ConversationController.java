package ai.conduit.chat.conversation;

import ai.conduit.chat.auth.CurrentUser;
import ai.conduit.chat.message.MessageDto;
import ai.conduit.chat.message.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Conversation CRUD. The streaming send endpoint
 * ({@code POST /api/conversations/{id}/messages}) lives in {@code ChatController}.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final CurrentUser currentUser;

    public ConversationController(ConversationService conversationService,
                                  MessageService messageService,
                                  CurrentUser currentUser) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ConversationDto> list() {
        return conversationService.listActive(currentUser.id()).stream()
                .map(ConversationDto::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto create(@RequestBody(required = false) CreateConversationRequest body) {
        String title = body == null ? null : body.title();
        return ConversationDto.from(conversationService.create(currentUser.id(), title));
    }

    @GetMapping("/{id}")
    public ConversationDetailDto detail(@PathVariable String id) {
        String userId = currentUser.id();
        Conversation conversation = conversationService.getOwnedOrThrow(id, userId);
        List<MessageDto> messages = messageService.allInOrder(id).stream()
                .map(MessageDto::from)
                .toList();
        return new ConversationDetailDto(ConversationDto.from(conversation), messages);
    }

    @PatchMapping("/{id}")
    public ConversationDto update(@PathVariable String id, @RequestBody UpdateConversationRequest body) {
        return ConversationDto.from(conversationService.update(id, currentUser.id(), body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable String id) {
        conversationService.delete(id, currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
