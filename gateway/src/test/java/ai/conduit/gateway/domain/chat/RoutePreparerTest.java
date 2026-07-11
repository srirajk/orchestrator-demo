package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.api.v1.chat.dto.ChatRequest;
import ai.conduit.gateway.api.v1.chat.dto.Message;
import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundSourceKind;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedMention;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundingResult;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionAligner;
import ai.conduit.gateway.synthesis.input.MentionSource;
import ai.conduit.gateway.synthesis.input.MentionSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Piece-3 preparation pipeline ({@link RoutePreparer}) with the grounding service
 * and manifest store mocked, so the masking / widen / relaxation control-flow is exercised
 * deterministically without a coverage service or an embedding index. World-B: the fixtures use only
 * generic manifest keys + the user's own verbatim words; the class under test emits a config-driven
 * neutral deictic, never a domain word.
 */
class RoutePreparerTest {

    private static final String MASK = "the subject";
    // Generic English function/request words — the config near-empty policy (no domain vocabulary).
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "for", "of", "to", "me", "my", "show", "give", "pull", "and", "vs",
            "what", "whats", "is", "on", "please", "then", "status");

    private final DomainManifestStore manifestStore = mock(DomainManifestStore.class);
    private final ReferenceGroundingService grounding = mock(ReferenceGroundingService.class);

    private RoutePreparer preparer(String maskToken, int minContentTokens) {
        RoutePreparationPolicy policy = new RoutePreparationPolicy(maskToken, STOPWORDS, minContentTokens);
        return new RoutePreparer(manifestStore, grounding, policy, 4);
    }

    private RoutePreparer preparer() {
        return preparer(MASK, 1);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    private static final EntityType REL = new EntityType(
            "relationship", "relationship_reference", "resolvable", "client relationship",
            null, "relationship", true, null);

    private static ChatRequest req(String... userTurns) {
        List<Message> msgs = java.util.Arrays.stream(userTurns)
                .map(c -> new Message("user", c)).toList();
        return new ChatRequest("m", msgs, false, null, null, null, null);
    }

    /** A RESOLVED_ALLOWED mention aligned in {@code messageContent} at {@code messageIndex}. */
    private static GroundedMention resolved(String messageContent, String verbatim, int messageIndex,
                                            MentionSource source, String canonicalId) {
        MentionSpan span = MentionAligner.align(messageContent, verbatim);
        Mention m = new Mention("relationship", "relationship_reference", verbatim, messageIndex, span, source);
        GroundedInterpretation gi = new GroundedInterpretation(
                canonicalId, "relationship", "sd", "iid", GroundStatus.RESOLVED_ALLOWED, null,
                GroundSourceKind.EXTRACTED_REFERENCE, source, String.valueOf(messageIndex), messageIndex,
                span, REL, null);
        return new GroundedMention(m, List.of(gi), true, false);
    }

    private void mockGround(GroundedReferenceSet set) {
        when(grounding.groundMentions(any(), any(), any(), any())).thenReturn(set);
    }

    private void stubManifest() {
        when(manifestStore.entityTypes()).thenReturn(List.of(REL));
        when(manifestStore.matchesNonCoverageScopedResolvableId(anyString())).thenReturn(false);
    }

    private PreparedRoute prepare(RoutePreparer p, ChatRequest request, String latest) {
        return p.prepare(request, latest, EntityBag.empty(), true, true,
                Principal.anonymous(), "t", "tok");
    }

    // ── Tests ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void masksEntitySpan_preservesCapabilityText_andGrantsRelaxation() {
        stubManifest();
        String content = "settlement status for Calderon Trust";
        mockGround(new GroundedReferenceSet(
                List.of(resolved(content, "Calderon Trust", 0, MentionSource.EXPLICIT, "REL-00099")),
                GroundingResult.allowed("REL-00099", REL, "sd", null)));

        PreparedRoute pr = prepare(preparer(), req(content), content);

        assertThat(pr.maskedRoutingText()).contains("settlement status");   // capability kept
        assertThat(pr.maskedRoutingText()).doesNotContain("Calderon");      // entity blanked
        assertThat(pr.maskedRoutingText()).contains(MASK);
        assertThat(pr.relaxationAllowed()).isTrue();                        // resolved + masked
        assertThat(pr.residualClass()).isEqualTo(PreparedRoute.ResidualClass.HAS_ACTION);
        assertThat(pr.maskDiagnostics().maskedSpanCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void masksAllOccurrences_mergesOverlappingNameAndIdSpans() {
        stubManifest();
        String content = "Calderon Trust (REL-00099) vs Calderon Trust";
        mockGround(new GroundedReferenceSet(
                List.of(resolved(content, "Calderon Trust", 0, MentionSource.EXPLICIT, "REL-00099")),
                GroundingResult.allowed("REL-00099", REL, "sd", null)));

        PreparedRoute pr = prepare(preparer(), req(content), content);

        assertThat(pr.maskedRoutingText()).doesNotContain("Calderon");   // both name occurrences masked
        assertThat(pr.maskedRoutingText()).doesNotContain("REL-00099");  // the id masked too
        assertThat(pr.maskedRoutingText()).contains(MASK);
    }

    @Test
    void emptyResidual_widensToFullMaskedWindow_thenHasAction() {
        stubManifest();
        // Latest turn is bare "Calderon Trust ?"; the prior turn carries the routable vocabulary.
        String prior = "show me the holdings";
        String latest = "Calderon Trust ?";
        mockGround(new GroundedReferenceSet(
                List.of(resolved(latest, "Calderon Trust", 1, MentionSource.EXPLICIT, "REL-00099")),
                GroundingResult.allowed("REL-00099", REL, "sd", null)));

        PreparedRoute pr = prepare(preparer(), req(prior, latest), latest);

        assertThat(pr.residualClass()).isEqualTo(PreparedRoute.ResidualClass.WIDENED);
        assertThat(pr.maskedRoutingText()).contains("holdings");        // widened window carries action
        assertThat(pr.maskedRoutingText()).doesNotContain("Calderon");  // entity still masked
        assertThat(pr.maskDiagnostics().maskMode()).isEqualTo("masked-widened");
        assertThat(pr.relaxationAllowed()).isTrue();
    }

    @Test
    void emptyResidual_evenWidened_isEmptyClass_forClarify() {
        stubManifest();
        String latest = "Calderon Trust ?";     // the only turn; nothing to widen into
        mockGround(new GroundedReferenceSet(
                List.of(resolved(latest, "Calderon Trust", 0, MentionSource.EXPLICIT, "REL-00099")),
                GroundingResult.allowed("REL-00099", REL, "sd", null)));

        PreparedRoute pr = prepare(preparer(), req(latest), latest);

        assertThat(pr.residualClass()).isEqualTo(PreparedRoute.ResidualClass.EMPTY);
        assertThat(pr.maskedRoutingText()).doesNotContain("Calderon");
    }

    @Test
    void alignmentMiss_onInWindowResolvedMention_forfeitsRelaxation() {
        stubManifest();
        String latest = "tell me about them";     // anaphora; the resolved name is not present here
        Mention m = new Mention("relationship", "relationship_reference", "Okafor Trust", 0, null,
                MentionSource.ANAPHORA);           // span == null → alignment miss, in-window
        GroundedInterpretation gi = new GroundedInterpretation(
                "REL-00500", "relationship", "sd", "iid", GroundStatus.RESOLVED_ALLOWED, null,
                GroundSourceKind.EXTRACTED_REFERENCE, MentionSource.ANAPHORA, "0", 0, null, REL, null);
        mockGround(new GroundedReferenceSet(
                List.of(new GroundedMention(m, List.of(gi), true, false)),
                GroundingResult.allowed("REL-00500", REL, "sd", null)));

        PreparedRoute pr = prepare(preparer(), req(latest), latest);

        assertThat(pr.maskDiagnostics().alignmentMisses()).isEqualTo(1);
        assertThat(pr.relaxationAllowed()).isFalse();   // masking incomplete → no trusted relaxation
    }

    @Test
    void nonCoverageScopedIdMatch_grantsRelaxation_withoutResolvedAllowedFocal() {
        when(manifestStore.entityTypes()).thenReturn(List.of(REL));
        when(manifestStore.matchesNonCoverageScopedResolvableId(anyString())).thenReturn(true);
        // No coverage interpretation → focal verdict NONE, yet the deterministic id_pattern earns it.
        mockGround(GroundedReferenceSet.none());

        PreparedRoute pr = prepare(preparer(), req("and for FND-12345?"), "and for FND-12345?");

        assertThat(pr.relaxationAllowed()).isTrue();    // V2.1 #4 — deterministic, not presence-trust
    }

    @Test
    void configMaskToken_thatCollidesWithEntityTypeWord_fallsBackToNeutralDeictic() {
        stubManifest();     // REL.display() = "client relationship", key = "relationship"
        String content = "settlement status for Calderon Trust";
        mockGround(new GroundedReferenceSet(
                List.of(resolved(content, "Calderon Trust", 0, MentionSource.EXPLICIT, "REL-00099")),
                GroundingResult.allowed("REL-00099", REL, "sd", null)));

        // Configure a banned token (an entity-type key) → RoutePreparer must not use it.
        PreparedRoute pr = prepare(preparer("relationship", 1), req(content), content);

        assertThat(pr.maskedRoutingText()).doesNotContain("relationship");   // banned token not used
        assertThat(pr.maskedRoutingText()).doesNotContain("Calderon");        // still masked
        assertThat(pr.maskedRoutingText()).contains("that");                  // neutral fallback deictic
    }
}
