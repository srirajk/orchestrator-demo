package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.DomainManifestStore.IdentifiedReference;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionSource;
import ai.conduit.gateway.synthesis.input.MentionSpan;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stage-1 reference grounding - the deterministic, PRE-ROUTING step that resolves a human reference
 * (a typed id like {@code REL-00188}, or an LLM-extracted name like "the Okafor account") to a
 * canonical id and runs the coverage CHECK on it, before the semantic router ever sees the query.
 *
 * <p><b>Why this exists (the class fix).</b> Coverage DENY and the CLARIFY decision are deterministic
 * and their inputs (the extracted entity bag, the manifest {@code required_context}, the coverage
 * service) do not depend on routing at all. When they were computed only <i>inside</i> the coverage
 * pipeline - which is gated behind the routing-confidence abstain - any phrasing that depressed the
 * embedding score (a possessive apostrophe, injection prose, a bare facet word) silently converted a
 * would-be DENY into an unhelpful "no service." Grounding moves the RESOLVE+CHECK to before routing,
 * so the semantic score can only ever choose between safe outcomes and never sits between the user
 * and a denial.
 *
 * <p><b>The four-way verdict lattice</b> (see {@link Verdict}):
 * <ul>
 *   <li>{@code RESOLVED_ALLOWED} - resolved uniquely and the coverage CHECK allowed: the caller
 *       memoizes the {@link #resolvedId()} and routes with {@code entityKnown=true}.</li>
 *   <li>{@code DENIED} - resolved uniquely but the coverage CHECK denied: a terminal DENY named with
 *       the owning sub-domain's copy, without ever routing.</li>
 *   <li>{@code AMBIGUOUS} - the reference matched several candidates: fall through to the existing
 *       {@code discover intersect candidates} clarify.</li>
 *   <li>{@code NOT_FOUND} - the reference resolved to nothing: demote to content (it stays in the
 *       agent prompt; it never fills a coverage slot, never a CHECK, never a denial).</li>
 *   <li>{@code UNAVAILABLE} - the coverage service was unreachable: FAIL CLOSED (deny access).</li>
 *   <li>{@code NONE} - no groundable reference in this turn: the normal pipeline runs unchanged.</li>
 * </ul>
 *
 * <p><b>Invariants.</b> RESOLVE is principal-agnostic (scoped by tenant, never the caller's book);
 * the coverage CHECK is the only gate and only ever receives ids that RESOLVED; the service fails
 * closed on {@link CoverageClient.CoverageUnavailableException}. World B: every symbol is
 * manifest-derived - no domain name, entity-type literal, id prefix, or user-facing copy here.
 */
@Service
public class ReferenceGroundingService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceGroundingService.class);

    private final DomainManifestStore manifestStore;
    private final CoverageClient coverageClient;
    private final GroundingBudget budget;
    // Virtual-thread executor for the bounded multi-reference fan-out. VT-per-task keeps thousands of
    // in-flight blocking coverage calls cheap; the Semaphore in groundMentions is what actually bounds
    // concurrency. Java 25 + JEP-491 means a blocking WebClient.block()/semaphore acquire on a VT does
    // not pin its carrier (the gateway's prior livelock class), so this fan-out is safe.
    private final ExecutorService groundingExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public ReferenceGroundingService(DomainManifestStore manifestStore, CoverageClient coverageClient,
                                     GroundingBudget budget) {
        this.manifestStore = manifestStore;
        this.coverageClient = coverageClient;
        this.budget = budget;
    }

    /** Convenience constructor with default budgets - for hand-built (unit-test) construction. */
    public ReferenceGroundingService(DomainManifestStore manifestStore, CoverageClient coverageClient) {
        this(manifestStore, coverageClient, GroundingBudget.defaults());
    }

    @PreDestroy
    void shutdown() {
        groundingExecutor.shutdownNow();
    }

    /**
     * Grounds the turn's single strongest reference. A typed id in {@code latestPrompt} (most
     * specific) is preferred over an LLM-extracted name in {@code preExtracted}; when neither is
     * present the verdict is {@link Verdict#NONE}. Both sources yield the same
     * {@link IdentifiedReference} shape, so a single RESOLVE->CHECK lattice serves both.
     *
     * @param preExtracted the entity bag from the intent+entity extraction (may be null)
     * @param latestPrompt the latest user message (source of a typed-id literal)
     * @param principal    the verified principal - used only for the coverage CHECK, never for RESOLVE
     * @param tenantId     the tenant scope for RESOLVE/CHECK
     * @param callerToken  the caller's verified bearer token, threaded to the coverage service
     */
    public GroundingResult ground(EntityBag preExtracted, String latestPrompt,
                                  Principal principal, String tenantId, String callerToken) {
        IdentifiedReference ref = manifestStore.identifyByIdPattern(latestPrompt)
                .filter(r -> r.coverage() != null)
                .or(() -> manifestStore.identifyByReference(preExtracted, latestPrompt)
                        .filter(r -> r.coverage() != null))
                .orElse(null);
        if (ref == null) return GroundingResult.none();

        String subDomainId = ref.subDomain() != null ? ref.subDomain().subDomainId() : null;
        String principalId = principal != null ? principal.id() : Principal.anonymous().id();
        try {
            CoverageResolveResult rr = coverageClient.resolve(
                    ref.id(), ref.entityType().resolveType(), tenantId, ref.coverage(), callerToken);
            if (rr.resolved() && rr.id() != null) {
                // CHECK only ever receives a RESOLVED id - never the raw human reference.
                CoverageCheckResult check = coverageClient.check(
                        principalId, tenantId, rr.id(), ref.coverage(), callerToken);
                if (!check.allowed()) {
                    return GroundingResult.denied(rr.id(), subDomainId, check.reason(), principalId);
                }
                return GroundingResult.allowed(rr.id(), ref.entityType(), subDomainId, ref.coverage());
            }
            if (rr.isAmbiguous()) {
                return GroundingResult.ambiguous(subDomainId);
            }
            // NOT_FOUND - demote to content, never a denial (fixes the wrong-type-entity false deny).
            return GroundingResult.notFound(subDomainId);
        } catch (CoverageClient.CoverageUnavailableException e) {
            log.error("Reference grounding coverage unavailable for principal={} subDomain={}: {}",
                    principalId, subDomainId, e.getMessage());
            return GroundingResult.unavailable(ref.id(), subDomainId);
        }
    }

    /**
     * Grounds EVERY extracted mention across ALL of its manifest interpretations (routing spec V2.1 /
     * Piece 2). Where {@link #ground} collapses the turn to a single strongest reference and one
     * verdict, this returns the full grounded dataset: a {@link GroundedMention} per mention, each
     * carrying every eligible sub-domain interpretation with its own {@link GroundStatus},
     * {@code denialReason}, span, provenance and interpretation id.
     *
     * <p><b>Interim disposition (V2.1 #1):</b> only the FOCAL mention's leading interpretation feeds
     * the existing single-{@link GroundingResult} terminal lattice (exposed as {@link
     * GroundedReferenceSet#focalVerdict()}). Non-focal mentions contribute NOTHING to disposition yet
     * - they exist for Piece 3 masking + relaxation. No request-global "deny on any interpretation".
     *
     * <p><b>Budgets:</b> {@code max_mentions} / {@code max_interpretations_per_mention} caps, a dedupe
     * key of {@code (coverage.resolve_url, resolve_type, reference)} so a repeated reference resolves
     * once, bounded concurrency over a virtual-thread executor, and a stage deadline after which
     * unfinished interpretations aggregate as {@code UNAVAILABLE} (fail-closed, deterministic).
     *
     * <p>Returns {@link GroundedReferenceSet#none()} (empty mention list, {@code focalVerdict = NONE})
     * when there is no groundable reference - {@code UNAVAILABLE} is a status on an interpretation, an
     * omission is not a status. RESOLVE is principal-agnostic; CHECK only ever sees a resolved id.
     */
    public GroundedReferenceSet groundMentions(EntityBag preExtracted, Principal principal,
                                               String tenantId, String callerToken) {
        if (preExtracted == null || preExtracted.mentions() == null
                || preExtracted.mentions().isEmpty()) {
            return GroundedReferenceSet.none();
        }
        String principalId = principal != null ? principal.id() : Principal.anonymous().id();

        // 1) Enumerate (mention -> interpretations), applying the mention + interpretation caps and
        //    collecting the de-duplicated set of coverage task keys.
        List<Mention> mentions = preExtracted.mentions().mentions();
        int mentionCap = Math.min(mentions.size(), Math.max(0, budget.maxMentions()));
        List<PlannedMention> planned = new ArrayList<>();
        Map<String, CoverageTask> tasks = new LinkedHashMap<>();
        for (int i = 0; i < mentionCap; i++) {
            Mention m = mentions.get(i);
            boolean nonCoverageScopedId = manifestStore.matchesNonCoverageScopedResolvableId(m.verbatimText());
            List<IdentifiedReference> interps =
                    manifestStore.interpretationsForReference(m.extractAs(), m.verbatimText());
            int interpCap = Math.min(interps.size(), Math.max(0, budget.maxInterpretationsPerMention()));
            List<IdentifiedReference> capped = interps.subList(0, interpCap);
            for (IdentifiedReference ref : capped) {
                String key = dedupeKey(ref);
                tasks.putIfAbsent(key, new CoverageTask(ref.id(),
                        ref.entityType().resolveType(), ref.coverage()));
            }
            planned.add(new PlannedMention(m, List.copyOf(capped), nonCoverageScopedId));
        }

        // 2) Run the de-duplicated coverage lattice with bounded concurrency + a stage deadline.
        Map<String, LatticeOutcome> outcomes = executeTasks(tasks, principal, principalId, tenantId, callerToken);

        // 3) Reassemble per-mention interpretations from the shared outcome map.
        List<GroundedMention> grounded = new ArrayList<>();
        for (PlannedMention pm : planned) {
            List<GroundedInterpretation> gis = new ArrayList<>();
            for (IdentifiedReference ref : pm.interpretations()) {
                LatticeOutcome o = outcomes.get(dedupeKey(ref));
                gis.add(toInterpretation(pm.mention(), ref, o));
            }
            grounded.add(new GroundedMention(pm.mention(), List.copyOf(gis), false,
                    pm.nonCoverageScopedIdPatternMatch()));
        }

        // 4) Pick the focal mention (V2.1 #3 ranking) and derive the single-lattice focal verdict.
        int focalIndex = focalIndex(grounded);
        if (focalIndex >= 0) {
            GroundedMention f = grounded.get(focalIndex);
            grounded.set(focalIndex, new GroundedMention(f.mention(), f.interpretations(), true,
                    f.nonCoverageScopedIdPatternMatch()));
        }
        GroundingResult focalVerdict = focalIndex < 0
                ? GroundingResult.none()
                : toFocalVerdict(grounded.get(focalIndex), principalId);
        return new GroundedReferenceSet(List.copyOf(grounded), focalVerdict);
    }

    private static String dedupeKey(IdentifiedReference ref) {
        String endpoint = ref.coverage() != null ? ref.coverage().resolveUrl() : "";
        return endpoint + "\u0001" + ref.entityType().resolveType() + "\u0001" + ref.id();
    }

    /**
     * Runs the de-duplicated RESOLVE->CHECK lattice for each unique coverage task with bounded
     * concurrency (a semaphore over the virtual-thread executor) and a single stage deadline. Any task
     * that does not finish by the deadline (or is interrupted) is aggregated as {@code UNAVAILABLE} -
     * fail-closed, and deterministic (the same key always maps to the same terminal outcome here).
     */
    private Map<String, LatticeOutcome> executeTasks(Map<String, CoverageTask> tasks, Principal principal,
                                                     String principalId, String tenantId, String callerToken) {
        if (tasks.isEmpty()) return Map.of();
        Semaphore gate = new Semaphore(Math.max(1, budget.concurrency()));
        Map<String, CompletableFuture<LatticeOutcome>> futures = new LinkedHashMap<>();
        for (Map.Entry<String, CoverageTask> e : tasks.entrySet()) {
            CoverageTask t = e.getValue();
            futures.put(e.getKey(), CompletableFuture.supplyAsync(() -> {
                try {
                    gate.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return LatticeOutcome.unavailable(t.reference());
                }
                try {
                    return runLattice(t, principalId, tenantId, callerToken);
                } finally {
                    gate.release();
                }
            }, groundingExecutor));
        }

        Map<String, LatticeOutcome> out = new LinkedHashMap<>();
        long deadlineNanos = System.nanoTime() + budget.stageDeadlineMillis() * 1_000_000L;
        for (Map.Entry<String, CompletableFuture<LatticeOutcome>> e : futures.entrySet()) {
            CoverageTask t = tasks.get(e.getKey());
            long remaining = deadlineNanos - System.nanoTime();
            LatticeOutcome o;
            try {
                o = remaining > 0
                        ? e.getValue().get(remaining, TimeUnit.NANOSECONDS)
                        : LatticeOutcome.unavailable(t.reference());
            } catch (TimeoutException te) {
                o = LatticeOutcome.unavailable(t.reference());
            } catch (Exception ex) {
                o = LatticeOutcome.unavailable(t.reference());
            }
            if (o.status() == GroundStatus.UNAVAILABLE) {
                e.getValue().cancel(true); // stop-work signal for anything past the deadline
            }
            out.put(e.getKey(), o);
        }
        return out;
    }

    /** The RESOLVE->CHECK lattice for a single interpretation (mirrors {@link #ground}'s inner lattice). */
    private LatticeOutcome runLattice(CoverageTask t, String principalId, String tenantId, String callerToken) {
        try {
            CoverageResolveResult rr = coverageClient.resolve(
                    t.reference(), t.resolveType(), tenantId, t.coverage(), callerToken);
            if (rr.resolved() && rr.id() != null) {
                CoverageCheckResult check = coverageClient.check(
                        principalId, tenantId, rr.id(), t.coverage(), callerToken);
                if (!check.allowed()) return LatticeOutcome.denied(rr.id(), check.reason());
                return LatticeOutcome.allowed(rr.id());
            }
            if (rr.isAmbiguous()) return LatticeOutcome.ambiguous();
            return LatticeOutcome.notFound();
        } catch (CoverageClient.CoverageUnavailableException e) {
            return LatticeOutcome.unavailable(t.reference());
        }
    }

    private GroundedInterpretation toInterpretation(Mention m, IdentifiedReference ref, LatticeOutcome o) {
        LatticeOutcome outcome = (o != null) ? o : LatticeOutcome.unavailable(ref.id());
        EntityType et = ref.entityType();
        String subDomainId = ref.subDomain() != null ? ref.subDomain().subDomainId() : null;
        GroundSourceKind sourceKind = matchesIdPattern(et, m.verbatimText())
                ? GroundSourceKind.ID_PATTERN : GroundSourceKind.EXTRACTED_REFERENCE;
        String canonicalId = switch (outcome.status()) {
            case RESOLVED_ALLOWED, DENIED -> outcome.resolvedId();
            case UNAVAILABLE -> ref.id();                 // carry the raw reference for the trace
            case AMBIGUOUS, NOT_FOUND -> null;
        };
        return new GroundedInterpretation(
                canonicalId, et.key(), subDomainId, dedupeKey(ref), outcome.status(),
                outcome.denialReason(), sourceKind, m.source(),
                String.valueOf(m.messageIndex()), m.messageIndex(), m.span(), et, ref.coverage());
    }

    private boolean matchesIdPattern(EntityType et, String text) {
        if (et == null || text == null) return false;
        var p = manifestStore.compiledIdPattern(et.idPattern());
        return p != null && p.matcher(text).find();
    }

    /**
     * Focal mention index (routing spec V2.1 #3): among mentions that carry at least one coverage
     * interpretation, an EXPLICIT mention outranks a carried ANAPHORA, and within a rank a later turn
     * (higher message index) wins; an exact tie keeps the earlier-recorded mention (the focal
     * reference is recorded first). Mirrors {@code MentionSet.compatScalar}. Returns {@code -1} when no
     * mention has a groundable interpretation.
     */
    private static int focalIndex(List<GroundedMention> grounded) {
        int best = -1;
        for (int i = 0; i < grounded.size(); i++) {
            GroundedMention gm = grounded.get(i);
            if (gm.interpretations().isEmpty()) continue;
            if (best < 0 || isBetterFocal(gm.mention(), grounded.get(best).mention())) best = i;
        }
        return best;
    }

    private static boolean isBetterFocal(Mention candidate, Mention current) {
        int rc = candidate.source() == MentionSource.EXPLICIT ? 1 : 0;
        int rk = current.source() == MentionSource.EXPLICIT ? 1 : 0;
        if (rc != rk) return rc > rk;
        return candidate.messageIndex() > current.messageIndex();
    }

    /** Maps the focal mention's LEADING (deterministic-first) interpretation onto the legacy lattice. */
    private static GroundingResult toFocalVerdict(GroundedMention focal, String principalId) {
        if (focal == null || focal.interpretations().isEmpty()) return GroundingResult.none();
        GroundedInterpretation gi = focal.interpretations().get(0);
        return switch (gi.status()) {
            case RESOLVED_ALLOWED -> GroundingResult.allowed(gi.canonicalId(), gi.entityType(),
                    gi.subDomainId(), gi.coverage());
            case DENIED -> GroundingResult.denied(gi.canonicalId(), gi.subDomainId(),
                    gi.denialReason(), principalId);
            case AMBIGUOUS -> GroundingResult.ambiguous(gi.subDomainId());
            case NOT_FOUND -> GroundingResult.notFound(gi.subDomainId());
            case UNAVAILABLE -> GroundingResult.unavailable(gi.canonicalId(), gi.subDomainId());
        };
    }

    // -- Internal grounding work-items (not part of the public contract) ----------------------------

    /** A mention plus its capped interpretation list and the V2.1 #4 non-coverage-scoped id flag. */
    private record PlannedMention(Mention mention, List<IdentifiedReference> interpretations,
                                  boolean nonCoverageScopedIdPatternMatch) {}

    /** One de-duplicated coverage RESOLVE->CHECK unit of work (its inputs, sans principal). */
    private record CoverageTask(String reference, String resolveType, DomainManifest.Coverage coverage) {}

    /** The terminal outcome of running one {@link CoverageTask} through the lattice. */
    private record LatticeOutcome(GroundStatus status, String resolvedId, String denialReason) {
        static LatticeOutcome allowed(String id)          { return new LatticeOutcome(GroundStatus.RESOLVED_ALLOWED, id, null); }
        static LatticeOutcome denied(String id, String r) { return new LatticeOutcome(GroundStatus.DENIED, id, r); }
        static LatticeOutcome ambiguous()                 { return new LatticeOutcome(GroundStatus.AMBIGUOUS, null, null); }
        static LatticeOutcome notFound()                  { return new LatticeOutcome(GroundStatus.NOT_FOUND, null, null); }
        static LatticeOutcome unavailable(String ref)     { return new LatticeOutcome(GroundStatus.UNAVAILABLE, ref, null); }
    }

    // -- Public multi-reference grounding contract (Piece 3 consumes this) --------------------------

    /**
     * Per-interpretation terminal status. The five values a coverage lattice can reach for ONE
     * interpretation of ONE mention. (Distinct from {@link Verdict}, which also has {@code NONE} for
     * "no reference at all" - an interpretation always corresponds to a real reference.)
     */
    public enum GroundStatus { RESOLVED_ALLOWED, DENIED, AMBIGUOUS, NOT_FOUND, UNAVAILABLE }

    /** How the reference was identified: a typed id matching a manifest {@code id_pattern}, or an LLM-extracted name. */
    public enum GroundSourceKind { ID_PATTERN, EXTRACTED_REFERENCE }

    /**
     * One fully-ground manifest interpretation of one mention.
     *
     * @param canonicalId      resolved id for {@code RESOLVED_ALLOWED}/{@code DENIED}; the raw
     *                         reference for {@code UNAVAILABLE} (for the trace); {@code null} otherwise.
     * @param entityKey        manifest {@code EntityType.key()} this interpretation resolves into.
     * @param subDomainId      owning sub-domain of this interpretation.
     * @param interpretationId stable dedupe/interpretation id - the coverage contract this grounded
     *                         against, encoded as {@code (resolve_url, resolve_type, reference)}.
     * @param status           the per-interpretation coverage verdict.
     * @param denialReason     machine-readable reason code on {@code DENIED}, else {@code null}.
     * @param sourceKind       typed-id vs extracted-name provenance.
     * @param mentionKind       {@code EXPLICIT} (latest turn) vs {@code ANAPHORA} (carried).
     * @param messageKey       stable string form of {@code messageIndex}.
     * @param messageIndex     index of the owning message in the client-sent window ({@code -1} if unknown).
     * @param span             gateway-derived {@code [start,end)} span, or {@code null} on an alignment miss.
     * @param entityType       the manifest entity type (carried so the focal memo/relaxation need not re-look-up).
     * @param coverage         the manifest coverage contract (carried for the same reason).
     */
    public record GroundedInterpretation(
            String canonicalId,
            String entityKey,
            String subDomainId,
            String interpretationId,
            GroundStatus status,
            String denialReason,
            GroundSourceKind sourceKind,
            MentionSource mentionKind,
            String messageKey,
            int messageIndex,
            MentionSpan span,
            EntityType entityType,
            DomainManifest.Coverage coverage) {

        public boolean isAllowed()   { return status == GroundStatus.RESOLVED_ALLOWED; }
        public boolean isDenied()    { return status == GroundStatus.DENIED; }
        /** DENIED-but-resolved and RESOLVED_ALLOWED both have a canonical resolution to mask (V2.1 minor). */
        public boolean isResolved()  { return canonicalId != null && (isAllowed() || isDenied()); }
    }

    /**
     * All grounded interpretations of ONE mention, plus whether it is the focal mention and whether
     * its verbatim text matched a resolvable-but-{@code resource_scoped:false} id_pattern (V2.1 #4 -
     * RECORDED here, acted on by Piece 3 relaxation, never here).
     */
    public record GroundedMention(
            Mention mention,
            List<GroundedInterpretation> interpretations,
            boolean focal,
            boolean nonCoverageScopedIdPatternMatch) {

        public GroundedMention {
            interpretations = interpretations == null ? List.of() : List.copyOf(interpretations);
        }
    }

    /**
     * The full multi-reference grounding result: every mention's grounded interpretations, plus the
     * single-lattice {@link #focalVerdict} the interim disposition drives from the FOCAL mention only
     * (V2.1 #1). Non-focal mentions carry no disposition weight yet - Piece 3 consumes them for
     * masking + relaxation.
     */
    public record GroundedReferenceSet(List<GroundedMention> mentions, GroundingResult focalVerdict) {

        public GroundedReferenceSet {
            mentions = mentions == null ? List.of() : List.copyOf(mentions);
            focalVerdict = focalVerdict == null ? GroundingResult.none() : focalVerdict;
        }

        public static GroundedReferenceSet none() {
            return new GroundedReferenceSet(List.of(), GroundingResult.none());
        }

        public boolean isEmpty() { return mentions.isEmpty(); }

        /** The focal mention (drives {@link #focalVerdict}), or {@code null} when none is groundable. */
        public GroundedMention focalMention() {
            for (GroundedMention gm : mentions) {
                if (gm.focal()) return gm;
            }
            return null;
        }

        /** Every grounded interpretation across all mentions, in mention-then-interpretation order. */
        public List<GroundedInterpretation> allInterpretations() {
            List<GroundedInterpretation> out = new ArrayList<>();
            for (GroundedMention gm : mentions) out.addAll(gm.interpretations());
            return out;
        }
    }

    /** The four-way lattice verdict, plus NONE (no reference) and UNAVAILABLE (fail-closed). */
    public enum Verdict { NONE, RESOLVED_ALLOWED, DENIED, AMBIGUOUS, NOT_FOUND, UNAVAILABLE }

    /**
     * The immutable grounding outcome. {@code resolvedId} carries the CHECK'd canonical id for
     * {@code RESOLVED_ALLOWED}/{@code DENIED} (and the raw reference id for {@code UNAVAILABLE}, for
     * the trace); {@code entityType}/{@code coverage} carry the memo an allowed verdict hands to the
     * coverage pipeline so it need not re-resolve.
     */
    public record GroundingResult(
            Verdict verdict,
            String resolvedId,
            EntityType entityType,
            String subDomainId,
            DomainManifest.Coverage coverage,
            String denialReason,
            String principalId) {

        public static GroundingResult none() {
            return new GroundingResult(Verdict.NONE, null, null, null, null, null, null);
        }

        public static GroundingResult allowed(String resolvedId, EntityType entityType,
                                              String subDomainId, DomainManifest.Coverage coverage) {
            return new GroundingResult(Verdict.RESOLVED_ALLOWED, resolvedId, entityType, subDomainId,
                    coverage, null, null);
        }

        public static GroundingResult denied(String resolvedId, String subDomainId,
                                             String denialReason, String principalId) {
            return new GroundingResult(Verdict.DENIED, resolvedId, null, subDomainId, null,
                    denialReason, principalId);
        }

        public static GroundingResult ambiguous(String subDomainId) {
            return new GroundingResult(Verdict.AMBIGUOUS, null, null, subDomainId, null, null, null);
        }

        public static GroundingResult notFound(String subDomainId) {
            return new GroundingResult(Verdict.NOT_FOUND, null, null, subDomainId, null, null, null);
        }

        public static GroundingResult unavailable(String referenceId, String subDomainId) {
            return new GroundingResult(Verdict.UNAVAILABLE, referenceId, null, subDomainId, null,
                    null, null);
        }

        public boolean isAllowed() { return verdict == Verdict.RESOLVED_ALLOWED; }
    }
}
