package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.domain.coverage.CoverageResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * World B: the descriptor composer authors NO domain literal. Every label/noun/id/question in the
 * rendered form is DATA passed in from the manifest + coverage result; the only strings the factory owns
 * are domain-agnostic templates (the free-text escape prompt) and the fixed enum wire values. Fed inputs
 * that carry NO domain words, the serialized form must contain none of the forbidden domain literals the
 * {@code world-b-check.sh} gate scans for.
 */
class WorldBFormLiteralGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // The forbidden-literal set mirrors scripts/world-b-check.sh CRITICAL patterns (domain names, client
    // names, id prefixes, entity field names, domain copy).
    private static final List<String> FORBIDDEN = List.of(
            "wealth-management", "asset-servicing", "private-banking", "custody-operations",
            "Whitman", "Calderon", "Okafor", "Andersen",
            "REL-", "FND-", "relationship_id", "fund_id",
            "client relationship", "which client", "in your coverage");

    @Test
    void factoryInjectsNoDomainLiteralOfItsOwn() throws Exception {
        // Inputs are deliberately domain-agnostic placeholders — nothing resembling a real domain.
        List<CoverageResource> book = List.of(
                new CoverageResource("X1", "Option One", "sd"),
                new CoverageResource("X2", "Option Two", "sd"));
        ClarificationDescriptorFactory factory =
                new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");

        ClarificationDescriptor d = factory.forEntity(
                "conv-1", "Which one?", "Which one?\n- Option One (X1)\n- Option Two (X2)",
                "item", "X\\d+", book, List.of(), "the query", 1);

        String json = mapper.writeValueAsString(d.toStructuredInteraction());
        for (String literal : FORBIDDEN) {
            assertThat(json).as("composer must not author domain literal '%s'", literal)
                    .doesNotContain(literal);
        }
    }

    @Test
    void manifestSuppliedLabelsPassThroughAsData() {
        // A domain label present in the INPUT is DATA and passes through verbatim (World B allows this —
        // the label came from the manifest/coverage service, not the gateway).
        List<CoverageResource> book = List.of(new CoverageResource("REL-42", "Whitman Family Office", "sd"));
        ClarificationDescriptorFactory factory =
                new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");

        ClarificationDescriptor d = factory.forEntity(
                "conv-1", "Which one?", "plain", "client", "REL-\\d+", book, List.of(), "q", 1);

        // The label the coverage service returned is carried as-is — it is data, not a gateway literal.
        assertThat(d.toStructuredInteraction().options())
                .extracting(ClarificationOption::label).containsExactly("Whitman Family Office");
    }

    @Test
    void freeTextPromptIsDomainAgnostic() {
        ClarificationDescriptorFactory factory =
                new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");
        ClarificationDescriptor d = factory.forEntity("conv-1", "q", "q", null, null,
                List.of(new CoverageResource("X1", "Option One", "sd")), List.of(), "q", 1);
        String prompt = d.freeTextPrompt().toLowerCase();
        for (String literal : FORBIDDEN) {
            assertThat(prompt).doesNotContain(literal.toLowerCase());
        }
    }
}
