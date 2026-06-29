package ai.meridian.gateway.synthesis.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the generic, manifest-keyed EntityBag contract that replaced the wealth-shaped record.
 * No entity-type-specific fields: everything is keyed off manifest declarations.
 */
class EntityBagTest {

    @Test
    @DisplayName("of() stores references and lists; generic accessors read by extract_as")
    void genericAccessors() {
        var bag = EntityBag.of(
                Map.of("relationship_reference", "Whitman Family Office", "period", "QTD"),
                Map.of("ticker_references", List.of("AAPL", "MSFT")));

        assertThat(bag.reference("relationship_reference")).isEqualTo("Whitman Family Office");
        assertThat(bag.reference("period")).isEqualTo("QTD");
        assertThat(bag.list("ticker_references")).containsExactly("AAPL", "MSFT");
        assertThat(bag.list("absent")).isEmpty();
        assertThat(bag.reference("absent")).isNull();
        assertThat(bag.resolved("relationship_id")).isNull();
        assertThat(bag.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("withResolved populates the resolved map keyed by entity key")
    void withResolved() {
        var bag = EntityBag.of(Map.of("relationship_reference", "REL-00042"), Map.of())
                .withResolved(Map.of("relationship_id", "REL-00042"), false);

        assertThat(bag.resolved("relationship_id")).isEqualTo("REL-00042");
        assertThat(bag.reference("relationship_reference")).isEqualTo("REL-00042");
        assertThat(bag.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("withCandidates flags clarification needed")
    void withCandidates() {
        var bag = EntityBag.of(Map.of("relationship_reference", "Acme"), Map.of())
                .withCandidates(List.of(new EntityBag.EntityCandidate("REL-1", "Acme One")));

        assertThat(bag.needsClarification()).isTrue();
        assertThat(bag.candidates()).hasSize(1);
    }

    @Test
    @DisplayName("withReference overrides one extract_as field, preserving the rest (id injection)")
    void withReferenceOverride() {
        var bag = EntityBag.of(
                        Map.of("relationship_reference", "Whitman", "period", "YTD"),
                        Map.of("ticker_references", List.of("AAPL")))
                .withReference("relationship_reference", "REL-00042");

        assertThat(bag.reference("relationship_reference")).isEqualTo("REL-00042");
        assertThat(bag.reference("period")).isEqualTo("YTD");
        assertThat(bag.list("ticker_references")).containsExactly("AAPL");
    }

    @Test
    @DisplayName("empty() is fully empty and immutable")
    void empty() {
        var bag = EntityBag.empty();
        assertThat(bag.references()).isEmpty();
        assertThat(bag.lists()).isEmpty();
        assertThat(bag.resolved()).isEmpty();
        assertThat(bag.candidates()).isEmpty();
        assertThat(bag.needsClarification()).isFalse();
    }
}
