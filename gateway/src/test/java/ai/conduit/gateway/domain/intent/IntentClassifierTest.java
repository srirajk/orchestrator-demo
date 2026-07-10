package ai.conduit.gateway.domain.intent;

import ai.conduit.gateway.api.v1.chat.dto.Message;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CLAUDE.md §5 — "Never return canned/fallback data when the LLM is unreachable; surface the error."
 *
 * <p>The classifier used to swallow every exception and return a fabricated FETCH_DATA intent with
 * an empty entity bag. Because CLARIFY is decided deterministically over the extracted entities
 * ({@code extracted ∩ required_context = ∅}), that empty bag made the gateway ask "which client did
 * you mean?" whenever the LLM provider rate-limited us — measured at 93 spurious clarifications out
 * of 227 requests under load. A provider outage must never be reported to the user as ambiguity in
 * their own question.
 */
class IntentClassifierTest {

    /** Points the classifier at a closed port: stands in for a 429 / provider outage. */
    private IntentClassifier classifierWithUnreachableLlm() {
        DomainManifestStore store = mock(DomainManifestStore.class);
        when(store.entityTypes()).thenReturn(List.of());
        return new IntentClassifier(
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                OpenTelemetry.noop().getTracer("test"),
                store,
                "http://127.0.0.1:1",   // nothing listens here
                "test-key",
                "test-model",
                "a test assistant",
                "that,them,it",
                0.0,
                0,   // maxRetries — keep the test fast
                1,   // retryInitialDelayMs
                2,   // retryBackoffMultiplier
                1);  // requestTimeoutSeconds
    }

    @Test
    void surfacesTheErrorWhenTheLlmIsUnreachable() {
        IntentClassifier classifier = classifierWithUnreachableLlm();
        List<Message> messages = List.of(new Message("user", "What's the latest NAV on fund FND-7781?"));

        assertThatThrownBy(() -> classifier.classify(messages))
                .as("a failed classification must surface, never be papered over with a canned intent")
                .isInstanceOf(IntentClassificationException.class);
    }

    @Test
    void neverReturnsAFabricatedIntentWithAnEmptyEntityBag() {
        IntentClassifier classifier = classifierWithUnreachableLlm();
        List<Message> messages = List.of(new Message("user", "Show me the Whitman relationship holdings"));

        // The specific regression: returning FETCH_DATA + EntityBag.empty() here is what turned a
        // provider 429 into "which client did you mean?". Any non-throwing return is a regression.
        assertThatThrownBy(() -> classifier.classify(messages))
                .isInstanceOf(IntentClassificationException.class)
                .hasMessageContaining("Intent classification failed");
    }
}
