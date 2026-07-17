package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.api.v1.chat.sse.OpenAiSseWriter;
import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One {@code clarify_capability} abstain ⇒ ONE {@link ClarificationDescriptor} rendered on BOTH planes: a
 * byte-correct plain-text "which capability?" question on the OpenAI chat SSE AND a structured form on the
 * out-of-band lane — both derived from the same descriptor, so they cannot drift. Also pins the capability
 * resume classification: an in-set capability pick is a route hint (CAPABILITY_SELECTION, not an entity
 * ground); an out-of-set pick demotes to untrusted free text. Pure unit — no Spring, no LLM.
 */
class CapabilityClarifyDualPlaneTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ClarificationDescriptorFactory factory =
            new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");

    private ClarificationDescriptor buildCapabilityDescriptor() {
        // Options are capability ids (the submit tokens / route hints), labelled with manifest copy.
        List<ClarificationOption> options = List.of(
                new ClarificationOption("meridian.hr.policy", "HR Policy Assistant", "HR policies and benefits"),
                new ClarificationOption("meridian.hr.payroll", "Payroll Assistant", "Payroll schedules and pay statements"));
        String plainText = "Which of these would you like me to use?\n"
                + "- HR Policy Assistant — HR policies and benefits\n"
                + "- Payroll Assistant — Payroll schedules and pay statements\n"
                + "\nReply with the name of the one you'd like.";
        return factory.forCapability("conv-1", "Which of these would you like me to use?", plainText,
                null, options, "tell me about that", 1);
    }

    @Test
    void oneDescriptorFeedsBothPlanes() {
        ClarificationDescriptor d = buildCapabilityDescriptor();
        assertThat(d.kind()).isEqualTo(InteractionKind.CLARIFY_CAPABILITY);

        // ── OOB plane: the dual-plane component stores + publishes the structured form ──
        TraceEventPublisher publisher = mock(TraceEventPublisher.class);
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarificationDualPlane dualPlane = new ClarificationDualPlane(publisher, store, true);

        dualPlane.offer(d, "req-1");

        verify(store).store(d);
        ArgumentCaptor<TraceEvent> ev = ArgumentCaptor.forClass(TraceEvent.class);
        verify(publisher).publish(ev.capture());
        assertThat(ev.getValue().type()).isEqualTo("structured_interaction");
        StructuredInteraction si = (StructuredInteraction) ev.getValue().data();
        assertThat(si.kind()).isEqualTo(InteractionKind.CLARIFY_CAPABILITY);
        assertThat(si.nonce()).isEqualTo(d.nonce());
        assertThat(si.options()).extracting(ClarificationOption::value)
                .containsExactly("meridian.hr.policy", "meridian.hr.payroll");
        assertThat(si.freeText().enabled()).isTrue();

        // ── SSE plane: the SAME descriptor's plain text, byte-correct ──
        List<String> frames = OpenAiSseWriter.textFrames(mapper, "conduit-assistant", "chatcmpl-1", 1L, d.plainText());
        assertThat(frames.get(0)).contains("\"role\":\"assistant\"");
        assertThat(frames).anySatisfy(f -> assertThat(f).contains("\"finish_reason\":\"stop\""));
        assertThat(frames).noneMatch(f -> f.contains("tool_calls"));   // clarify is NOT a tool-call
        assertThat(frames.get(frames.size() - 1)).isEqualTo(OpenAiSseWriter.DONE);
        String reconstructed = frames.stream()
                .filter(f -> f.contains("\"content\":"))
                .map(f -> {
                    try { return mapper.readTree(f.stripLeading())
                            .path("choices").path(0).path("delta").path("content").asText(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .reduce("", String::concat);
        assertThat(reconstructed).isEqualTo(d.plainText());

        // Parity: every offered capability label is visible in the plain-text twin.
        assertThat(d.plainText()).contains("HR Policy Assistant").contains("Payroll Assistant");
    }

    @Test
    void clientWithoutOobSupport_getsCleanTextOnly() {
        ClarificationDescriptor d = buildCapabilityDescriptor();
        List<String> frames = OpenAiSseWriter.textFrames(mapper, "conduit-assistant", "chatcmpl-1", 1L, d.plainText());
        assertThat(frames).noneMatch(f -> f.contains(d.nonce()));
        assertThat(frames).noneMatch(f -> f.contains("clarify_capability"));
        assertThat(frames).noneMatch(f -> f.contains("structured_interaction"));
    }

    // ── Resume classification: a capability pick is a route hint, not an entity ground ──

    @Test
    void inSetCapabilityPick_isCapabilityRouteHint_notEntityGround() {
        ClarificationDescriptor d = buildCapabilityDescriptor();
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarifyResume resume = new ClarifyResume(store);
        when(store.consume("conv-1", d.nonce())).thenReturn(Optional.of(d));

        ClarifyResume.Decision dec = resume.resolve("conv-1", d.nonce(), "meridian.hr.payroll");

        assertThat(dec.consumed()).isTrue();
        assertThat(dec.mode()).isEqualTo(ClarifyResume.Mode.CAPABILITY_SELECTION);
        assertThat(dec.capabilityHint()).isEqualTo("meridian.hr.payroll");  // the route hint
        assertThat(dec.groundedId()).isNull();                              // NOT an entity ground
        assertThat(dec.resumedQuery()).isEqualTo("tell me about that");     // re-drives the original query
        assertThat(dec.inheritedDepth()).isEqualTo(1);
    }

    @Test
    void outOfSetCapabilityPick_demotesToFreeText_noHint() {
        ClarificationDescriptor d = buildCapabilityDescriptor();
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarifyResume resume = new ClarifyResume(store);
        when(store.consume("conv-1", d.nonce())).thenReturn(Optional.of(d));

        ClarifyResume.Decision dec = resume.resolve("conv-1", d.nonce(), "meridian.hr.unknown");

        assertThat(dec.consumed()).isTrue();
        assertThat(dec.mode()).isEqualTo(ClarifyResume.Mode.FREE_TEXT);
        assertThat(dec.capabilityHint()).isNull();
        assertThat(dec.groundedId()).isNull();
    }
}
