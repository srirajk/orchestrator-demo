package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.domain.coverage.CoverageResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE security test. An ambiguous reference resolves to several entities, but the principal is only
 * entitled to some of them (the {@code discover} book is the CHECK-passed set). The offered set that
 * enters the descriptor MUST contain only the entitled candidates — never the ones outside the book —
 * and MUST NOT expose any "N more hidden" count (a count is itself an enumeration oracle). The
 * free-text escape must always be present.
 *
 * <p>This fails genuinely if the factory ever builds the offered set from raw resolve candidates
 * instead of intersecting them with the entitled book.
 */
class EnumerationOracleTest {

    private final ClarificationDescriptorFactory factory =
            new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");

    @Test
    void nonEntitledCandidatesNeverEnterTheDescriptor() {
        // The principal's ENTITLED book (the discover result). REL-2 is entitled; REL-9 is NOT in the book.
        List<CoverageResource> entitledBook = List.of(
                new CoverageResource("REL-1", "Alpha Trust", "sd"),
                new CoverageResource("REL-2", "Beta Holdings", "sd"));

        // The ambiguous reference resolved to REL-2 (entitled) AND REL-9 (NOT entitled — a different
        // principal's entity that merely matched the name). A naive impl would offer REL-9 too.
        List<String> rawResolveCandidateIds = List.of("REL-2", "REL-9");

        ClarificationDescriptor d = factory.forEntity(
                "conv-1", "Which one?", "Which one?\n- Beta Holdings (REL-2)\nReply with the name or identifier.",
                "client", "REL-\\d+", entitledBook, rawResolveCandidateIds, "show holdings", 1);

        // Only the entitled ∩ resolved candidate is offered.
        assertThat(d.offeredValues()).containsExactly("REL-2");
        assertThat(d.offeredValues()).doesNotContain("REL-9");

        StructuredInteraction si = d.toStructuredInteraction();
        assertThat(si.options()).extracting(ClarificationOption::value).containsExactly("REL-2");
        // No candidate outside the book leaked into any label/secondary field either.
        assertThat(si.options()).allSatisfy(o -> {
            assertThat(o.value()).isNotEqualTo("REL-9");
            assertThat(o.label()).doesNotContain("REL-9");
        });

        // No hidden-count oracle anywhere on the wire form.
        assertThat(si.toString()).doesNotContain("hidden");
        assertThat(si.toString()).doesNotContain("more");
        for (var m : StructuredInteraction.class.getRecordComponents()) {
            assertThat(m.getName().toLowerCase()).doesNotContain("hidden");
            assertThat(m.getName().toLowerCase()).doesNotContain("count");
        }

        // The free-text escape is always present — the user is never trapped in the offered set.
        assertThat(si.freeText()).isNotNull();
        assertThat(si.freeText().enabled()).isTrue();
    }

    @Test
    void noRestriction_offersWholeEntitledBook_neverBeyondIt() {
        List<CoverageResource> entitledBook = List.of(
                new CoverageResource("REL-1", "Alpha Trust", "sd"),
                new CoverageResource("REL-2", "Beta Holdings", "sd"));

        ClarificationDescriptor d = factory.forEntity(
                "conv-1", "Which one?", "Which one?", "client", "REL-\\d+",
                entitledBook, List.of(), "show holdings", 1);

        // Whole book (no ambiguous subset to narrow to) — but still ONLY the entitled book.
        assertThat(d.offeredValues()).containsExactlyInAnyOrder("REL-1", "REL-2");
    }
}
