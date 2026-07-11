package ai.conduit.gateway.domain.intent;

import ai.conduit.gateway.api.v1.chat.dto.Message;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionSet;
import ai.conduit.gateway.synthesis.input.MentionSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link IntentClassifier#buildMentionSet} — the LLM-output → span-aware mention model
 * step — without an LLM round-trip. Uses a synthetic manifest entity type ({@code account_id} /
 * {@code account_reference}); no domain vocabulary. Verifies multi-reference capture, source
 * classification, gateway-derived spans, and that the focal scalar is preserved for compat.
 */
class IntentClassifierMentionTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> ANAPHORA = List.of("that", "them", "their", "it", "this");

    /** One resolvable entity type, manifest-shaped but domain-neutral. */
    private static final EntityType ACCOUNT = new EntityType(
            "account_id", "account_reference", "resolvable", "account",
            "ACC-\\d+", "account", true, null);

    private static JsonNode json(String body) {
        try {
            return M.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MentionSet build(JsonNode parsed, List<Message> messages, Map<String, String> focal) {
        return IntentClassifier.buildMentionSet(parsed, List.of(ACCOUNT), messages, focal, ANAPHORA);
    }

    @Test
    @DisplayName("two names of one entity key are both captured; focal is the compat scalar")
    void twoNamesOneKey() {
        List<Message> messages = List.of(new Message("user", "compare Calderon and Whitman"));
        JsonNode parsed = json("""
            {"mentions":[
              {"entity":"account_reference","text":"Calderon","source":"explicit"},
              {"entity":"account_reference","text":"Whitman","source":"explicit"}
            ]}""");

        MentionSet set = build(parsed, messages, Map.of("account_reference", "Calderon"));

        List<Mention> mentions = set.forKey("account_id");
        assertThat(mentions).hasSize(2);
        assertThat(mentions).extracting(Mention::verbatimText).containsExactlyInAnyOrder("Calderon", "Whitman");
        assertThat(mentions).allMatch(Mention::explicit);
        assertThat(mentions).allMatch(Mention::aligned);            // both spans gateway-derived
        assertThat(set.compatScalar("account_id")).isEqualTo("Calderon");   // focal preserved
    }

    @Test
    @DisplayName("two ids of one entity key are both captured and span-aligned")
    void twoIds() {
        List<Message> messages = List.of(new Message("user", "settlements for ACC-001 and ACC-002"));
        JsonNode parsed = json("""
            {"mentions":[
              {"entity":"account_reference","text":"ACC-001","source":"explicit"},
              {"entity":"account_reference","text":"ACC-002","source":"explicit"}
            ]}""");

        MentionSet set = build(parsed, messages, Map.of("account_reference", "ACC-001"));

        assertThat(set.forKey("account_id")).extracting(Mention::verbatimText)
                .containsExactlyInAnyOrder("ACC-001", "ACC-002");
        assertThat(set.forKey("account_id")).allMatch(Mention::aligned);
    }

    @Test
    @DisplayName("explicit source: a reference stated in the latest turn is EXPLICIT")
    void explicitSource() {
        List<Message> messages = List.of(new Message("user", "show Calderon holdings"));
        JsonNode parsed = json("""
            {"mentions":[{"entity":"account_reference","text":"Calderon","source":"explicit"}]}""");

        MentionSet set = build(parsed, messages, Map.of("account_reference", "Calderon"));

        Mention m = set.forKey("account_id").get(0);
        assertThat(m.source()).isEqualTo(MentionSource.EXPLICIT);
        assertThat(m.messageIndex()).isEqualTo(0);
        assertThat(m.aligned()).isTrue();
    }

    @Test
    @DisplayName("anaphora source: a back-reference carries the name from an earlier turn")
    void anaphoraSource() {
        List<Message> messages = List.of(
                new Message("user", "show Calderon holdings"),
                new Message("assistant", "Here are the holdings."),
                new Message("user", "and their goals?"));
        JsonNode parsed = json("""
            {"mentions":[{"entity":"account_reference","text":"Calderon","source":"anaphora"}]}""");

        MentionSet set = build(parsed, messages, Map.of("account_reference", "Calderon"));

        Mention m = set.forKey("account_id").get(0);
        assertThat(m.source()).isEqualTo(MentionSource.ANAPHORA);
        assertThat(m.messageIndex()).isEqualTo(0);      // aligned to the earlier turn, not the latest
        assertThat(m.aligned()).isTrue();
    }

    @Test
    @DisplayName("alignment miss: a carried reference absent from the window keeps no span")
    void alignmentMiss() {
        List<Message> messages = List.of(new Message("user", "and their goals?"));
        JsonNode parsed = json("{}");   // LLM omitted the mentions array

        // The focal reference was carried from outside the sent window, so it is not in any message.
        MentionSet set = build(parsed, messages, Map.of("account_reference", "Calderon"));

        Mention m = set.forKey("account_id").get(0);
        assertThat(m.aligned()).isFalse();
        assertThat(m.span()).isNull();
        assertThat(m.source()).isEqualTo(MentionSource.ANAPHORA);   // latest turn is a bare back-reference
        assertThat(set.alignmentMisses()).containsExactly(m);
        // compat scalar still surfaces the focal even without a span
        assertThat(set.compatScalar("account_id")).isEqualTo("Calderon");
    }

    @Test
    @DisplayName("focal is recorded once; an identical LLM mention does not double-count")
    void focalDeduplicated() {
        List<Message> messages = List.of(new Message("user", "show Calderon holdings"));
        JsonNode parsed = json("""
            {"mentions":[{"entity":"account_reference","text":"Calderon","source":"explicit"}]}""");

        MentionSet set = build(parsed, messages, Map.of("account_reference", "Calderon"));
        assertThat(set.forKey("account_id")).hasSize(1);
    }

    @Test
    @DisplayName("unknown entity fields in the mentions array are dropped generically")
    void unknownFieldDropped() {
        List<Message> messages = List.of(new Message("user", "show Calderon holdings"));
        JsonNode parsed = json("""
            {"mentions":[
              {"entity":"account_reference","text":"Calderon","source":"explicit"},
              {"entity":"not_a_field","text":"Nonsense","source":"explicit"}
            ]}""");

        MentionSet set = build(parsed, messages, Map.of("account_reference", "Calderon"));
        assertThat(set.mentions()).hasSize(1);
        assertThat(set.entityKeys()).containsExactly("account_id");
    }
}
