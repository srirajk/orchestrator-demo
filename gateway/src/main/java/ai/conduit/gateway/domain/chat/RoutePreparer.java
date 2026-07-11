package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.api.v1.chat.dto.ChatRequest;
import ai.conduit.gateway.api.v1.chat.dto.Message;
import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedMention;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.MentionAligner;
import ai.conduit.gateway.synthesis.input.MentionSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shared pre-routing preparation pipeline (routing spec V2 Piece 3): one component every
 * route-consuming path runs, in place of the three drifting entity/window/mask/route copies that
 * lived across FETCH_DATA, the FOLLOW_UP probe, and the eval endpoint.
 *
 * <p>Ordered stages (spec Piece 3):
 * <ol>
 *   <li><b>derive mentions + focal</b> — from {@link ReferenceGroundingService#groundMentions};</li>
 *   <li><b>choose bounded turns</b> — the SAME window logic the fetch path used (carry-context,
 *       entity-known, fresh-id), but computed as message indices so masking is message-local;</li>
 *   <li><b>ground mentions</b> — one {@code groundMentions} pass over those turns;</li>
 *   <li><b>mask resolved spans per message, THEN join</b> — spans index the ORIGINAL message, so
 *       every selected message is masked in its own coordinate space and only then trimmed + joined
 *       (offsets are never translated through {@code trim()} + newline concat). Both
 *       {@code RESOLVED_ALLOWED} and {@code DENIED} mentions are masked (both have a canonical
 *       resolution); all occurrences of a resolved id/name in-window are blanked; overlapping /
 *       nested / duplicate spans merge before a single reverse-offset application;</li>
 *   <li><b>residual / action policy</b> — if the base window's masked residual is near-empty, WIDEN
 *       to the full masked window before deciding; the resolver's own abstain then handles a still-
 *       empty window (→ the existing required-entity clarify). "Near-empty" is a config-driven
 *       tokenizer/stopword policy, never a Java word list;</li>
 *   <li><b>relaxation signal</b> — bias-to-fetch relaxation is granted ONLY when the focal reference
 *       RESOLVED_ALLOWED and its span was actually masked (masking complete), or a deterministic
 *       non-coverage-scoped {@code id_pattern} matched (V2.1 #4). An unmasked / leaked resolved
 *       mention forfeits relaxation — normal score/margin abstention applies.</li>
 * </ol>
 *
 * <p><b>World-B.</b> Every symbol here is a manifest key, the user's own verbatim words, a character
 * offset, a generic enum, or the config-driven neutral mask deictic. The mask token is banned from
 * ever equalling an {@code EntityType.display/resolveType/key}. No domain literal, id pattern, or
 * entity-type word appears in this class.
 *
 * <p><b>Interim disposition.</b> Piece 3 does NOT decide the terminal outcome; the existing focal
 * {@code ground()} lattice still drives DENY/UNAVAILABLE/CLARIFY. This component only shapes the
 * routing text + relaxation and exposes the full grounded set for Piece 4.
 */
public class RoutePreparer {

    private static final Logger log = LoggerFactory.getLogger(RoutePreparer.class);

    /** A pure deictic used only when the configured mask token collides with a manifest type word. */
    private static final String SAFE_FALLBACK_MASK = "that";
    /** Bound on repeated-occurrence alignment so a degenerate needle cannot loop unbounded. */
    private static final int MAX_OCCURRENCES = 64;

    private final DomainManifestStore manifestStore;
    private final ReferenceGroundingService referenceGrounding;
    private final RoutePreparationPolicy policy;
    private final int routingContextTurns;

    public RoutePreparer(DomainManifestStore manifestStore,
                         ReferenceGroundingService referenceGrounding,
                         RoutePreparationPolicy policy,
                         int routingContextTurns) {
        this.manifestStore = manifestStore;
        this.referenceGrounding = referenceGrounding;
        this.policy = policy;
        this.routingContextTurns = Math.max(1, routingContextTurns);
    }

    /**
     * Runs the full preparation pipeline for one turn.
     *
     * @param request             the client-sent conversation window (masking indexes into it)
     * @param latestPrompt        the latest user message content (raw, as extracted upstream)
     * @param preExtracted        the extracted entity bag (its {@code mentions} drive grounding)
     * @param carryContext        whether this turn may inherit prior-turn vocabulary (FETCH/FOLLOW_UP
     *                            true; CLARIFY false — a bare under-specified ask never widens)
     * @param groundedMemoAllowed whether the focal {@code ground()} lattice already RESOLVED_ALLOWED
     *                            this turn (window-selection parity with the pre-Piece-3 fetch path)
     * @param principal           verified principal — used only for the coverage CHECK, never RESOLVE
     * @param tenantId            tenant scope for RESOLVE/CHECK
     * @param callerToken         caller's verified bearer token threaded to the coverage service
     */
    public PreparedRoute prepare(ChatRequest request, String latestPrompt, EntityBag preExtracted,
                                 boolean carryContext, boolean groundedMemoAllowed,
                                 Principal principal, String tenantId, String callerToken) {
        GroundedReferenceSet groundedSet =
                referenceGrounding.groundMentions(preExtracted, principal, tenantId, callerToken);

        String maskToken = effectiveMaskToken();

        // Distinct resolved needles — every resolved mention's verbatim name plus every resolved
        // canonical id — masked at ALL in-window occurrences (name + id: "Name (ID)").
        Set<String> resolvedNeedles = new LinkedHashSet<>();
        int mentionCount = groundedSet.mentions().size();
        for (GroundedMention gm : groundedSet.mentions()) {
            if (!isResolved(gm)) continue;
            if (gm.mention().verbatimText() != null) resolvedNeedles.add(gm.mention().verbatimText());
            for (GroundedInterpretation gi : gm.interpretations()) {
                if (gi.isResolved() && gi.canonicalId() != null) resolvedNeedles.add(gi.canonicalId());
            }
        }

        // Stage 2 — bounded turns, EXACT parity with the pre-Piece-3 fetch window logic.
        boolean entityKnownForWindow = groundedMemoAllowed
                || (carryContext && hasGroundedResolvableReference(preExtracted, latestPrompt));
        boolean latestHasId = latestContainsResolvableId(latestPrompt);
        int baseWindow;
        if (!carryContext) {
            baseWindow = 1;                                     // CLARIFY: route on the bare message
        } else if (entityKnownForWindow && !latestHasId) {
            baseWindow = 1;                                     // current turn supplies the facet
        } else if (entityKnownForWindow && latestHasId) {
            baseWindow = 2;                                     // fresh id + one prior turn's facet
        } else {
            baseWindow = routingContextTurns;                  // keyword-less: carry the topic
        }

        List<Integer> baseIdx = lastUserTurnIndices(request, baseWindow);
        MaskedText base = maskedJoin(request, baseIdx, groundedSet, resolvedNeedles, maskToken);

        // Stage 5 — residual / widen. Only a context-carrying turn may widen; a CLARIFY turn stays
        // on its bare masked residual so it cannot inherit a confident domain from history.
        MaskedText chosen;
        String maskMode;
        PreparedRoute.ResidualClass residualClass;
        boolean baseNearEmpty = isNearEmpty(base.masked, maskToken);
        if (carryContext && baseNearEmpty) {
            List<Integer> fullIdx = lastUserTurnIndices(request, routingContextTurns);
            MaskedText widened = maskedJoin(request, fullIdx, groundedSet, resolvedNeedles, maskToken);
            chosen = widened;
            maskMode = "masked-widened";
            residualClass = isNearEmpty(widened.masked, maskToken)
                    ? PreparedRoute.ResidualClass.EMPTY : PreparedRoute.ResidualClass.WIDENED;
        } else {
            chosen = base;
            maskMode = base.spanCount > 0 ? "masked-base" : "unmasked-base";
            residualClass = baseNearEmpty
                    ? PreparedRoute.ResidualClass.EMPTY : PreparedRoute.ResidualClass.HAS_ACTION;
        }

        // Stage 6 — relaxation. A leaked (unmasked) resolved name forfeits relaxation; a deterministic
        // non-coverage-scoped id match earns it independently (V2.1 #4).
        int alignmentMisses = alignmentMissCount(groundedSet, chosen.indices);
        boolean maskingComplete = alignmentMisses == 0 && !leaks(chosen.masked, resolvedNeedles);
        boolean focalAllowed = groundedSet.focalVerdict().isAllowed();
        boolean nonCoverageScopedId = anyNonCoverageScopedIdMatch(groundedSet, latestPrompt);
        boolean relaxationAllowed = (focalAllowed && maskingComplete) || nonCoverageScopedId;

        PreparedRoute.MaskDiagnostics diagnostics = new PreparedRoute.MaskDiagnostics(
                maskMode, mentionCount, chosen.spanCount, alignmentMisses, residualClass.name(),
                stableHash(chosen.unmasked), stableHash(chosen.masked));

        return new PreparedRoute(chosen.masked, groundedSet, relaxationAllowed, residualClass, diagnostics);
    }

    // ── Masking ────────────────────────────────────────────────────────────────────────────────

    /** A masked window plus its unmasked twin (for the audit hash) and how many spans were blanked. */
    private record MaskedText(String masked, String unmasked, int spanCount, List<Integer> indices) {}

    /**
     * Masks each selected message in its OWN coordinate space, then trims + joins (oldest→newest). A
     * single-turn window returns the raw (untrimmed) message, mirroring the legacy latest-message
     * routing text; a multi-turn window trims each masked piece and joins with a newline.
     */
    private MaskedText maskedJoin(ChatRequest request, List<Integer> indices,
                                  GroundedReferenceSet groundedSet, Set<String> resolvedNeedles,
                                  String maskToken) {
        List<Message> msgs = request.messages();
        if (msgs == null || indices.isEmpty()) return new MaskedText("", "", 0, indices);
        boolean single = indices.size() == 1;
        List<String> maskedPieces = new ArrayList<>();
        List<String> rawPieces = new ArrayList<>();
        int spanCount = 0;
        for (int idx : indices) {
            String original = msgs.get(idx).content();
            if (original == null) original = "";
            List<MentionSpan> maskSpans = collectMaskSpans(original, idx, groundedSet, resolvedNeedles);
            List<MentionSpan> merged = mergeSpans(maskSpans);
            spanCount += merged.size();
            String masked = applyMask(original, merged, maskToken);
            maskedPieces.add(single ? masked : masked.trim());
            rawPieces.add(single ? original : original.trim());
        }
        String maskedJoined = String.join("\n", maskedPieces);
        String rawJoined = String.join("\n", rawPieces);
        return new MaskedText(maskedJoined, rawJoined, spanCount, indices);
    }

    /**
     * Every span to blank in one message: each resolved mention's own recorded span (when it belongs
     * to this message) plus all occurrences of every resolved needle (name + canonical id) aligned in
     * this message. Duplicates/overlaps are reconciled by {@link #mergeSpans}.
     */
    private List<MentionSpan> collectMaskSpans(String original, int idx,
                                               GroundedReferenceSet groundedSet, Set<String> resolvedNeedles) {
        List<MentionSpan> spans = new ArrayList<>();
        for (GroundedMention gm : groundedSet.mentions()) {
            if (!isResolved(gm)) continue;
            if (gm.mention().messageIndex() == idx && gm.mention().span() != null) {
                spans.add(gm.mention().span());
            }
        }
        for (String needle : resolvedNeedles) {
            spans.addAll(allOccurrences(original, needle));
        }
        return spans;
    }

    /** Every deterministic alignment of {@code needle} within {@code original}, capped for safety. */
    private static List<MentionSpan> allOccurrences(String original, String needle) {
        List<MentionSpan> out = new ArrayList<>();
        for (int occ = 0; occ < MAX_OCCURRENCES; occ++) {
            MentionSpan span = MentionAligner.align(original, needle, occ);
            if (span == null) break;
            out.add(span);
        }
        return out;
    }

    /** Merge overlapping / nested / adjacent / duplicate spans into a disjoint, ascending set. */
    private static List<MentionSpan> mergeSpans(List<MentionSpan> spans) {
        if (spans.size() <= 1) return new ArrayList<>(spans);
        List<MentionSpan> sorted = new ArrayList<>(spans);
        sorted.sort((a, b) -> a.start() != b.start() ? Integer.compare(a.start(), b.start())
                : Integer.compare(a.end(), b.end()));
        List<MentionSpan> merged = new ArrayList<>();
        int curStart = sorted.get(0).start();
        int curEnd = sorted.get(0).end();
        for (int i = 1; i < sorted.size(); i++) {
            MentionSpan s = sorted.get(i);
            if (s.start() <= curEnd) {                 // overlap, nesting, or touching
                curEnd = Math.max(curEnd, s.end());
            } else {
                merged.add(new MentionSpan(curStart, curEnd));
                curStart = s.start();
                curEnd = s.end();
            }
        }
        merged.add(new MentionSpan(curStart, curEnd));
        return merged;
    }

    /**
     * Applies the mask token to each merged span (reverse offset so earlier offsets stay valid) and,
     * first, sanitizes any user-typed occurrence of the mask token that lies OUTSIDE a mask span by
     * neutralizing it to equal-length whitespace (so a user cannot inject the deictic).
     */
    private String applyMask(String original, List<MentionSpan> maskSpans, String maskToken) {
        List<MentionSpan> sanitizeSpans = sanitizeSpans(original, maskToken, maskSpans);
        // Combine: mask spans → the deictic; sanitize spans → equal-length spaces. Apply right→left.
        record Edit(int start, int end, String replacement) {}
        List<Edit> edits = new ArrayList<>();
        for (MentionSpan s : maskSpans) edits.add(new Edit(s.start(), s.end(), maskToken));
        for (MentionSpan s : sanitizeSpans) edits.add(new Edit(s.start(), s.end(), " ".repeat(s.length())));
        edits.sort((a, b) -> Integer.compare(b.start(), a.start()));   // right → left
        StringBuilder sb = new StringBuilder(original);
        int prevStart = Integer.MAX_VALUE;
        for (Edit e : edits) {
            if (e.end() > prevStart) continue;         // defensive: skip any residual overlap
            sb.replace(e.start(), e.end(), e.replacement());
            prevStart = e.start();
        }
        return sb.toString();
    }

    /** Occurrences of the mask token in the message that do NOT overlap a mask span (to neutralize). */
    private static List<MentionSpan> sanitizeSpans(String original, String maskToken, List<MentionSpan> maskSpans) {
        List<MentionSpan> out = new ArrayList<>();
        for (MentionSpan occ : allOccurrences(original, maskToken)) {
            boolean overlaps = false;
            for (MentionSpan m : maskSpans) {
                if (occ.start() < m.end() && m.start() < occ.end()) { overlaps = true; break; }
            }
            if (!overlaps) out.add(occ);
        }
        return out;
    }

    // ── Relaxation + residual helpers ────────────────────────────────────────────────────────────

    /** True when a resolved needle still appears (normalized) in the masked routing text — a leak. */
    private static boolean leaks(String maskedText, Set<String> resolvedNeedles) {
        if (maskedText == null || maskedText.isBlank()) return false;
        String hay = MentionAligner.normalizeNeedle(maskedText);
        for (String needle : resolvedNeedles) {
            String n = MentionAligner.normalizeNeedle(needle);
            if (!n.isEmpty() && hay.contains(n)) return true;
        }
        return false;
    }

    /** Resolved mentions whose owning message is in-window but that carry no span (unmaskable). */
    private static int alignmentMissCount(GroundedReferenceSet groundedSet, List<Integer> windowIdx) {
        int misses = 0;
        for (GroundedMention gm : groundedSet.mentions()) {
            if (!isResolved(gm)) continue;
            if (gm.mention().span() == null && windowIdx.contains(gm.mention().messageIndex())) misses++;
        }
        return misses;
    }

    private boolean anyNonCoverageScopedIdMatch(GroundedReferenceSet groundedSet, String latestPrompt) {
        boolean fromMention = groundedSet.mentions().stream()
                .anyMatch(GroundedMention::nonCoverageScopedIdPatternMatch);
        return fromMention || manifestStore.matchesNonCoverageScopedResolvableId(latestPrompt);
    }

    /**
     * A masked residual is "near-empty" when it carries fewer than {@code minContentTokens} tokens
     * that are neither a config stopword nor part of the mask deictic. Config-driven — no Java word
     * list (CLAUDE.md §5).
     */
    private boolean isNearEmpty(String text, String maskToken) {
        Set<String> maskTokens = tokenize(maskToken);
        int content = 0;
        for (String tok : tokenize(text)) {
            if (policy.residualStopwords().contains(tok)) continue;
            if (maskTokens.contains(tok)) continue;
            content++;
            if (content >= policy.minContentTokens()) return false;
        }
        return content < policy.minContentTokens();
    }

    private static Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String tok : text.toLowerCase().split("[^\\p{Alnum}]+")) {
            if (!tok.isEmpty()) out.add(tok);
        }
        return out;
    }

    private static boolean isResolved(GroundedMention gm) {
        return gm.interpretations().stream().anyMatch(GroundedInterpretation::isResolved);
    }

    // ── Window + reference helpers (manifest-driven ports of the fetch path) ─────────────────────

    /** Indices (into {@code request.messages()}) of the last {@code window} user turns, oldest→newest. */
    private static List<Integer> lastUserTurnIndices(ChatRequest request, int window) {
        List<Integer> idx = new ArrayList<>();
        List<Message> msgs = request.messages();
        if (msgs == null) return idx;
        for (int i = msgs.size() - 1; i >= 0 && idx.size() < window; i--) {
            Message m = msgs.get(i);
            if (m != null && "user".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                idx.add(i);
            }
        }
        Collections.reverse(idx);
        return idx;
    }

    private boolean latestContainsResolvableId(String latestPrompt) {
        if (latestPrompt == null || latestPrompt.isBlank()) return false;
        return manifestStore.entityTypes().stream()
                .filter(EntityType::isResolvable)
                .map(EntityType::idPattern)
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .anyMatch(pattern -> manifestStore.compiledIdPattern(pattern).matcher(latestPrompt).find());
    }

    private boolean hasGroundedResolvableReference(EntityBag preExtracted, String latestPrompt) {
        List<EntityType> resolvables = manifestStore.entityTypes().stream()
                .filter(EntityType::isResolvable).toList();
        for (EntityType et : resolvables) {
            if (preExtracted != null && et.extractAs() != null
                    && preExtracted.reference(et.extractAs()) != null) {
                return true;
            }
            String pattern = et.idPattern();
            if (pattern != null && !pattern.isBlank() && latestPrompt != null
                    && manifestStore.compiledIdPattern(pattern).matcher(latestPrompt).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The configured mask deictic, unless it collides with a manifest {@code EntityType}
     * display/resolveType/key — banning a domain type word as the token (spec DO-NOT). On a collision
     * (or a blank config) it falls back to a pure deictic that names no entity kind.
     */
    private String effectiveMaskToken() {
        String tok = policy.maskToken();
        if (tok == null || tok.isBlank()) return SAFE_FALLBACK_MASK;
        String candidate = tok.strip();
        String lower = candidate.toLowerCase();
        Set<String> banned = new LinkedHashSet<>();
        for (EntityType et : manifestStore.entityTypes()) {
            addBanned(banned, et.display());
            addBanned(banned, et.resolveType());
            addBanned(banned, et.key());
        }
        if (banned.contains(lower)) {
            log.warn("Configured routing mask token collides with a manifest entity type word; "
                    + "falling back to a neutral deictic (World-B). Set conduit.routing.entity-mask-token "
                    + "to a domain-neutral value.");
            return SAFE_FALLBACK_MASK;
        }
        return candidate;
    }

    private static void addBanned(Set<String> banned, String value) {
        if (value != null && !value.isBlank()) banned.add(value.strip().toLowerCase());
    }

    private static String stableHash(String text) {
        if (text == null || text.isEmpty()) return "0";
        return Integer.toHexString(text.hashCode());
    }
}
