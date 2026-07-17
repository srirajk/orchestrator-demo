package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.domain.clarify.ClarificationDescriptor.Submission;
import ai.conduit.gateway.domain.coverage.CoverageResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resume contract Phase 2 enforces on submit: the descriptor defines an OFFERED SET and a validation
 * predicate. A submission whose token is in the offered set is {@code IN_SET}; anything else — an id not
 * offered, an out-of-book id, blank, or free text — DEMOTES to free text, never silently grounded. This
 * is the second half of the enumeration-oracle defence: a client cannot submit an id it was never offered
 * and have it grounded.
 */
class SubmittedOptionValidationContractTest {

    private final ClarificationDescriptorFactory factory =
            new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");

    private ClarificationDescriptor descriptor() {
        List<CoverageResource> book = List.of(
                new CoverageResource("REL-1", "Alpha Trust", "sd"),
                new CoverageResource("REL-2", "Beta Holdings", "sd"));
        return factory.forEntity("conv-1", "Which one?", "Which one?", "client", "REL-\\d+",
                book, List.of(), "q", 1);
    }

    @Test
    void offeredSetIsExposed() {
        assertThat(descriptor().offeredValues()).containsExactly("REL-1", "REL-2");
    }

    @Test
    void inSetSubmissionIsAccepted() {
        assertThat(descriptor().validate("REL-1")).isEqualTo(Submission.IN_SET);
        assertThat(descriptor().validate("rel-2")).isEqualTo(Submission.IN_SET);  // case-insensitive
    }

    @Test
    void outOfSetSubmissionDemotesToFreeText() {
        // An id that was never offered (e.g. one the principal is not entitled to) is NOT grounded.
        assertThat(descriptor().validate("REL-9")).isEqualTo(Submission.DEMOTE_TO_FREE_TEXT);
        // Arbitrary free text demotes too.
        assertThat(descriptor().validate("the other one")).isEqualTo(Submission.DEMOTE_TO_FREE_TEXT);
        assertThat(descriptor().validate("")).isEqualTo(Submission.DEMOTE_TO_FREE_TEXT);
        assertThat(descriptor().validate(null)).isEqualTo(Submission.DEMOTE_TO_FREE_TEXT);
    }
}
