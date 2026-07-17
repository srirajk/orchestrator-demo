package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.domain.coverage.CoverageResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase-2 RESUME read-side unit tests over {@link ClarifyResume} — the pure consume-and-classify decision,
 * with the descriptor store mocked. Covers the whole contract lattice: an in-set chip selection grounds the
 * chosen id and re-drives the original query; an out-of-set selection DEMOTES to free text (never silently
 * grounds); a free-text escape is untrusted DATA; single-use/replay/expiry/latest-turn all yield NONE (no
 * privileged injection). No pipeline, no LLM — the decision is deterministic.
 */
class ClarifyResumeTest {

    private final ClarificationDescriptorFactory factory =
            new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");

    private ClarificationDescriptor descriptor(String nonce) {
        List<CoverageResource> book = List.of(
                new CoverageResource("ENT-1", "Alpha", "sd"),
                new CoverageResource("ENT-2", "Beta", "sd"));
        ClarificationDescriptor base = factory.forEntity("conv-1", "Which one?", "Which one?\n- Alpha (ENT-1)",
                "resource", "ENT-\\d+", book, List.of(), "show the details", 1);
        // Pin the nonce so the mocked store.consume can key on it deterministically.
        return new ClarificationDescriptor(nonce, base.conversationId(), base.kind(), base.entityNoun(),
                base.question(), base.plainText(), base.offeredCandidates(), base.freeTextEnabled(),
                base.freeTextPrompt(), base.idPattern(), base.originatingQuery(), base.clarifyDepth(),
                base.maxClarifyDepth(), base.createdAtEpochMs(), base.ttlSeconds(), base.singleUse(),
                base.blocking());
    }

    @Test
    void inSetSelection_groundsChosenId_andReDrivesOriginalQuery() {
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarifyResume resume = new ClarifyResume(store);
        when(store.consume("conv-1", "n1")).thenReturn(Optional.of(descriptor("n1")));

        ClarifyResume.Decision d = resume.resolve("conv-1", "n1", "ENT-2");

        assertThat(d.consumed()).isTrue();
        assertThat(d.mode()).isEqualTo(ClarifyResume.Mode.GROUNDED_SELECTION);
        assertThat(d.groundedId()).isEqualTo("ENT-2");
        assertThat(d.idPattern()).isEqualTo("ENT-\\d+");
        assertThat(d.resumedQuery()).isEqualTo("show the details");   // the descriptor's original query
        assertThat(d.inheritedDepth()).isEqualTo(1);
    }

    @Test
    void inSetSelection_isCaseInsensitiveMatch() {
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarifyResume resume = new ClarifyResume(store);
        when(store.consume("conv-1", "n1")).thenReturn(Optional.of(descriptor("n1")));

        ClarifyResume.Decision d = resume.resolve("conv-1", "n1", "ent-1");
        assertThat(d.mode()).isEqualTo(ClarifyResume.Mode.GROUNDED_SELECTION);
        assertThat(d.groundedId()).isEqualTo("ent-1");
    }

    @Test
    void outOfSetSelection_demotesToFreeText_neverGrounds() {
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarifyResume resume = new ClarifyResume(store);
        when(store.consume("conv-1", "n1")).thenReturn(Optional.of(descriptor("n1")));

        // A value that is NOT one of the offered, entitled options — the oracle-safe demotion.
        ClarifyResume.Decision d = resume.resolve("conv-1", "n1", "ENT-999");

        assertThat(d.consumed()).isTrue();                                    // the descriptor was still burned
        assertThat(d.mode()).isEqualTo(ClarifyResume.Mode.FREE_TEXT);
        assertThat(d.groundedId()).isNull();                                  // never silently grounded
        assertThat(d.inheritedDepth()).isEqualTo(1);
    }

    @Test
    void freeTextEscape_isUntrustedFreeText_noGround() {
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarifyResume resume = new ClarifyResume(store);
        when(store.consume("conv-1", "n1")).thenReturn(Optional.of(descriptor("n1")));

        ClarifyResume.Decision d = resume.resolve("conv-1", "n1", null);   // no chip selection

        assertThat(d.consumed()).isTrue();
        assertThat(d.mode()).isEqualTo(ClarifyResume.Mode.FREE_TEXT);
        assertThat(d.groundedId()).isNull();
    }

    @Test
    void noNonce_isNone_andNeverTouchesTheStore() {
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarifyResume resume = new ClarifyResume(store);

        assertThat(resume.resolve("conv-1", null, "ENT-1").mode()).isEqualTo(ClarifyResume.Mode.NONE);
        assertThat(resume.resolve("conv-1", "  ", "ENT-1").consumed()).isFalse();
        verify(store, never()).consume(eq("conv-1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void replayOrExpiredOrSuperseded_consumeEmpty_isNone() {
        ClarificationDescriptorStore store = mock(ClarificationDescriptorStore.class);
        ClarifyResume resume = new ClarifyResume(store);
        // Single-use already burned it (a replay), or it TTL-expired, or a newer free-text turn invalidated
        // it (latest-turn-wins) — consume returns empty, so no privileged injection happens.
        when(store.consume("conv-1", "stale")).thenReturn(Optional.empty());

        ClarifyResume.Decision d = resume.resolve("conv-1", "stale", "ENT-1");

        assertThat(d.consumed()).isFalse();
        assertThat(d.mode()).isEqualTo(ClarifyResume.Mode.NONE);
        verify(store).consume("conv-1", "stale");
    }
}
