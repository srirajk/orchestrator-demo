package ai.conduit.gateway.domain.clarify;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The Phase-2 RESUME decision (the read side of the Phase-1 {@link ClarificationDescriptor} contract).
 * A resume presents the outstanding descriptor's single-use {@code nonce}; this service
 * {@link ClarificationDescriptorStore#consume(String, String) consumes} it (single-use, latest-turn-wins)
 * and decides how the folded answer re-enters the pipeline. It NEVER invokes an agent, checks
 * entitlement, or synthesizes — it only resolves the descriptor and classifies the submission. The
 * caller ({@code ChatService}) then re-drives the FULL pipeline (grounding → routing → CHECK → invoke →
 * synth), so entitlement is re-verified on the resumed turn and the TOCTOU window dissolves.
 *
 * <p><b>The three dispositions.</b>
 * <ul>
 *   <li><b>{@link Mode#GROUNDED_SELECTION}</b> — the submitted {@code selection} is one of the offered,
 *       entitled candidates ({@link ClarificationDescriptor#validate} ⇒ {@code IN_SET}). The resumed turn
 *       re-runs the descriptor's {@link ClarificationDescriptor#originatingQuery() original query} with the
 *       chosen canonical id injected as a grounded reference, so grounding + coverage CHECK re-run on that
 *       exact id (a denial at resume is honoured — the form is never trusted as authorization).</li>
 *   <li><b>{@link Mode#FREE_TEXT}</b> — a free-text escape answer, OR a {@code selection} that is NOT in
 *       the offered set (an oracle-safe demotion: never silently ground a value the user did not pick from
 *       an offered choice). The folded free text is UNTRUSTED DATA (hard-rule 4c): it re-drives the
 *       pipeline as a normal query, never a privileged ground and never an instruction to the synthesizer.</li>
 *   <li><b>{@link Mode#NONE}</b> — no nonce, or the descriptor was already consumed / expired / superseded
 *       (a replay or a stale form). No privileged injection; the turn is processed as an ordinary chat turn.</li>
 * </ul>
 *
 * <p><b>World B.</b> This class authors no domain literal — it carries only the descriptor's own DATA
 * (the canonical id, the manifest id-pattern, the original query) across the resume seam.
 */
@Component
public class ClarifyResume {

    private final ClarificationDescriptorStore store;

    public ClarifyResume(ClarificationDescriptorStore store) {
        this.store = store;
    }

    /** How the folded answer re-enters the pipeline. */
    public enum Mode {
        /** No live descriptor consumed (no nonce, or a replay/expired/superseded form) — ordinary turn. */
        NONE,
        /** An offered, entitled candidate was chosen — re-drive the original query with it grounded. */
        GROUNDED_SELECTION,
        /** A free-text escape, or an out-of-set (demoted) selection — untrusted DATA, re-drive as a query. */
        FREE_TEXT
    }

    /**
     * The resume decision the caller acts on.
     *
     * @param consumed       true iff a live descriptor was consumed by this nonce (single-use burned it)
     * @param mode           how the answer re-enters the pipeline
     * @param groundedId      the chosen canonical id to inject as a grounded reference (GROUNDED_SELECTION only)
     * @param idPattern      the descriptor's manifest id-pattern — resolves the entity key to inject under
     * @param resumedQuery    the query text to route on for GROUNDED_SELECTION (the descriptor's original
     *                        query); null for FREE_TEXT (the folded free-text message content is routed as-is)
     * @param inheritedDepth  the consumed descriptor's lineage depth, so a re-clarification THIS turn
     *                        advances the loop bound instead of resetting it
     */
    public record Decision(boolean consumed, Mode mode, String groundedId, String idPattern,
                           String resumedQuery, int inheritedDepth) {
        public static Decision none() {
            return new Decision(false, Mode.NONE, null, null, null, 0);
        }
    }

    /**
     * Resolve a resume submission. {@code selection} is the raw option value from a chip choice (null for a
     * pure free-text escape). The folded free-text message content is NOT needed here — for FREE_TEXT the
     * caller routes on the (already-folded) latest user message; only GROUNDED_SELECTION rewrites the turn to
     * the descriptor's original query.
     */
    public Decision resolve(String conversationId, String nonce, String selection) {
        if (isBlank(nonce)) return Decision.none();
        Optional<ClarificationDescriptor> opt = store.consume(conversationId, nonce);
        if (opt.isEmpty()) return Decision.none();   // replay / expired / superseded → ordinary turn
        ClarificationDescriptor d = opt.get();
        int depth = d.clarifyDepth();
        if (!isBlank(selection)
                && d.validate(selection) == ClarificationDescriptor.Submission.IN_SET) {
            String query = !isBlank(d.originatingQuery()) ? d.originatingQuery() : selection;
            return new Decision(true, Mode.GROUNDED_SELECTION, selection, d.idPattern(), query, depth);
        }
        // Free-text escape, or an out-of-set selection: consumed, but NEVER grounded — demote to untrusted
        // free text and let the folded message content re-drive the pipeline as an ordinary query.
        return new Decision(true, Mode.FREE_TEXT, null, d.idPattern(), null, depth);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
