package ai.conduit.gateway.synthesis.answer;

import ai.conduit.gateway.config.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the synthesizer LLM produces a figure that fails grounding validation, the answer is not
 * served as-is and is not blanked — it is replaced by a deterministic rendering built from the
 * <em>real</em> grounded figures (the agent data). This is the anti-hallucination guard, and it must
 * state only what the agents reported.
 *
 * <p>The danger it defends against: the LLM inventing or misquoting a number. The danger this test
 * defends against: the fallback itself quietly becoming a source of fabricated text. The fallback
 * may only echo the figures it was given.
 */
class AnswerSynthesizerGroundingTest {

    private static AnswerSynthesizer synthesizer() {
        // deterministicFigureFallback is stateless; the collaborators (incl. the DomainManifestStore)
        // are unused on this path — an explicit non-blank domain-context override means
        // composedDomainContext() is never consulted.
        return new AnswerSynthesizer(
                new ObjectMapper(), null, null, null, null, new SimpleMeterRegistry(), prompts(), null,
                "http://unused", "", "test-model", false, "Meridian", "test context",
                1, 1, 1, 5, 5);
    }

    /** Loads the real prompt resources from the test classpath (mirrors production wiring). */
    private static PromptLoader prompts() {
        try {
            return PromptLoader.forClasspath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static GroundedFigure figure(String label, String rendered) {
        return new GroundedFigure(label, "{{" + label + "}}", rendered, null, "text", "test-agent", java.util.Set.of());
    }

    @Test
    void theDeterministicFallbackStatesOnlyTheGroundedFigures() {
        AnswerSynthesizer synthesizer = synthesizer();
        List<GroundedFigure> figures = List.of(
                figure("YTD performance", "8.20%"),
                figure("Total holdings", "$4,231,905.55"));

        String out = synthesizer.deterministicFigureFallback("how did the portfolio do?", figures);

        // Every rendered value the agents provided is present, verbatim.
        assertThat(out).contains("8.20%").contains("$4,231,905.55");
        assertThat(out).contains("YTD performance").contains("Total holdings");
    }

    @Test
    void theFallbackInventsNoValueThatWasNotGrounded() {
        AnswerSynthesizer synthesizer = synthesizer();
        List<GroundedFigure> figures = List.of(figure("YTD performance", "8.20%"));

        String out = synthesizer.deterministicFigureFallback("how did it do?", figures);

        // A consolidated/derived number the agents never reported must never appear. The fallback
        // echoes grounded figures; it does not compute, roll up, or embellish.
        assertThat(out)
                .as("the fallback must not fabricate a figure beyond what the agents grounded")
                .doesNotContain("100%")
                .doesNotContain("$0")
                .doesNotContain("total of");
        assertThat(out).contains("8.20%");
    }

    @Test
    void anEmptyFigureSetProducesNoInventedContent() {
        AnswerSynthesizer synthesizer = synthesizer();

        String out = synthesizer.deterministicFigureFallback("anything", List.of());

        // No figures grounded → the output names no value at all; it cannot manufacture one.
        assertThat(out).doesNotContain("%").doesNotContain("$");
    }
}
