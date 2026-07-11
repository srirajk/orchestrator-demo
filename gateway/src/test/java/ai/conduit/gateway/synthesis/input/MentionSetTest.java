package ai.conduit.gateway.synthesis.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the compat-scalar derivation (routing spec V2.1 resolution #3) and the grouping/miss views
 * the grounding stage consumes. Keys/values are synthetic — no domain vocabulary.
 */
class MentionSetTest {

    private static Mention explicit(String key, String text, int idx) {
        return new Mention(key, key + "_ref", text, idx, new MentionSpan(0, text.length()), MentionSource.EXPLICIT);
    }

    private static Mention anaphora(String key, String text, int idx, boolean aligned) {
        MentionSpan span = aligned ? new MentionSpan(0, text.length()) : null;
        return new Mention(key, key + "_ref", text, idx, span, MentionSource.ANAPHORA);
    }

    @Test
    @DisplayName("compat scalar = latest-turn explicit mention")
    void latestExplicitWins() {
        var set = new MentionSet(List.of(
                explicit("k", "Old", 1),
                anaphora("k", "Carried", 0, true),
                explicit("k", "New", 5)));
        assertThat(set.compatScalar("k")).isEqualTo("New");
    }

    @Test
    @DisplayName("explicit outranks a later-turn anaphora")
    void explicitOutranksLaterAnaphora() {
        var set = new MentionSet(List.of(
                anaphora("k", "Carried", 9, true),
                explicit("k", "Named", 2)));
        assertThat(set.compatScalar("k")).isEqualTo("Named");
    }

    @Test
    @DisplayName("with no explicit mention, the carried anaphora is the compat scalar")
    void anaphoraFallback() {
        var set = new MentionSet(List.of(anaphora("k", "Carried", 0, true)));
        assertThat(set.compatScalar("k")).isEqualTo("Carried");
    }

    @Test
    @DisplayName("same-turn tie keeps the first-recorded (focal) mention")
    void focalWinsSameTurnTie() {
        var set = new MentionSet(List.of(
                explicit("k", "Focal", 4),
                explicit("k", "Other", 4)));
        assertThat(set.compatScalar("k")).isEqualTo("Focal");
        assertThat(set.forKey("k")).hasSize(2);
    }

    @Test
    @DisplayName("grouping, key order, and alignment-miss views")
    void views() {
        Mention a = explicit("a", "One", 0);
        Mention b1 = explicit("b", "Two", 1);
        Mention b2 = anaphora("b", "Three", -1, false);   // a miss
        var set = new MentionSet(List.of(a, b1, b2));

        assertThat(set.entityKeys()).containsExactly("a", "b");
        assertThat(set.forKey("b")).containsExactly(b1, b2);
        assertThat(set.alignmentMisses()).containsExactly(b2);
        assertThat(set.compatScalar("missing")).isNull();
        assertThat(set.compatScalars()).containsEntry("a", "One").containsEntry("b", "Two");
    }
}
