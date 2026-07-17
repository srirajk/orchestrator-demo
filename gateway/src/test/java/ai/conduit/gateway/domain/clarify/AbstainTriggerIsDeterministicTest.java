package ai.conduit.gateway.domain.clarify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The form-show trigger is DETERMINISTIC: a structured form is offered iff the pipeline already
 * abstained (routing abstain OR grounding AMBIGUOUS) AND at least one ENTITLED candidate exists. No LLM,
 * no randomness — the deterministic-clarify hard rule (CLAUDE.md §4e) extends to the structured surface.
 */
class AbstainTriggerIsDeterministicTest {

    @Test
    void formShownOnlyWhenAbstainedAndCandidatesExist() {
        assertThat(ClarificationTrigger.shouldOfferForm(true, 1)).isTrue();
        assertThat(ClarificationTrigger.shouldOfferForm(true, 3)).isTrue();

        assertThat(ClarificationTrigger.shouldOfferForm(false, 1)).isFalse();  // not abstained → no form
        assertThat(ClarificationTrigger.shouldOfferForm(false, 5)).isFalse();
        assertThat(ClarificationTrigger.shouldOfferForm(true, 0)).isFalse();   // abstained but nothing to offer
    }

    @Test
    void triggerIsAPureFunction_sameInputsSameOutput_noHiddenState() {
        // Called many times with identical inputs — always identical output (no LLM, no counter, no clock).
        for (int i = 0; i < 1000; i++) {
            assertThat(ClarificationTrigger.shouldOfferForm(true, 2)).isTrue();
            assertThat(ClarificationTrigger.shouldOfferForm(false, 2)).isFalse();
        }
    }
}
