package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.DomainManifestStore.IdentifiedReference;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.synthesis.input.EntityBag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Stage-1 reference grounding — the deterministic, PRE-ROUTING step that resolves a human reference
 * (a typed id like {@code REL-00188}, or an LLM-extracted name like "the Okafor account") to a
 * canonical id and runs the coverage CHECK on it, before the semantic router ever sees the query.
 *
 * <p><b>Why this exists (the class fix).</b> Coverage DENY and the CLARIFY decision are deterministic
 * and their inputs (the extracted entity bag, the manifest {@code required_context}, the coverage
 * service) do not depend on routing at all. When they were computed only <i>inside</i> the coverage
 * pipeline — which is gated behind the routing-confidence abstain — any phrasing that depressed the
 * embedding score (a possessive apostrophe, injection prose, a bare facet word) silently converted a
 * would-be DENY into an unhelpful "no service." Grounding moves the RESOLVE+CHECK to before routing,
 * so the semantic score can only ever choose between safe outcomes and never sits between the user
 * and a denial.
 *
 * <p><b>The four-way verdict lattice</b> (see {@link Verdict}):
 * <ul>
 *   <li>{@code RESOLVED_ALLOWED} — resolved uniquely and the coverage CHECK allowed: the caller
 *       memoizes the {@link #resolvedId()} and routes with {@code entityKnown=true}.</li>
 *   <li>{@code DENIED} — resolved uniquely but the coverage CHECK denied: a terminal DENY named with
 *       the owning sub-domain's copy, without ever routing.</li>
 *   <li>{@code AMBIGUOUS} — the reference matched several candidates: fall through to the existing
 *       {@code discover ∩ candidates} clarify.</li>
 *   <li>{@code NOT_FOUND} — the reference resolved to nothing: demote to content (it stays in the
 *       agent prompt; it never fills a coverage slot, never a CHECK, never a denial).</li>
 *   <li>{@code UNAVAILABLE} — the coverage service was unreachable: FAIL CLOSED (deny access).</li>
 *   <li>{@code NONE} — no groundable reference in this turn: the normal pipeline runs unchanged.</li>
 * </ul>
 *
 * <p><b>Invariants.</b> RESOLVE is principal-agnostic (scoped by tenant, never the caller's book);
 * the coverage CHECK is the only gate and only ever receives ids that RESOLVED; the service fails
 * closed on {@link CoverageClient.CoverageUnavailableException}. World B: every symbol is
 * manifest-derived — no domain name, entity-type literal, id prefix, or user-facing copy here.
 */
@Service
public class ReferenceGroundingService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceGroundingService.class);

    private final DomainManifestStore manifestStore;
    private final CoverageClient coverageClient;

    public ReferenceGroundingService(DomainManifestStore manifestStore, CoverageClient coverageClient) {
        this.manifestStore = manifestStore;
        this.coverageClient = coverageClient;
    }

    /**
     * Grounds the turn's single strongest reference. A typed id in {@code latestPrompt} (most
     * specific) is preferred over an LLM-extracted name in {@code preExtracted}; when neither is
     * present the verdict is {@link Verdict#NONE}. Both sources yield the same
     * {@link IdentifiedReference} shape, so a single RESOLVE→CHECK lattice serves both.
     *
     * @param preExtracted the entity bag from the intent+entity extraction (may be null)
     * @param latestPrompt the latest user message (source of a typed-id literal)
     * @param principal    the verified principal — used only for the coverage CHECK, never for RESOLVE
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
                // CHECK only ever receives a RESOLVED id — never the raw human reference.
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
            // NOT_FOUND — demote to content, never a denial (fixes the wrong-type-entity false deny).
            return GroundingResult.notFound(subDomainId);
        } catch (CoverageClient.CoverageUnavailableException e) {
            log.error("Reference grounding coverage unavailable for principal={} subDomain={}: {}",
                    principalId, subDomainId, e.getMessage());
            return GroundingResult.unavailable(ref.id(), subDomainId);
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
