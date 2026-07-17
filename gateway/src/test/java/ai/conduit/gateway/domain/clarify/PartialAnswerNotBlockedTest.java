package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.domain.clarify.ClarificationTrigger.Disposition;
import ai.conduit.gateway.domain.coverage.CoverageResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic partial-answer split (CLAUDE.md §4d, brief (f)): when at least one selected capability
 * produced a grounded answer, the answer is served and refinement rides the OOB lane as NON-BLOCKING
 * chips — a form is NEVER placed in front of an available answer. Only when nothing grounded AND the turn
 * abstained with entitled candidates does a BLOCKING form replace the no-service answer.
 */
class PartialAnswerNotBlockedTest {

    @Test
    void groundedAnswerAvailable_neverBlocksWithAForm() {
        // Even with an abstain signal and entitled candidates present, a grounded answer wins: chips, not a form.
        assertThat(ClarificationTrigger.decide(true, true, 3, 1, 2))
                .isEqualTo(Disposition.ANSWER_WITH_REFINEMENT_CHIPS);
        assertThat(ClarificationTrigger.decide(true, false, 0, 1, 2))
                .isEqualTo(Disposition.ANSWER_WITH_REFINEMENT_CHIPS);
    }

    @Test
    void noAnswerButAbstainedWithCandidates_blockingForm() {
        assertThat(ClarificationTrigger.decide(false, true, 2, 1, 2))
                .isEqualTo(Disposition.BLOCKING_FORM);
    }

    @Test
    void noAnswerNothingToOffer_noService() {
        assertThat(ClarificationTrigger.decide(false, true, 0, 1, 2))
                .isEqualTo(Disposition.NO_SERVICE);
        assertThat(ClarificationTrigger.decide(false, false, 3, 1, 2))
                .isEqualTo(Disposition.NO_SERVICE);
    }

    @Test
    void refinementDescriptorIsNonBlocking() {
        // The chips form is the same descriptor down-converted to non-blocking — beside the answer, not in front.
        ClarificationDescriptorFactory factory =
                new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");
        ClarificationDescriptor form = factory.forEntity("conv-1", "Refine?", "Refine?", "client", "REL-\\d+",
                List.of(new CoverageResource("REL-1", "Alpha Trust", "sd")), List.of(), "q", 1);
        assertThat(form.blocking()).isTrue();
        assertThat(form.asRefinement().blocking()).isFalse();
        assertThat(form.asRefinement().toStructuredInteraction().blocking()).isFalse();
    }
}
