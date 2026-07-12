package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundSourceKind;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedMention;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundingResult;
import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionSource;
import ai.conduit.gateway.synthesis.input.MentionSpan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure tests for {@link ReferenceGroundingService#detectUnboundReferences} — the Compare-CLARIFY predicate
 * (Tier A grounding-status + Tier B resolve-confirmation) and the over-clarify guardrail matrix, with a
 * mocked {@link CoverageClient}. World-B: domain-shaped literals are TEST fixtures only (the grep scans
 * main). {@link DomainManifestStore} is unused by the method under test, so it is a bare mock.
 */
class ReferenceGroundingServiceDetectUnboundTest {

    private static final EntityType REL = new EntityType(
            "relationship_id", "relationship_reference", "resolvable",
            "client relationship", "REL-\\d+", "relationship", true, null);
    private static final DomainManifest.Coverage COVERAGE =
            new DomainManifest.Coverage("http://wealth/discover", "http://wealth/check", "http://wealth/resolve", 60);
    private static final int TURN = 3;
    private static final String TENANT = "default";
    private static final String TOKEN = "tok";

    private final CoverageClient coverage = mock(CoverageClient.class);
    private final ReferenceGroundingService svc =
            new ReferenceGroundingService(mock(DomainManifestStore.class), coverage, GroundingBudget.defaults());

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private static Mention explicit(String verbatim) {
        return new Mention(REL.key(), REL.extractAs(), verbatim, TURN,
                new MentionSpan(0, verbatim.length()), MentionSource.EXPLICIT);
    }

    private static Mention anaphora(String verbatim) {
        return new Mention(REL.key(), REL.extractAs(), verbatim, TURN - 1, null, MentionSource.ANAPHORA);
    }

    private static GroundedInterpretation interp(String canonicalId, String canonicalName,
                                                 GroundStatus status, Mention m) {
        return new GroundedInterpretation(
                canonicalId, canonicalName, REL.key(), "private-banking",
                "resolve|relationship|" + (canonicalId == null ? "?" : canonicalId), status,
                status == GroundStatus.DENIED ? "not-covered" : null,
                GroundSourceKind.EXTRACTED_REFERENCE, m.source(),
                String.valueOf(m.messageIndex()), m.messageIndex(), m.span(), REL, COVERAGE);
    }

    private static GroundedMention gm(Mention m, GroundedInterpretation... interps) {
        return new GroundedMention(m, List.of(interps), false, false);
    }

    private static GroundedReferenceSet set(GroundedMention... mentions) {
        return new GroundedReferenceSet(List.of(mentions), GroundingResult.none());
    }

    /** A grounded set whose sole binding is Whitman ALLOWED (REL-00042), plus any extra mentions. */
    private GroundedReferenceSet withWhitmanBound(GroundedMention... extra) {
        Mention whitman = explicit("Whitman Family Office");
        GroundedMention bound = gm(whitman, interp("REL-00042", "Whitman Family Office",
                GroundStatus.RESOLVED_ALLOWED, whitman));
        GroundedMention[] all = new GroundedMention[extra.length + 1];
        all[0] = bound;
        System.arraycopy(extra, 0, all, 1, extra.length);
        return set(all);
    }

    private static EntityBindingSet allBindings(GroundedReferenceSet s) {
        return EntityBindingSet.deriveAll(s);
    }

    private static CoverageResolveResult resolved(String id, String name) {
        return new CoverageResolveResult(true, id, name, List.of());
    }
    private static CoverageResolveResult ambiguous() {
        return new CoverageResolveResult(false, null, null, List.of(
                new CoverageResolveResult.ResolveCandidate("REL-1", "A"),
                new CoverageResolveResult.ResolveCandidate("REL-2", "B")));
    }
    private static CoverageResolveResult notFound() {
        return new CoverageResolveResult(false, null, null, List.of());
    }

    // ── Tier A — grounding-status detection (no I/O) ────────────────────────────────────────────────

    @Test
    void tierA_explicitAllNotFound_properNoun_detects_withNoResolveCall() {
        Mention tesla = explicit("Tesla");
        List<UnboundReference> unbound = svc.detectUnboundReferences(
                withWhitmanBound(gm(tesla, interp(null, null, GroundStatus.NOT_FOUND, tesla))),
                allBindings(withWhitmanBound(gm(tesla, interp(null, null, GroundStatus.NOT_FOUND, tesla)))),
                "compare Whitman Family Office and Tesla", TENANT, TOKEN);

        assertThat(unbound).extracting(UnboundReference::verbatim).containsExactly("Tesla");
        assertThat(unbound.get(0).resolvedId()).isNull();      // Tier A never resolves
        verifyNoInteractions(coverage);                        // proved zero new I/O
    }

    @Test
    void tierA_capabilityNoun_neverACandidate() {
        // "performance" carries no proper-noun token AND is not in the raw capitalized runs → no candidate.
        Mention perf = new Mention(REL.key(), REL.extractAs(), "performance", TURN,
                new MentionSpan(0, 11), MentionSource.EXPLICIT);
        GroundedReferenceSet s = withWhitmanBound(gm(perf, interp(null, null, GroundStatus.NOT_FOUND, perf)));
        assertThat(svc.detectUnboundReferences(s, allBindings(s),
                "compare the performance of Whitman Family Office", TENANT, TOKEN)).isEmpty();
    }

    @Test
    void tierA_ambiguousInterps_capitalizedVerbatim_detectsWithAmbiguousFlag() {
        Mention smith = explicit("Smith");
        GroundedReferenceSet s = withWhitmanBound(gm(smith, interp(null, null, GroundStatus.AMBIGUOUS, smith)));
        List<UnboundReference> unbound = svc.detectUnboundReferences(s, allBindings(s),
                "compare Whitman Family Office and Smith", TENANT, TOKEN);
        assertThat(unbound).hasSize(1);
        assertThat(unbound.get(0).verbatim()).isEqualTo("Smith");
        assertThat(unbound.get(0).ambiguous()).isTrue();
    }

    // ── Tier B — resolve-confirmation ───────────────────────────────────────────────────────────────

    @Test
    void tierB_zeroInterpMention_resolveConfirmed_detects_verbatimNotCanonical() {
        // PC-1: "the Calderon account" got zero interpretations; resolve confirms REL-00099 ∉ {REL-00042}.
        when(coverage.resolve(eq("the Calderon account"), any(), any(), any(), any()))
                .thenReturn(resolved("REL-00099", "Calderon Trust"));
        Mention calderon = explicit("the Calderon account");
        GroundedReferenceSet s = withWhitmanBound(gm(calderon));  // zero interpretations
        List<UnboundReference> unbound = svc.detectUnboundReferences(s, allBindings(s),
                "compare the concentration of the Whitman Family Office and the Calderon account", TENANT, TOKEN);

        assertThat(unbound).hasSize(1);
        // SECURITY: the rendered verbatim is the USER's own words, NEVER the resolved canonical name.
        assertThat(unbound.get(0).verbatim()).isEqualTo("the Calderon account");
        assertThat(unbound.get(0).verbatim()).doesNotContain("Calderon Trust");
        assertThat(unbound.get(0).resolvedId()).isEqualTo("REL-00099");   // carried for the ∉-bound guard only
    }

    @Test
    void tierB_possessiveRawPhrase_resolveConfirmed_detects() {
        when(coverage.resolve(eq("Calderon"), any(), any(), any(), any()))
                .thenReturn(resolved("REL-00099", "Calderon Trust"));
        GroundedReferenceSet s = withWhitmanBound();
        List<UnboundReference> unbound = svc.detectUnboundReferences(s, allBindings(s),
                "compare Whitman Family Office's concentration and Calderon's", TENANT, TOKEN);
        assertThat(unbound).extracting(UnboundReference::verbatim).contains("Calderon");
    }

    @Test
    void tierB_ambiguousResolve_doesNotConfirm() {
        when(coverage.resolve(eq("Trust"), any(), any(), any(), any())).thenReturn(ambiguous());
        GroundedReferenceSet s = withWhitmanBound();
        assertThat(svc.detectUnboundReferences(s, allBindings(s),
                "compare Whitman Family Office and Trust", TENANT, TOKEN)).isEmpty();
    }

    @Test
    void tierB_resolveToBoundId_doesNotConfirm() {
        // "the Whitman account" resolves to the already-bound id → second entity-named-two-ways guard.
        when(coverage.resolve(eq("the Whitman account"), any(), any(), any(), any()))
                .thenReturn(resolved("REL-00042", "Whitman Family Office"));
        Mention alias = explicit("the Whitman account");
        GroundedReferenceSet s = withWhitmanBound(gm(alias));   // zero-interp
        assertThat(svc.detectUnboundReferences(s, allBindings(s),
                "compare Whitman Family Office and the Whitman account", TENANT, TOKEN)).isEmpty();
    }

    @Test
    void tierB_notFoundResolve_doesNotConfirm() {
        when(coverage.resolve(eq("Meridian"), any(), any(), any(), any())).thenReturn(notFound());
        GroundedReferenceSet s = withWhitmanBound();
        assertThat(svc.detectUnboundReferences(s, allBindings(s),
                "compare Whitman Family Office and Meridian", TENANT, TOKEN)).isEmpty();
    }

    @Test
    void tierB_coverageUnavailable_failsOpenToToday() {
        when(coverage.resolve(any(), any(), any(), any(), any()))
                .thenThrow(new CoverageClient.CoverageUnavailableException("down"));
        GroundedReferenceSet s = withWhitmanBound();
        assertThat(svc.detectUnboundReferences(s, allBindings(s),
                "compare Whitman Family Office and Calderon", TENANT, TOKEN)).isEmpty();
    }

    // ── Over-clarify guardrails ─────────────────────────────────────────────────────────────────────

    @Test
    void overlap_verbatimSubsetOfBoundCanonicalName_excluded() {
        // A stray "Office" run overlaps the bound canonicalName "Whitman Family Office" → never a residual.
        GroundedReferenceSet s = withWhitmanBound();
        assertThat(svc.detectUnboundReferences(s, allBindings(s),
                "show the Office concentration for Whitman Family Office", TENANT, TOKEN)).isEmpty();
        verifyNoInteractions(coverage);
    }

    @Test
    void anaphora_priorTurnMention_neverACandidate() {
        Mention carried = anaphora("Calderon");
        GroundedReferenceSet s = withWhitmanBound(gm(carried, interp(null, null, GroundStatus.NOT_FOUND, carried)));
        // Raw prompt is the latest turn only; the carried name is not in it and the mention is prior-turn.
        assertThat(svc.detectUnboundReferences(s, allBindings(s),
                "compare Whitman Family Office", TENANT, TOKEN)).isEmpty();
    }

    @Test
    void singleEntity_cleanQuery_noResidual() {
        GroundedReferenceSet s = withWhitmanBound();
        when(coverage.resolve(any(), any(), any(), any(), any())).thenReturn(notFound());
        // Only the bound entity + generic capitalized words → all overlap-filtered or NOT_FOUND → empty.
        assertThat(svc.detectUnboundReferences(s, allBindings(s),
                "What is the concentration of Whitman Family Office", TENANT, TOKEN)).isEmpty();
    }

    @Test
    void deniedEntityIsABinding_notAResidual() {
        // Whitman ALLOWED + Okafor DENIED both bind (deriveAll). Okafor's raw phrase overlaps its bound
        // verbatim → no residual → CLARIFY does not fire (deny > clarify; the PARTIAL path owns it).
        Mention whitman = explicit("Whitman Family Office");
        Mention okafor = explicit("the Okafor account");
        GroundedReferenceSet s = set(
                gm(whitman, interp("REL-00042", "Whitman Family Office", GroundStatus.RESOLVED_ALLOWED, whitman)),
                gm(okafor, interp("REL-00188", "Okafor", GroundStatus.DENIED, okafor)));
        assertThat(svc.detectUnboundReferences(s, EntityBindingSet.deriveAll(s),
                "compare the Whitman Family Office and the Okafor account", TENANT, TOKEN)).isEmpty();
    }

    @Test
    void capMaxResolves_honored() {
        ReferenceGroundingService capped = new ReferenceGroundingService(
                mock(DomainManifestStore.class), coverage,
                new GroundingBudget(8, 4, 8, 8000, 1));   // max-resolves = 1
        when(coverage.resolve(any(), any(), any(), any(), any())).thenReturn(notFound());
        GroundedReferenceSet s = withWhitmanBound();
        // Two junk capitalized runs; only one is resolved (cap=1); neither confirms → empty, but proves no throw.
        assertThat(capped.detectUnboundReferences(s, allBindings(s),
                "compare Whitman Family Office and Alpha and Beta", TENANT, TOKEN)).isEmpty();
    }
}
