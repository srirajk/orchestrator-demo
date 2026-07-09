package ai.conduit.gateway.synthesis.answer;

import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedFigureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GroundedFigureRenderer renderer = new GroundedFigureRenderer(MAPPER);
    private final GroundedFigureValidator validator = new GroundedFigureValidator();

    @Test
    void rendererFormatsGenericFigureTypes() throws Exception {
        var data = MAPPER.readTree("""
                {"pct_fraction":0.314,"pct_points":494.8,"money":1967000,"count":3,"as_of":"2026-06-22"}
                """);

        assertThat(renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Fraction percent", "pct_fraction", "percent1"),
                data, 0).renderedValue()).isEqualTo("31.4%");
        assertThat(renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Point percent", "pct_points", "percent1"),
                data, 1).renderedValue()).isEqualTo("494.8%");
        assertThat(renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Money", "money", "currency_usd"),
                data, 2).renderedValue()).isEqualTo("$1,967,000.00");
        assertThat(renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Count", "count", "count"),
                data, 3).renderedValue()).isEqualTo("3");
        assertThat(renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Date", "as_of", "date"),
                data, 4).renderedValue()).isEqualTo("2026-06-22");
    }

    @Test
    void validatorFlagsFabricatedFigure() {
        GroundedFigure figure = renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Claims loss ratio", "value", "percent1"),
                MAPPER.createObjectNode().put("value", 494.8), 0);

        var result = validator.validate("Claims loss ratio is 49.48%.", List.of(figure));

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("49.48"));
    }

    @Test
    void validatorFlagsRightNumberWrongLabel() {
        GroundedFigure observed = renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Observed breach percent", "observed", "percent1"),
                MAPPER.createObjectNode().put("observed", 31.4), 0);
        GroundedFigure threshold = renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Threshold percent", "threshold", "percent1"),
                MAPPER.createObjectNode().put("threshold", 10.0), 1);

        var result = validator.validate("Threshold percent is 31.4%.", List.of(observed, threshold));

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("31.4"));
    }

    @Test
    void validatorPassesEquivalentPercentFormatting() {
        GroundedFigure figure = renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Claims loss ratio", "value", "percent1"),
                MAPPER.createObjectNode().put("value", 0.314), 0);

        var result = validator.validate("Claims loss ratio is 31.4%.", List.of(figure));

        assertThat(result.ok()).isTrue();
    }

    @Test
    void validatorPassesCodeRenderedRoundedValueAndListOrdinal() {
        GroundedFigure figure = renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Top single-name concentration", "value", "percent1"),
                MAPPER.createObjectNode().put("value", 25.4194), 0);

        var result = validator.validate("1. Top single-name concentration is 25.4%.", List.of(figure));

        assertThat(result.ok()).isTrue();
    }

    @Test
    void validatorIgnoresSignedIdentifierSuffixNearLabel() {
        GroundedFigure figure = renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Failed settlement amount", "value", "currency_usd"),
                MAPPER.createObjectNode().put("value", 185000), 0);

        var result = validator.validate(
                "Failed settlement amount is $185,000.00 across the T+1 bucket.",
                List.of(figure));

        assertThat(result.ok()).isTrue();
    }

    @Test
    void validatorFlagsUnlabelledLoadBearingPercent() {
        GroundedFigure figure = renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Top single-name concentration", "value", "percent1"),
                MAPPER.createObjectNode().put("value", 25.4194), 0);

        var result = validator.validate("The portfolio also has another position at 6%.", List.of(figure));

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("6%"));
    }

    @Test
    void validatorFlagsPercentSignOnPlainFigure() {
        GroundedFigure figure = renderer.renderOne("a",
                new AgentManifest.ProducedFigure("Diversification HHI", "value", "plain"),
                MAPPER.createObjectNode().put("value", 0.209603), 0);

        var result = validator.validate("Diversification HHI is 0.209603%.", List.of(figure));

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("0.209603%"));
    }
}
