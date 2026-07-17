package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.domain.clarify.ClarificationTrigger.Disposition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clarification loop is hard-bounded per query lineage (brief (g)). Depth is inherited from the
 * outstanding descriptor and advanced by one on each free-text / "Other" resubmit, so a user cannot be
 * walked around an unbounded clarify loop. At the cap the turn degrades to honest no-service text instead
 * of offering yet another form.
 */
class ClarifyDepthCapTest {

    private static final int MAX = 2;

    @Test
    void depthAdvancesByOneAndInheritsAcrossTurns() {
        int firstTurn = ClarificationTrigger.nextDepth(0);      // no prior clarification
        assertThat(firstTurn).isEqualTo(1);

        int secondTurn = ClarificationTrigger.nextDepth(firstTurn);   // "Other"/free-text inherits depth 1
        assertThat(secondTurn).isEqualTo(2);

        int thirdTurn = ClarificationTrigger.nextDepth(secondTurn);   // inherits depth 2
        assertThat(thirdTurn).isEqualTo(3);
    }

    @Test
    void withinCapOffersForm_atCapStillOffers_overCapDegrades() {
        // depth 1 and 2 are within the cap → a blocking form is allowed.
        assertThat(ClarificationTrigger.decide(false, true, 2, 1, MAX)).isEqualTo(Disposition.BLOCKING_FORM);
        assertThat(ClarificationTrigger.decide(false, true, 2, 2, MAX)).isEqualTo(Disposition.BLOCKING_FORM);
        // depth 3 (a third clarification in the lineage) is over the cap → degrade to no-service.
        assertThat(ClarificationTrigger.decide(false, true, 2, 3, MAX)).isEqualTo(Disposition.NO_SERVICE);
    }

    @Test
    void overCapPredicate() {
        assertThat(ClarificationTrigger.overCap(2, MAX)).isFalse();
        assertThat(ClarificationTrigger.overCap(3, MAX)).isTrue();
    }
}
