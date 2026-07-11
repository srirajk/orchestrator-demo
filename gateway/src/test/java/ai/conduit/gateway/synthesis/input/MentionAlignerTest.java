package ai.conduit.gateway.synthesis.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic aligner is the load-bearing half of the span model (routing spec V2.1
 * resolution #2): spans are gateway-DERIVED, never LLM-emitted. These lock the alignment
 * tolerances (case, punctuation, possessive, Unicode, repeated occurrence) and the miss contract.
 * All fixtures use synthetic tokens — no domain vocabulary.
 */
class MentionAlignerTest {

    /** The original substring a span selects — the property every case really asserts. */
    private static String slice(String original, MentionSpan s) {
        return original.substring(s.start(), s.end());
    }

    @Test
    @DisplayName("exact reference aligns to its own characters")
    void exact() {
        String msg = "Show me Calderon holdings";
        MentionSpan s = MentionAligner.align(msg, "Calderon");
        assertThat(s).isNotNull();
        assertThat(slice(msg, s)).isEqualTo("Calderon");
    }

    @Test
    @DisplayName("possessive: the span covers the name, not the trailing 's")
    void possessive() {
        String msg = "What are Calderon's goals";
        MentionSpan s = MentionAligner.align(msg, "Calderon");
        assertThat(s).isNotNull();
        assertThat(slice(msg, s)).isEqualTo("Calderon");
    }

    @Test
    @DisplayName("case drift aligns and the span keeps the ORIGINAL casing")
    void caseDrift() {
        String msg = "open the calderon account";
        MentionSpan s = MentionAligner.align(msg, "Calderon");
        assertThat(s).isNotNull();
        assertThat(slice(msg, s)).isEqualTo("calderon");
    }

    @Test
    @DisplayName("surrounding punctuation is tolerated on both sides")
    void punctuation() {
        String msg = "Compare (Calderon) and others";
        MentionSpan s = MentionAligner.align(msg, "Calderon");
        assertThat(s).isNotNull();
        assertThat(slice(msg, s)).isEqualTo("Calderon");
        // a trailing-punctuation verbatim still aligns to the bare token
        MentionSpan s2 = MentionAligner.align("Calderon holdings", "Calderon.");
        assertThat(s2).isNotNull();
        assertThat(slice("Calderon holdings", s2)).isEqualTo("Calderon");
    }

    @Test
    @DisplayName("multi-word reference aligns across an internal space")
    void multiWord() {
        String msg = "the Calderon Trust portfolio";
        MentionSpan s = MentionAligner.align(msg, "Calderon Trust");
        assertThat(s).isNotNull();
        assertThat(slice(msg, s)).isEqualTo("Calderon Trust");
    }

    @Test
    @DisplayName("Unicode diacritics fold; the span keeps the accented original")
    void unicode() {
        String msg = "the José account";
        MentionSpan s = MentionAligner.align(msg, "Jose");
        assertThat(s).isNotNull();
        assertThat(slice(msg, s)).isEqualTo("José");
    }

    @Test
    @DisplayName("repeated reference: successive occurrences align to distinct spans")
    void repeated() {
        String msg = "Calderon versus Calderon Trust";
        MentionSpan first = MentionAligner.align(msg, "Calderon", 0);
        MentionSpan second = MentionAligner.align(msg, "Calderon", 1);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.start()).isGreaterThan(first.start());
        assertThat(slice(msg, first)).isEqualTo("Calderon");
        assertThat(slice(msg, second)).isEqualTo("Calderon");
        // no third occurrence
        assertThat(MentionAligner.align(msg, "Calderon", 2)).isNull();
    }

    @Test
    @DisplayName("alignment miss returns null — never a guessed span")
    void miss() {
        assertThat(MentionAligner.align("tell me about the account", "Calderon")).isNull();
    }

    @Test
    @DisplayName("matches only on token boundaries — never a partial token")
    void tokenBoundary() {
        // "10" must not bind inside "100"
        assertThat(MentionAligner.align("account 100 details", "10")).isNull();
        // but the whole token aligns
        MentionSpan s = MentionAligner.align("account 100 details", "100");
        assertThat(s).isNotNull();
        assertThat(slice("account 100 details", s)).isEqualTo("100");
    }
}
