package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundSourceKind;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedMention;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.Verdict;
import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.DomainManifestStore.IdentifiedReference;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.domain.manifest.SubDomainManifest;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionSet;
import ai.conduit.gateway.synthesis.input.MentionSource;
import ai.conduit.gateway.synthesis.input.MentionSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Piece 2 multi-reference grounding ({@link ReferenceGroundingService#groundMentions}):
 * EVERY mention, ALL of its manifest interpretations, full per-interpretation verdict data — with the
 * interim disposition rule that ONLY the focal mention's leading interpretation drives the terminal
 * lattice (non-focal mentions contribute nothing to disposition yet). Pure Mockito: the manifest store
 * and coverage client are mocked, so each Codex-listed failure mode is exercised deterministically.
 *
 * <p>Domain-shaped literals here live in TEST fixtures only (the World-B grep scans main, not test) —
 * they stand in for whatever a real manifest declares.
 */
class ReferenceGroundingMentionsTest {

    private static final EntityType REL = new EntityType(
            "relationship_id", "relationship_reference", "resolvable",
            "client relationship", "REL-\\d+", "relationship", true, null);
    private static final EntityType REL_B = new EntityType(
            "relationship_id", "relationship_reference", "resolvable",
            "institutional relationship", "REL-\\d+", "relationship", true, null);

    private static final DomainManifest.Coverage COVERAGE_A =
            new DomainManifest.Coverage("discoverA", "checkA", "resolveA", 0);
    private static final DomainManifest.Coverage COVERAGE_B =
            new DomainManifest.Coverage("discoverB", "checkB", "resolveB", 0);

    private static final SubDomainManifest SUB_A = new SubDomainManifest(
            "private-banking", "Private Banking", "wealth-management",
            List.of("relationship_id"), true, Map.of(), List.of());
    private static final SubDomainManifest SUB_B = new SubDomainManifest(
            "institutional-banking", "Institutional Banking", "wealth-management",
            List.of("relationship_id"), true, Map.of(), List.of());

    private static final Principal RM = new Principal(
            "rm_jane", "default", List.of("relationship_manager"),
            List.of(), Map.of("wealth", "confidential-pii"), List.of("wealth-private-banking"));

    private DomainManifestStore manifestStore;
    private CoverageClient coverageClient;
    private ReferenceGroundingService grounding;

    @BeforeEach
    void setUp() {
        manifestStore = mock(DomainManifestStore.class);
        coverageClient = mock(CoverageClient.class);
        grounding = new ReferenceGroundingService(manifestStore, coverageClient);
        // Default: nothing matches a non-coverage-scoped id pattern; individual tests override.
        when(manifestStore.matchesNonCoverageScopedResolvableId(anyString())).thenReturn(false);
        when(manifestStore.interpretationsForReference(anyString(), anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        grounding.shutdown();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private static Mention explicit(String verbatim, int idx) {
        return new Mention("relationship_id", "relationship_reference", verbatim, idx,
                new MentionSpan(0, verbatim.length()), MentionSource.EXPLICIT);
    }

    private static Mention anaphora(String verbatim, int idx) {
        return new Mention("relationship_id", "relationship_reference", verbatim, idx, null,
                MentionSource.ANAPHORA);
    }

    private static EntityBag bag(Mention... mentions) {
        return EntityBag.of(Map.of(), Map.of(), new MentionSet(List.of(mentions)));
    }

    private void interpretation(String reference, EntityType et, SubDomainManifest sub,
                                DomainManifest.Coverage coverage) {
        when(manifestStore.interpretationsForReference(eq("relationship_reference"), eq(reference)))
                .thenReturn(List.of(new IdentifiedReference(reference, et, sub, coverage)));
    }

    private void resolvesAllowed(DomainManifest.Coverage coverage, String reference, String canonicalId) {
        when(coverageClient.resolve(eq(reference), eq("relationship"), anyString(), eq(coverage), any()))
                .thenReturn(new CoverageResolveResult(true, canonicalId, canonicalId, List.of()));
        when(coverageClient.check(eq("rm_jane"), anyString(), eq(canonicalId), eq(coverage), any()))
                .thenReturn(CoverageCheckResult.ofAllowed());
    }

    private void resolvesDenied(DomainManifest.Coverage coverage, String reference, String canonicalId,
                               String reason) {
        when(coverageClient.resolve(eq(reference), eq("relationship"), anyString(), eq(coverage), any()))
                .thenReturn(new CoverageResolveResult(true, canonicalId, canonicalId, List.of()));
        when(coverageClient.check(eq("rm_jane"), anyString(), eq(canonicalId), eq(coverage), any()))
                .thenReturn(CoverageCheckResult.denied(reason));
    }

    private GroundedInterpretation only(GroundedMention gm) {
        assertThat(gm.interpretations()).hasSize(1);
        return gm.interpretations().get(0);
    }

    // ── multi-reference: two names of ONE entity key ──────────────────────────────────────────────

    @Test
    void twoNamesOfOneEntityKey_bothGroundIndependently() {
        interpretation("Whitman", REL, SUB_A, COVERAGE_A);
        interpretation("Calderon", REL, SUB_A, COVERAGE_A);
        resolvesAllowed(COVERAGE_A, "Whitman", "REL-00042");
        resolvesAllowed(COVERAGE_A, "Calderon", "REL-00099");

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("Whitman", 0), explicit("Calderon", 2)), RM, "default", "tok");

        assertThat(set.mentions()).hasSize(2);
        assertThat(only(set.mentions().get(0)).canonicalId()).isEqualTo("REL-00042");
        assertThat(only(set.mentions().get(1)).canonicalId()).isEqualTo("REL-00099");
        assertThat(set.allInterpretations()).allMatch(GroundedInterpretation::isAllowed);
    }

    // ── two typed ids → both ground; sourceKind = ID_PATTERN ─────────────────────────────────────

    @Test
    void twoTypedIds_bothGround_withIdPatternProvenance() {
        when(manifestStore.compiledIdPattern("REL-\\d+")).thenReturn(Pattern.compile("REL-\\d+"));
        interpretation("REL-100", REL, SUB_A, COVERAGE_A);
        interpretation("REL-200", REL, SUB_A, COVERAGE_A);
        resolvesAllowed(COVERAGE_A, "REL-100", "REL-100");
        resolvesAllowed(COVERAGE_A, "REL-200", "REL-200");

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("REL-100", 0), explicit("REL-200", 0)), RM, "default", "tok");

        assertThat(set.allInterpretations()).extracting(GroundedInterpretation::sourceKind)
                .containsOnly(GroundSourceKind.ID_PATTERN);
    }

    // ── one surface name valid in TWO sub-domains → two interpretations ───────────────────────────

    @Test
    void oneSurfaceName_validInTwoSubDomains_yieldsTwoInterpretations() {
        when(manifestStore.interpretationsForReference("relationship_reference", "Sterling"))
                .thenReturn(List.of(
                        new IdentifiedReference("Sterling", REL, SUB_A, COVERAGE_A),
                        new IdentifiedReference("Sterling", REL_B, SUB_B, COVERAGE_B)));
        resolvesAllowed(COVERAGE_A, "Sterling", "REL-00001");
        resolvesAllowed(COVERAGE_B, "Sterling", "REL-00002");

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("Sterling", 0)), RM, "default", "tok");

        assertThat(set.mentions()).hasSize(1);
        GroundedMention gm = set.mentions().get(0);
        assertThat(gm.interpretations()).hasSize(2);
        assertThat(gm.interpretations()).extracting(GroundedInterpretation::subDomainId)
                .containsExactly("private-banking", "institutional-banking"); // store order preserved
        assertThat(gm.interpretations()).extracting(GroundedInterpretation::interpretationId)
                .doesNotHaveDuplicates();
        // Focal verdict = the LEADING interpretation only (interim single-lattice).
        assertThat(set.focalVerdict().verdict()).isEqualTo(Verdict.RESOLVED_ALLOWED);
        assertThat(set.focalVerdict().resolvedId()).isEqualTo("REL-00001");
    }

    // ── allow + deny ACROSS interpretations of one mention (both kept; no request-global deny) ────

    @Test
    void allowAndDenyAcrossInterpretations_bothRetained() {
        when(manifestStore.interpretationsForReference("relationship_reference", "Smith"))
                .thenReturn(List.of(
                        new IdentifiedReference("Smith", REL, SUB_A, COVERAGE_A),
                        new IdentifiedReference("Smith", REL_B, SUB_B, COVERAGE_B)));
        resolvesAllowed(COVERAGE_A, "Smith", "REL-00010");
        resolvesDenied(COVERAGE_B, "Smith", "REL-00011", "not-covered");

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("Smith", 0)), RM, "default", "tok");

        List<GroundedInterpretation> gis = set.mentions().get(0).interpretations();
        assertThat(gis).extracting(GroundedInterpretation::status)
                .containsExactly(GroundStatus.RESOLVED_ALLOWED, GroundStatus.DENIED);
        assertThat(gis.get(1).denialReason()).isEqualTo("not-covered");
        // Leading interpretation is ALLOWED → focal verdict is ALLOWED, the deny does not terminate.
        assertThat(set.focalVerdict().verdict()).isEqualTo(Verdict.RESOLVED_ALLOWED);
    }

    // ── denial in a NON-FOCAL (incidental) mention must NOT terminate under interim policy ────────

    @Test
    void nonFocalDenial_doesNotDriveDisposition() {
        // Focal = the latest explicit mention (Whitman, idx 2, in-book); incidental = an earlier
        // carried anaphora (Okafor, idx 0, out-of-book). The focal verdict must stay ALLOWED.
        interpretation("Whitman", REL, SUB_A, COVERAGE_A);
        interpretation("Okafor", REL, SUB_A, COVERAGE_A);
        resolvesAllowed(COVERAGE_A, "Whitman", "REL-00042");
        resolvesDenied(COVERAGE_A, "Okafor", "REL-00188", "not-covered");

        GroundedReferenceSet set = grounding.groundMentions(
                bag(anaphora("Okafor", 0), explicit("Whitman", 2)), RM, "default", "tok");

        assertThat(set.focalMention().mention().verbatimText()).isEqualTo("Whitman");
        assertThat(set.focalVerdict().verdict()).isEqualTo(Verdict.RESOLVED_ALLOWED);
        // The out-of-book denial is still RECORDED (Piece 3 masking consumes it) — just not terminal.
        assertThat(set.allInterpretations()).anyMatch(GroundedInterpretation::isDenied);
    }

    // ── one UNAVAILABLE interpretation on the FOCAL mention → fail-closed ─────────────────────────

    @Test
    void unavailableFocalInterpretation_failsClosed() {
        interpretation("Whitman", REL, SUB_A, COVERAGE_A);
        when(coverageClient.resolve(eq("Whitman"), anyString(), anyString(), eq(COVERAGE_A), any()))
                .thenThrow(new CoverageClient.CoverageUnavailableException("coverage down"));

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("Whitman", 0)), RM, "default", "tok");

        assertThat(only(set.mentions().get(0)).status()).isEqualTo(GroundStatus.UNAVAILABLE);
        assertThat(set.focalVerdict().verdict()).isEqualTo(Verdict.UNAVAILABLE);
    }

    // ── NOT_FOUND → demote to content (no canonical id, not a denial) ────────────────────────────

    @Test
    void notFoundReference_isNotFound_notDenied() {
        interpretation("Tesla", REL, SUB_A, COVERAGE_A);
        when(coverageClient.resolve(eq("Tesla"), anyString(), anyString(), eq(COVERAGE_A), any()))
                .thenReturn(new CoverageResolveResult(false, null, null, List.of()));

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("Tesla", 0)), RM, "default", "tok");

        GroundedInterpretation gi = only(set.mentions().get(0));
        assertThat(gi.status()).isEqualTo(GroundStatus.NOT_FOUND);
        assertThat(gi.canonicalId()).isNull();
        assertThat(set.focalVerdict().verdict()).isEqualTo(Verdict.NOT_FOUND);
    }

    // ── budget: interpretations-per-mention cap ──────────────────────────────────────────────────

    @Test
    void budget_capsInterpretationsPerMention() {
        grounding = new ReferenceGroundingService(manifestStore, coverageClient,
                new GroundingBudget(8, 1, 8, 8000));
        when(manifestStore.interpretationsForReference("relationship_reference", "Sterling"))
                .thenReturn(List.of(
                        new IdentifiedReference("Sterling", REL, SUB_A, COVERAGE_A),
                        new IdentifiedReference("Sterling", REL_B, SUB_B, COVERAGE_B)));
        resolvesAllowed(COVERAGE_A, "Sterling", "REL-00001");

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("Sterling", 0)), RM, "default", "tok");

        assertThat(set.mentions().get(0).interpretations()).hasSize(1); // capped from 2 to 1
        verify(coverageClient, times(1)).resolve(eq("Sterling"), anyString(), anyString(), eq(COVERAGE_A), any());
    }

    // ── budget: max-mentions cap ─────────────────────────────────────────────────────────────────

    @Test
    void budget_capsMentions() {
        grounding = new ReferenceGroundingService(manifestStore, coverageClient,
                new GroundingBudget(1, 4, 8, 8000));
        interpretation("Whitman", REL, SUB_A, COVERAGE_A);
        interpretation("Calderon", REL, SUB_A, COVERAGE_A);
        resolvesAllowed(COVERAGE_A, "Whitman", "REL-00042");

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("Whitman", 0), explicit("Calderon", 2)), RM, "default", "tok");

        assertThat(set.mentions()).hasSize(1); // second mention dropped by the cap
    }

    // ── dedupe: the SAME (endpoint, resolveType, reference) resolves ONCE across mentions ────────

    @Test
    void dedupe_sharedReferenceResolvesOnce() {
        interpretation("Whitman", REL, SUB_A, COVERAGE_A);
        resolvesAllowed(COVERAGE_A, "Whitman", "REL-00042");

        GroundedReferenceSet set = grounding.groundMentions(
                bag(explicit("Whitman", 0), anaphora("Whitman", 2)), RM, "default", "tok");

        // Two mentions, one shared coverage task: RESOLVE + CHECK each run exactly once.
        verify(coverageClient, times(1)).resolve(eq("Whitman"), anyString(), anyString(), eq(COVERAGE_A), any());
        verify(coverageClient, times(1)).check(anyString(), anyString(), eq("REL-00042"), eq(COVERAGE_A), any());
        assertThat(set.mentions()).hasSize(2);
        assertThat(set.allInterpretations()).allMatch(gi -> "REL-00042".equals(gi.canonicalId()));
    }

    // ── deterministic aggregation when ONE interpretation's coverage is down ──────────────────────

    @Test
    void deterministicAggregation_whenOneInterpretationCoverageDown() {
        when(manifestStore.interpretationsForReference("relationship_reference", "Sterling"))
                .thenReturn(List.of(
                        new IdentifiedReference("Sterling", REL, SUB_A, COVERAGE_A),
                        new IdentifiedReference("Sterling", REL_B, SUB_B, COVERAGE_B)));
        resolvesAllowed(COVERAGE_A, "Sterling", "REL-00001");
        when(coverageClient.resolve(eq("Sterling"), anyString(), anyString(), eq(COVERAGE_B), any()))
                .thenThrow(new CoverageClient.CoverageUnavailableException("B down"));

        GroundedReferenceSet first = grounding.groundMentions(
                bag(explicit("Sterling", 0)), RM, "default", "tok");
        GroundedReferenceSet second = grounding.groundMentions(
                bag(explicit("Sterling", 0)), RM, "default", "tok");

        assertThat(first.mentions().get(0).interpretations()).extracting(GroundedInterpretation::status)
                .containsExactly(GroundStatus.RESOLVED_ALLOWED, GroundStatus.UNAVAILABLE);
        // Deterministic: a re-run yields the identical status vector.
        assertThat(second.mentions().get(0).interpretations()).extracting(GroundedInterpretation::status)
                .containsExactly(GroundStatus.RESOLVED_ALLOWED, GroundStatus.UNAVAILABLE);
    }

    // ── V2.1 #4: a resolvable but resource_scoped:false id_pattern match is RECORDED, not acted on ─

    @Test
    void nonCoverageScopedIdPatternMatch_isRecordedWithoutGrounding() {
        when(manifestStore.matchesNonCoverageScopedResolvableId("FND-000123")).thenReturn(true);
        // No coverage interpretation for a non-coverage-scoped type.
        Mention m = new Mention("fund_id", "fund_reference", "FND-000123", 0,
                new MentionSpan(0, 10), MentionSource.EXPLICIT);

        GroundedReferenceSet set = grounding.groundMentions(bag(m), RM, "default", "tok");

        GroundedMention gm = set.mentions().get(0);
        assertThat(gm.nonCoverageScopedIdPatternMatch()).isTrue();
        assertThat(gm.interpretations()).isEmpty();
        // No groundable interpretation anywhere → focal verdict NONE (nothing drives the lattice).
        assertThat(set.focalVerdict().verdict()).isEqualTo(Verdict.NONE);
    }

    // ── no mentions → empty set / NONE (not an omission-as-status) ───────────────────────────────

    @Test
    void noMentions_returnsNone() {
        GroundedReferenceSet set = grounding.groundMentions(EntityBag.empty(), RM, "default", "tok");
        assertThat(set.isEmpty()).isTrue();
        assertThat(set.focalVerdict().verdict()).isEqualTo(Verdict.NONE);
    }
}
