package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundSourceKind;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedMention;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundingResult;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionSource;
import ai.conduit.gateway.synthesis.input.MentionSpan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure derivation tests for {@link EntityBindingSet} — the substrate of multi-entity COMPARE. No Spring,
 * no coverage calls: a hand-built {@link GroundedReferenceSet} feeds {@link EntityBindingSet#derive} and
 * the deterministic rules are asserted directly. Domain-shaped literals live only in TEST fixtures (the
 * World-B grep scans main, not test) — they stand in for whatever a real manifest declares.
 */
class EntityBindingSetTest {

    private static final EntityType REL = new EntityType(
            "relationship_id", "relationship_reference", "resolvable",
            "client relationship", "REL-\\d+", "relationship", true, null);
    private static final EntityType POLICY = new EntityType(
            "policy_id", "policy_reference", "resolvable",
            "insurance policy", "POL-\\d+", "policy", true, null);

    private static final int CURRENT_TURN = 4;
    private static final int PRIOR_TURN = 2;

    private static Mention explicit(String verbatim, int turn) {
        return new Mention(REL.key(), REL.extractAs(), verbatim, turn,
                new MentionSpan(0, verbatim.length()), MentionSource.EXPLICIT);
    }

    private static Mention anaphora(String verbatim, int turn) {
        return new Mention(REL.key(), REL.extractAs(), verbatim, turn, null, MentionSource.ANAPHORA);
    }

    private static GroundedInterpretation interp(EntityType et, String canonicalId, String canonicalName,
                                                 GroundStatus status, Mention m) {
        return new GroundedInterpretation(
                canonicalId, canonicalName, et.key(), "private-banking",
                "resolve|" + et.resolveType() + "|" + (canonicalId == null ? "?" : canonicalId),
                status, status == GroundStatus.DENIED ? "not-covered" : null,
                GroundSourceKind.EXTRACTED_REFERENCE, m.source(),
                String.valueOf(m.messageIndex()), m.messageIndex(), m.span(), et, null);
    }

    private static GroundedMention mention(Mention m, GroundedInterpretation... interps) {
        return new GroundedMention(m, List.of(interps), false, false);
    }

    private static GroundedReferenceSet set(GroundedMention... mentions) {
        return new GroundedReferenceSet(List.of(mentions), GroundingResult.none());
    }

    @Test
    void twoExplicitResolvedMentions_currentTurn_formTwoOrderedBindings() {
        Mention whitman = explicit("Whitman Family Office", CURRENT_TURN);
        Mention calderon = explicit("Calderon Trust", CURRENT_TURN);
        EntityBindingSet bindings = EntityBindingSet.derive(set(
                mention(whitman, interp(REL, "REL-00042", "Whitman Family Office", GroundStatus.RESOLVED_ALLOWED, whitman)),
                mention(calderon, interp(REL, "REL-00099", "Calderon Trust", GroundStatus.RESOLVED_ALLOWED, calderon))));

        assertThat(bindings.multiEntity()).isTrue();
        assertThat(bindings.bindings()).extracting(EntityBinding::canonicalId)
                .containsExactly("REL-00042", "REL-00099");           // = user order (mention order)
        assertThat(bindings.bindings().get(0).userVerbatim()).isEqualTo("Whitman Family Office");
        assertThat(bindings.allowedCount()).isEqualTo(2);
    }

    @Test
    void anaphoraMention_neverFormsABinding() {
        Mention whitman = explicit("Whitman", CURRENT_TURN);
        Mention carried = anaphora("that account", PRIOR_TURN);
        EntityBindingSet bindings = EntityBindingSet.derive(set(
                mention(whitman, interp(REL, "REL-00042", "Whitman Family Office", GroundStatus.RESOLVED_ALLOWED, whitman)),
                mention(carried, interp(REL, "REL-00099", "Calderon Trust", GroundStatus.RESOLVED_ALLOWED, carried))));

        // Only the EXPLICIT latest-turn mention survives → a single binding → feature INERT (S8/S9 safe).
        assertThat(bindings.multiEntity()).isFalse();
    }

    @Test
    void priorTurnExplicitMention_excluded_onlyLatestTurnBinds() {
        Mention priorWhitman = explicit("Whitman", PRIOR_TURN);
        Mention currentCalderon = explicit("Calderon Trust", CURRENT_TURN);
        EntityBindingSet bindings = EntityBindingSet.derive(set(
                mention(priorWhitman, interp(REL, "REL-00042", "Whitman", GroundStatus.RESOLVED_ALLOWED, priorWhitman)),
                mention(currentCalderon, interp(REL, "REL-00099", "Calderon", GroundStatus.RESOLVED_ALLOWED, currentCalderon))));

        assertThat(bindings.multiEntity()).isFalse();                 // a carried prior-turn client never widens fan-out
    }

    @Test
    void sameIdTwice_dedupesToOneBinding() {
        Mention a = explicit("Whitman", CURRENT_TURN);
        Mention b = explicit("the Whitman Family Office", CURRENT_TURN);
        EntityBindingSet bindings = EntityBindingSet.derive(set(
                mention(a, interp(REL, "REL-00042", "Whitman Family Office", GroundStatus.RESOLVED_ALLOWED, a)),
                mention(b, interp(REL, "REL-00042", "Whitman Family Office", GroundStatus.RESOLVED_ALLOWED, b))));

        assertThat(bindings.multiEntity()).isFalse();                 // one distinct id → one binding
    }

    @Test
    void deniedAndUnavailableBothFormBindings() {
        Mention okafor = explicit("the Okafor account", CURRENT_TURN);
        Mention whitman = explicit("Whitman Family Office", CURRENT_TURN);
        EntityBindingSet bindings = EntityBindingSet.derive(set(
                mention(okafor, interp(REL, "REL-00188", "Okafor", GroundStatus.DENIED, okafor)),
                mention(whitman, interp(REL, "REL-00042", "Whitman", GroundStatus.RESOLVED_ALLOWED, whitman))));

        assertThat(bindings.multiEntity()).isTrue();
        assertThat(bindings.allowedCount()).isEqualTo(1);
        assertThat(bindings.anyDenied()).isTrue();
        assertThat(bindings.firstWithheld().canonicalId()).isEqualTo("REL-00188");
        assertThat(bindings.firstWithheld().terminalDenialReason()).isEqualTo("not-covered");
    }

    @Test
    void notFoundAndAmbiguous_neverBind() {
        Mention notFound = explicit("Nonexistent Trust", CURRENT_TURN);
        Mention ambiguous = explicit("the Smith account", CURRENT_TURN);
        EntityBindingSet bindings = EntityBindingSet.derive(set(
                mention(notFound, interp(REL, null, null, GroundStatus.NOT_FOUND, notFound)),
                mention(ambiguous, interp(REL, null, null, GroundStatus.AMBIGUOUS, ambiguous))));

        assertThat(bindings.multiEntity()).isFalse();
        assertThat(bindings.bindings()).isEmpty();
    }

    @Test
    void dominantEntityKeyWins_whenTwoTypesPresent() {
        // Two relationships (≥2) and one policy: the relationship type dominates → those bindings.
        Mention whitman = explicit("Whitman", CURRENT_TURN);
        Mention calderon = explicit("Calderon", CURRENT_TURN);
        Mention policy = new Mention(POLICY.key(), POLICY.extractAs(), "POL-1", CURRENT_TURN,
                new MentionSpan(0, 5), MentionSource.EXPLICIT);
        EntityBindingSet bindings = EntityBindingSet.derive(set(
                mention(whitman, interp(REL, "REL-00042", "Whitman", GroundStatus.RESOLVED_ALLOWED, whitman)),
                mention(calderon, interp(REL, "REL-00099", "Calderon", GroundStatus.RESOLVED_ALLOWED, calderon)),
                mention(policy, interp(POLICY, "POL-1", "Policy One", GroundStatus.RESOLVED_ALLOWED, policy))));

        assertThat(bindings.entityKey()).isEqualTo(REL.key());
        assertThat(bindings.bindings()).extracting(EntityBinding::canonicalId)
                .containsExactly("REL-00042", "REL-00099");
    }

    @Test
    void emptyOrNullSet_isInert() {
        assertThat(EntityBindingSet.derive(null).multiEntity()).isFalse();
        assertThat(EntityBindingSet.derive(GroundedReferenceSet.none()).multiEntity()).isFalse();
    }
}
