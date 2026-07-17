package ai.conduit.chat.chat;

import ai.conduit.chat.auth.AccessTokenService;
import ai.conduit.chat.auth.CurrentUser;
import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.conversation.ConversationService;
import ai.conduit.chat.memory.ContextAssembler;
import ai.conduit.chat.message.Message;
import ai.conduit.chat.message.MessageService;
import ai.conduit.chat.web.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Phase-2 RESUME endpoint {@link ClarifyController}: a resume must carry an answer;
 * the answer is folded into a user turn and re-driven with the form's nonce + chip selection; and a
 * gateway rejection rolls back the just-persisted turn (no orphan). The controller is exercised directly
 * with mocked collaborators — no Spring context, no gateway.
 */
class ClarifyControllerTest {

    private CurrentUser currentUser;
    private AccessTokenService accessTokenService;
    private ConversationService conversationService;
    private MessageService messageService;
    private ContextAssembler contextAssembler;
    private GatewayClient gatewayClient;
    private ChatStreamService chatStreamService;
    private ClarifyController controller;

    private final Conversation conversation = mock(Conversation.class);
    private final Message userMessage = mock(Message.class);

    @BeforeEach
    void setUp() {
        currentUser = mock(CurrentUser.class);
        accessTokenService = mock(AccessTokenService.class);
        conversationService = mock(ConversationService.class);
        messageService = mock(MessageService.class);
        contextAssembler = mock(ContextAssembler.class);
        gatewayClient = mock(GatewayClient.class);
        chatStreamService = mock(ChatStreamService.class);
        controller = new ClarifyController(currentUser, accessTokenService, conversationService,
                messageService, contextAssembler, gatewayClient, chatStreamService);

        when(currentUser.id()).thenReturn("u1");
        when(accessTokenService.currentAccessToken()).thenReturn("tok");
        when(conversation.getId()).thenReturn("conv-1");
        when(conversationService.getOwnedOrThrow("conv-1", "u1")).thenReturn(conversation);
        when(messageService.append(eq("conv-1"), eq("u1"), eq(MessageService.ROLE_USER), any()))
                .thenReturn(userMessage);
        when(contextAssembler.assemble(any(), any())).thenReturn(List.of());
    }

    @Test
    void missingAnswer_isBadRequest_andNeverTouchesGateway() {
        var req = new ClarifyResolveRequest("conv-1", "n1", null, null);
        assertThatThrownBy(() -> controller.resolve(req))
                .isInstanceOf(BadRequestException.class);
        verify(gatewayClient, never()).openChatStream(any(), any(), any(), any(), any());
        verify(messageService, never()).append(any(), any(), any(), any());
    }

    @Test
    void chipSelection_foldsAnswer_andReDrivesWithNonceAndSelection() {
        when(gatewayClient.openChatStream(eq("tok"), eq("conv-1"), any(), eq("n1"), eq("REL-00042")))
                .thenReturn(new GatewayStream(200, new ByteArrayInputStream(new byte[0])));

        var req = new ClarifyResolveRequest("conv-1", "n1", "REL-00042", null);
        var response = controller.resolve(req);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // The chosen value is folded as the user turn and carried as the selection header.
        verify(messageService).append("conv-1", "u1", MessageService.ROLE_USER, "REL-00042");
        verify(gatewayClient).openChatStream("tok", "conv-1", List.of(), "n1", "REL-00042");
    }

    @Test
    void freeText_foldsFreeText_andCarriesNoSelectionHeader() {
        when(gatewayClient.openChatStream(eq("tok"), eq("conv-1"), any(), eq("n1"), eq(null)))
                .thenReturn(new GatewayStream(200, new ByteArrayInputStream(new byte[0])));

        var req = new ClarifyResolveRequest("conv-1", "n1", null, "the Geneva mandate");
        controller.resolve(req);

        verify(messageService).append("conv-1", "u1", MessageService.ROLE_USER, "the Geneva mandate");
        verify(gatewayClient).openChatStream("tok", "conv-1", List.of(), "n1", null);
    }

    @Test
    void gatewayRejection_rollsBackTheOrphanUserTurn() {
        when(gatewayClient.openChatStream(any(), any(), any(), any(), any()))
                .thenReturn(new GatewayStream(403, null));   // gateway refuses the resume

        var req = new ClarifyResolveRequest("conv-1", "n1", "REL-00042", null);
        var response = controller.resolve(req);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(messageService).delete(userMessage);   // no orphan left behind
    }
}
