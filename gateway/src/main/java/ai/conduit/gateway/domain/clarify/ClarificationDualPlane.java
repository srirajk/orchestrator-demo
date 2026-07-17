package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The dual-plane seam for a structured clarification. One {@link ClarificationDescriptor} drives BOTH
 * planes; the chat pipeline owns the plain-text SSE plane (it holds the emitter), and this component
 * owns the OUT-OF-BAND plane: it stores the descriptor for Phase-2 resume and publishes a
 * {@code structured_interaction} trace event on the glass-box lane (the {@code TraceStreamController}
 * pattern). The two planes therefore cannot drift — the SSE streams {@link ClarificationDescriptor#plainText()}
 * and the OOB carries {@link ClarificationDescriptor#toStructuredInteraction()}, both from one object.
 *
 * <p>The out-of-band emission is ADDITIVE — it never touches the OpenAI SSE byte stream, so a client
 * with no glass-box support still receives the clean plain-text clarification and nothing else. It can
 * be disabled with {@code conduit.clarify.structured-interaction.enabled=false}, which leaves the legacy
 * text-only path byte-identical.
 */
@Component
public class ClarificationDualPlane {

    private static final Logger log = LoggerFactory.getLogger(ClarificationDualPlane.class);

    /** The out-of-band trace event type carrying the structured form. */
    public static final String EVENT_TYPE = "structured_interaction";

    private final TraceEventPublisher publisher;
    private final ClarificationDescriptorStore store;
    private final boolean enabled;

    public ClarificationDualPlane(
            TraceEventPublisher publisher,
            ClarificationDescriptorStore store,
            @Value("${conduit.clarify.structured-interaction.enabled:true}") boolean enabled) {
        this.publisher = publisher;
        this.store = store;
        this.enabled = enabled;
    }

    /**
     * Emit the out-of-band plane for {@code descriptor}: persist it (single-use, TTL) and publish the
     * structured form on the glass-box lane. The caller is responsible for streaming
     * {@code descriptor.plainText()} on the chat SSE — the two planes share this one object.
     *
     * @return the descriptor, for fluent use at the call site
     */
    public ClarificationDescriptor offer(ClarificationDescriptor descriptor, String requestId) {
        if (!enabled || descriptor == null) return descriptor;
        try {
            store.store(descriptor);
            publisher.publish(TraceEvent.of(EVENT_TYPE, requestId, descriptor.conversationId(),
                    descriptor.toStructuredInteraction()));
        } catch (Exception e) {
            // Best-effort: a failed OOB emission must not break the request; the plain-text twin still streams.
            log.warn("ClarificationDualPlane.offer failed for convId={}: {}",
                    descriptor.conversationId(), e.getMessage());
        }
        return descriptor;
    }

    /**
     * Latest-turn-wins invalidation: drop any outstanding descriptor for the conversation. Called by the
     * chat path at the start of every new user turn so a newer free-text turn supersedes an outstanding
     * form. Returns the depth of the descriptor that WAS outstanding (0 if none) so the caller can
     * inherit the lineage depth for the loop bound before it is dropped.
     */
    public int supersede(String conversationId) {
        if (!enabled || conversationId == null) return 0;
        int inherited = store.inheritedDepth(conversationId);
        store.invalidate(conversationId);
        return inherited;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
