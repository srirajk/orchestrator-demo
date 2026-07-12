package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.coverage.EntityBinding;
import ai.conduit.gateway.domain.coverage.EntityBindingSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundSourceKind;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.UnboundReference;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.MentionSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure test for the Compare-CLARIFY copy composition ({@link ChatService#composeComparePartial}). The
 * load-bearing assertion is the SECURITY property: {@code {unresolved}} renders the user's OWN verbatim,
 * NEVER the canonical name Tier-B resolved to. World-B: fixtures only (the grep scans main).
 */
class ChatServiceCompareClarifyTest {

    private static final EntityType REL = new EntityType(
            "relationship_id", "relationship_reference", "resolvable",
            "client relationship", "REL-\\d+", "relationship", true, null);

    private static EntityBinding allowed(String id, String canonicalName, String verbatim) {
        GroundedInterpretation gi = new GroundedInterpretation(
                id, canonicalName, REL.key(), "private-banking", "resolve|relationship|" + id,
                GroundStatus.RESOLVED_ALLOWED, null, GroundSourceKind.EXTRACTED_REFERENCE,
                MentionSource.EXPLICIT, "1", 1, null, REL, null);
        return new EntityBinding(id, verbatim, REL.key(), List.of(gi), 0);
    }

    @Test
    void unresolvedRendersUserVerbatim_neverTheResolvedCanonicalName() {
        EntityBindingSet bindings = new EntityBindingSet(
                List.of(allowed("REL-00042", "Whitman Family Office", "the Whitman Family Office")), REL.key());
        // Tier-B resolved "the Calderon account" to the canonical "Calderon Trust" (REL-00099) — that name
        // MUST NOT surface; only the user's own words may appear as {unresolved}.
        UnboundReference calderon = new UnboundReference("the Calderon account", "REL-00099", false, List.of());
        String template = "I found {resolved}. I couldn't identify the other client ({unresolved}). "
                + "Give the name or ID.";

        String out = ChatService.composeComparePartial(template, bindings, List.of(calderon));

        assertThat(out).contains("Whitman Family Office");        // {resolved} = bound client's display
        assertThat(out).contains("the Calderon account");         // {unresolved} = user's OWN verbatim
        assertThat(out).doesNotContain("Calderon Trust");         // SECURITY: never the resolved canonical name
        assertThat(out).doesNotContain("REL-00099");              // never the resolved id
        assertThat(out).doesNotContain("{resolved}").doesNotContain("{unresolved}");
    }

    @Test
    void multipleUnresolved_joinedDeduped() {
        EntityBindingSet bindings = new EntityBindingSet(
                List.of(allowed("REL-00042", "Whitman Family Office", "Whitman")), REL.key());
        String out = ChatService.composeComparePartial("{resolved} / {unresolved}", bindings, List.of(
                new UnboundReference("Calderon", "REL-00099", false, List.of()),
                new UnboundReference("Calderon", "REL-00099", false, List.of()),   // dup verbatim
                new UnboundReference("Okafor", "REL-00188", false, List.of())));
        assertThat(out).isEqualTo("Whitman Family Office / Calderon, Okafor");
    }
}
