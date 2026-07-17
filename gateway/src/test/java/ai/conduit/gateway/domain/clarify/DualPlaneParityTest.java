package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.api.v1.chat.sse.OpenAiSseWriter;
import ai.conduit.gateway.domain.coverage.CoverageResource;
import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * One abstain ⇒ ONE {@link ClarificationDescriptor} rendered on BOTH planes: a byte-correct plain-text
 * question on the OpenAI chat SSE (role delta, content deltas, {@code stop} finish-reason, {@code [DONE]})
 * AND a structured form on the out-of-band lane — both derived from the same descriptor, so they cannot
 * drift. A client with no out-of-band support still receives clean plain text and nothing else.
 */
class DualPlaneParityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ClarificationDescriptorFactory factory =
            new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");

    private ClarificationDescriptor buildDescriptor() {
        List<CoverageResource> book = List.of(
                new CoverageResource("REL-1", "Alpha Trust", "sd"),
                new CoverageResource("REL-2", "Beta Holdings", "sd"));
        String plainText = "Which one are you asking about?\n"
                + "- Alpha Trust (REL-1)\n- Beta Holdings (REL-2)\nReply with the name or identifier.";
        return factory.forEntity("conv-1", "Which one are you asking about?", plainText,
                "client", "REL-\\d+", book, List.of(), "show holdings", 1);
    }

    @Test
    void oneDescriptorFeedsBothPlanes() {
        ClarificationDescriptor d = buildDescriptor();

        // ── OOB plane: the dual-plane component stores + publishes the structured form ──
        TraceEventPublisher publisher = mock(TraceEventPublisher.class);
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarificationDualPlane dualPlane = new ClarificationDualPlane(publisher, store, true);

        dualPlane.offer(d, "req-1");

        verify(store).store(d);                                        // persisted for Phase-2 resume
        ArgumentCaptor<TraceEvent> ev = ArgumentCaptor.forClass(TraceEvent.class);
        verify(publisher).publish(ev.capture());
        assertThat(ev.getValue().type()).isEqualTo("structured_interaction");
        assertThat(ev.getValue().conversationId()).isEqualTo("conv-1");
        StructuredInteraction si = (StructuredInteraction) ev.getValue().data();
        assertThat(si.kind()).isEqualTo(InteractionKind.CLARIFY_ENTITY);
        assertThat(si.nonce()).isEqualTo(d.nonce());
        assertThat(si.options()).extracting(ClarificationOption::value).containsExactly("REL-1", "REL-2");
        assertThat(si.freeText().enabled()).isTrue();

        // ── SSE plane: the SAME descriptor's plain text, byte-correct ──
        List<String> frames = OpenAiSseWriter.textFrames(mapper, "conduit-assistant", "chatcmpl-1", 1L, d.plainText());
        assertThat(frames.get(0)).contains("\"role\":\"assistant\"");
        assertThat(frames).anySatisfy(f -> assertThat(f).contains("\"finish_reason\":\"stop\""));
        assertThat(frames).noneMatch(f -> f.contains("tool_calls"));   // clarify is NOT a tool-call
        assertThat(frames.get(frames.size() - 1)).isEqualTo(OpenAiSseWriter.DONE);
        // The content deltas reconstruct exactly the descriptor's plain text.
        String reconstructed = frames.stream()
                .filter(f -> f.contains("\"content\":"))
                .map(f -> {
                    try { return mapper.readTree(f.stripLeading())
                            .path("choices").path(0).path("delta").path("content").asText(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .reduce("", String::concat);
        assertThat(reconstructed).isEqualTo(d.plainText());

        // Parity of content: every offered option's label is visible in the plain-text twin.
        assertThat(d.plainText()).contains("Alpha Trust").contains("Beta Holdings");
    }

    @Test
    void clientWithoutOobSupport_getsCleanTextOnly() {
        ClarificationDescriptor d = buildDescriptor();
        // The SSE bytes carry ONLY the plain-text question — no structured form / nonce / kind leaks in.
        List<String> frames = OpenAiSseWriter.textFrames(mapper, "conduit-assistant", "chatcmpl-1", 1L, d.plainText());
        assertThat(frames).noneMatch(f -> f.contains(d.nonce()));
        assertThat(frames).noneMatch(f -> f.contains("clarify_entity"));
        assertThat(frames).noneMatch(f -> f.contains("structured_interaction"));
    }

    @Test
    void mutatingTheDescriptorChangesBothPlanes() {
        ClarificationDescriptor d1 = buildDescriptor();
        String sse1 = d1.plainText();
        StructuredInteraction oob1 = d1.toStructuredInteraction();

        // Mutate the ONE source object — both planes must reflect it (they are functions of the descriptor).
        ClarificationDescriptor d2 = d1
                .withPlainText("A different question entirely.")
                .withQuestion("A different question entirely.")
                .withOfferedCandidates(List.of(new ClarificationOption("REL-9", "Gamma Fund", "REL-9")));

        assertThat(d2.plainText()).isNotEqualTo(sse1);                             // SSE plane changed
        assertThat(d2.toStructuredInteraction().question()).isNotEqualTo(oob1.question()); // OOB plane changed
        assertThat(d2.toStructuredInteraction().options())
                .extracting(ClarificationOption::value).containsExactly("REL-9");
    }
}
