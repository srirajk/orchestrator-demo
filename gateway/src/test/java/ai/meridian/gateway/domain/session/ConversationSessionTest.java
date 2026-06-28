package ai.meridian.gateway.domain.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSessionTest {

    @Test
    void emptySessionHasNullFields() {
        var s = ConversationSession.empty("conv-123");
        assertThat(s.conversationId()).isEqualTo("conv-123");
        assertThat(s.relationshipId()).isNull();
        assertThat(s.domainWorkflowState()).isNull();
        assertThat(s.authorizationCache()).isNull();
    }

    @Test
    void withDomainWorkflowState_updatesMap() {
        var s = ConversationSession.empty("conv-123");
        var updated = s.withDomainWorkflowState("wealth-management/private-banking", "satisfied");

        assertThat(updated.getDomainWorkflowState("wealth-management/private-banking")).isEqualTo("satisfied");
        assertThat(updated.conversationId()).isEqualTo("conv-123");
    }

    @Test
    void multipleSubDomainStates_areIndependent() {
        var s = ConversationSession.empty("conv-123")
            .withDomainWorkflowState("wealth-management/private-banking", "satisfied")
            .withDomainWorkflowState("asset-servicing/custody-operations", "awaiting_relationship_id");

        assertThat(s.getDomainWorkflowState("wealth-management/private-banking")).isEqualTo("satisfied");
        assertThat(s.getDomainWorkflowState("asset-servicing/custody-operations")).isEqualTo("awaiting_relationship_id");
    }

    @Test
    void withResults_incrementsTurnCount() {
        var s = ConversationSession.empty("conv-123");
        var updated = s.withResults("REL-00042", null, null);
        assertThat(updated.turnCount()).isEqualTo(1);
        assertThat(updated.relationshipId()).isEqualTo("REL-00042");
    }

    @Test
    void newConversationId_hasCleanState() {
        var s1 = ConversationSession.empty("conv-aaa")
            .withDomainWorkflowState("wealth-management/private-banking", "satisfied");
        var s2 = ConversationSession.empty("conv-bbb");

        assertThat(s2.getDomainWorkflowState("wealth-management/private-banking")).isNull();
        assertThat(s1.conversationId()).isEqualTo("conv-aaa");
    }
}
