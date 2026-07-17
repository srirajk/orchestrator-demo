package ai.conduit.gateway.domain.clarify;

/**
 * The DETERMINISTIC form-show / partial-answer / loop-bound decision. Pure arithmetic over signals the
 * gateway already computed (the resolver's abstain flag, the grounding AMBIGUOUS verdict, the count of
 * ENTITLED candidates, the grounded-answer count, the lineage depth). No LLM, no randomness — the
 * deterministic-clarify hard rule (CLAUDE.md §4e) extends unchanged to the structured surface. This is
 * a re-render of the existing abstain/AMBIGUOUS signal, never a new decision.
 */
public final class ClarificationTrigger {

    private ClarificationTrigger() {}

    /**
     * The single form-show predicate: offer a structured clarification form iff the pipeline already
     * abstained (routing abstain OR grounding AMBIGUOUS) AND there is at least one ENTITLED candidate to
     * show. Zero candidates ⇒ no form (a bare no-service, never an empty picker). Deterministic.
     *
     * @param abstainOrAmbiguous the existing abstain signal (routing abstain OR grounding AMBIGUOUS)
     * @param entitledCandidateCount number of candidates that PASSED the entitlement CHECK
     */
    public static boolean shouldOfferForm(boolean abstainOrAmbiguous, int entitledCandidateCount) {
        return abstainOrAmbiguous && entitledCandidateCount >= 1;
    }

    /** The disposition a clarification-eligible turn resolves to (partial-answer split + loop bound). */
    public enum Disposition {
        /** A grounded answer is available — stream it; offer refinement as NON-BLOCKING chips. Never a form. */
        ANSWER_WITH_REFINEMENT_CHIPS,
        /** No answer, abstained, entitled candidates exist, depth under cap — a BLOCKING form replaces no-service. */
        BLOCKING_FORM,
        /** No answer and either nothing to offer or the lineage hit its depth cap — honest no-service text. */
        NO_SERVICE
    }

    /**
     * The deterministic split (CLAUDE.md §4d partial-result tolerance, brief (f)+(g)):
     * <ol>
     *   <li>A grounded answer exists ⇒ {@link Disposition#ANSWER_WITH_REFINEMENT_CHIPS} — NEVER put a
     *       form in front of an available answer; the answer streams and refinement rides the OOB lane
     *       as non-blocking chips.</li>
     *   <li>Else, if the turn abstained with ≥1 entitled candidate AND the (already-incremented) depth
     *       is within the cap ⇒ {@link Disposition#BLOCKING_FORM}.</li>
     *   <li>Else ⇒ {@link Disposition#NO_SERVICE} (nothing offerable, or the depth cap was reached —
     *       degrade to honest no-service text rather than loop).</li>
     * </ol>
     *
     * @param groundedAnswerAvailable at least one selected capability produced a grounded answer
     * @param abstainOrAmbiguous      the existing abstain signal
     * @param entitledCandidateCount  entitled candidates available to offer
     * @param depth                   this clarification's depth in the lineage (already incremented)
     * @param maxDepth                the hard depth cap
     */
    public static Disposition decide(boolean groundedAnswerAvailable, boolean abstainOrAmbiguous,
                                     int entitledCandidateCount, int depth, int maxDepth) {
        if (groundedAnswerAvailable) {
            return Disposition.ANSWER_WITH_REFINEMENT_CHIPS;
        }
        if (shouldOfferForm(abstainOrAmbiguous, entitledCandidateCount) && depth <= maxDepth) {
            return Disposition.BLOCKING_FORM;
        }
        return Disposition.NO_SERVICE;
    }

    /**
     * The depth a clarification takes given the depth INHERITED from the query lineage. A free-text /
     * "Other" resubmit inherits the prior depth and advances by one, so a user cannot be walked around
     * an unbounded clarify loop. A lineage with no prior clarification inherits 0 and takes depth 1.
     */
    public static int nextDepth(int inheritedDepth) {
        return Math.max(0, inheritedDepth) + 1;
    }

    /** True when a clarification at {@code depth} would exceed the cap — the caller must degrade to no-service. */
    public static boolean overCap(int depth, int maxDepth) {
        return depth > maxDepth;
    }
}
