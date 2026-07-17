package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.domain.coverage.CoverageResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Composes a {@link ClarificationDescriptor} from grounded, ENTITLED inputs. This is the single place
 * the enumeration-oracle fix lives: the offered candidate set is the caller's entitled book, optionally
 * intersected with a raw resolve-candidate id set — so a candidate the principal is NOT entitled to can
 * never enter the descriptor, and no "N more hidden" count is ever produced.
 *
 * <p><b>World B.</b> This class authors NO domain literal. Every label/noun/id it emits is passed in as
 * DATA (coverage resource labels + ids, the manifest entity-type display, manifest clarification copy);
 * the only strings it owns are domain-agnostic templates (the free-text escape prompt). It is covered by
 * {@code scripts/world-b-check.sh}.
 */
@Component
public class ClarificationDescriptorFactory {

    /** Hard cap on offered options (brief guardrail: 3–4). Config, never a constant (CLAUDE.md §5). */
    private final int maxOptions;
    /** Hard depth cap per query lineage (brief guardrail (g): 2). Config. */
    private final int maxClarifyDepth;
    /** Redis TTL for the stored descriptor. Config. */
    private final long ttlSeconds;
    /** Domain-agnostic free-text escape copy (a template, not domain copy). Config. */
    private final String freeTextPrompt;

    public ClarificationDescriptorFactory(
            @Value("${conduit.clarify.max-options:4}") int maxOptions,
            @Value("${conduit.clarify.max-depth:2}") int maxClarifyDepth,
            @Value("${conduit.clarify.descriptor-ttl-seconds:900}") long ttlSeconds,
            @Value("${conduit.clarify.free-text-prompt:None of these — reply with it directly.}") String freeTextPrompt) {
        this.maxOptions = Math.max(1, maxOptions);
        this.maxClarifyDepth = Math.max(1, maxClarifyDepth);
        this.ttlSeconds = ttlSeconds;
        this.freeTextPrompt = freeTextPrompt;
    }

    public int maxClarifyDepth() {
        return maxClarifyDepth;
    }

    /**
     * Build a {@link InteractionKind#CLARIFY_ENTITY} descriptor over an entitled book.
     *
     * <p><b>The security contract.</b> {@code entitledBook} is the principal's CHECK-passed coverage set
     * (the {@code discover} result). {@code restrictToIds}, when non-empty, are the raw resolve
     * candidates for the ambiguous reference — which may include entities OUTSIDE the book. The offered
     * set is {@code entitledBook ∩ restrictToIds} (or the whole entitled book when no restriction is
     * given): an id in {@code restrictToIds} but not in the book is silently dropped, never surfaced and
     * never counted. This is the enumeration-oracle fix — display candidates pass the CHECK before they
     * enter the descriptor.
     *
     * @param conversationId   Redis key scope
     * @param question         base question text (manifest copy)
     * @param plainText        the fully authored SSE text (template or composed) — the plain-text twin
     * @param entityNoun       manifest display noun for the slot; may be null
     * @param idPattern        the slot entity's manifest {@code id_pattern} (Phase-2 submit validation)
     * @param entitledBook     the principal's entitled coverage resources (the CHECK-passed universe)
     * @param restrictToIds    raw candidate ids to intersect with the book; empty ⇒ offer the whole book
     * @param originatingQuery the user query that triggered the clarification (untrusted; carried for resume)
     * @param clarifyDepth     this clarification's depth in the lineage (already incremented by the caller)
     */
    public ClarificationDescriptor forEntity(String conversationId, String question, String plainText,
                                             String entityNoun, String idPattern,
                                             List<CoverageResource> entitledBook,
                                             Collection<String> restrictToIds,
                                             String originatingQuery, int clarifyDepth) {
        Set<String> restrict = restrictToIds == null ? Set.of()
                : new HashSet<>(restrictToIds.stream().filter(id -> id != null && !id.isBlank()).toList());

        List<ClarificationOption> offered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CoverageResource r : entitledBook == null ? List.<CoverageResource>of() : entitledBook) {
            if (r == null || r.id() == null || r.id().isBlank()) continue;
            // Enumeration-oracle fix: an entitled book entry is offerable; a restriction only NARROWS.
            if (!restrict.isEmpty() && !restrict.contains(r.id())) continue;
            if (!seen.add(r.id())) continue;                 // de-dupe by id
            offered.add(new ClarificationOption(r.id(), r.label(), r.id()));
            if (offered.size() >= maxOptions) break;         // cap — no hidden-count is emitted
        }

        return new ClarificationDescriptor(
                UUID.randomUUID().toString(),
                conversationId,
                InteractionKind.CLARIFY_ENTITY,
                entityNoun,
                question,
                plainText,
                offered,
                true,                       // free-text escape always present
                freeTextPrompt,
                idPattern,
                originatingQuery,
                clarifyDepth,
                maxClarifyDepth,
                System.currentTimeMillis(),
                ttlSeconds,
                true,                       // single-use
                true);                      // blocking form (replaces no-service)
    }

    /**
     * Build a {@link InteractionKind#CLARIFY_CAPABILITY} descriptor over Cerbos-filtered capability
     * options. The caller supplies ONLY capabilities the principal may invoke (Cerbos-filtered), so —
     * as with entity candidates — no unauthorized option enters the descriptor and no hidden count is
     * emitted. Reserved surface: no live ChatService trigger wires this yet; the envelope + factory
     * support the kind so Phase 2/3 can adopt it without a new transport.
     */
    public ClarificationDescriptor forCapability(String conversationId, String question, String plainText,
                                                 String entityNoun, List<ClarificationOption> entitledCapabilities,
                                                 String originatingQuery, int clarifyDepth) {
        List<ClarificationOption> offered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClarificationOption o : entitledCapabilities == null ? List.<ClarificationOption>of() : entitledCapabilities) {
            if (o == null || !seen.add(o.value())) continue;
            offered.add(o);
            if (offered.size() >= maxOptions) break;
        }
        return new ClarificationDescriptor(
                UUID.randomUUID().toString(), conversationId, InteractionKind.CLARIFY_CAPABILITY,
                entityNoun, question, plainText, offered, true, freeTextPrompt, null,
                originatingQuery, clarifyDepth, maxClarifyDepth,
                System.currentTimeMillis(), ttlSeconds, true, true);
    }
}
