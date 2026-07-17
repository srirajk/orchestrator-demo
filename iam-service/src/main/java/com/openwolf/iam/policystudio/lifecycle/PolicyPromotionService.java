package com.openwolf.iam.policystudio.lifecycle;

import com.openwolf.iam.policystudio.ConsequenceApprovalService;
import com.openwolf.iam.tenancy.ActiveTenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one promotion machine for policy bundles (Axiom Story C5) — the SAME authorized compare-and-set
 * B4 uses to make a tenant visible, now advancing a tenant's active policy version from a reviewed OLD
 * bundle to a certified candidate. It is deliberately the same spine as the onboarding manifest
 * promotion (one machine, two artifact types): verify the approval signature over the exact C4
 * {@code consequenceReviewHash}, stage the candidate alongside the live version, load + probe every
 * invariant with {@code policyVersion = candidate bundleId}, then compare-and-set the live pointer on
 * B4's {@link ActiveTenantDirectory}. A failed signature, a tampered candidate, a stale CAS, or a failed
 * probe leaves the candidate INACTIVE.
 *
 * <p><b>Idempotent (C5.3, onboarding §10):</b> a lost-response retry with the same idempotency key finds
 * the existing PROMOTED receipt and returns it — it never drives a second CAS.
 *
 * <p><b>Rollback (§11):</b> not a database reversal — {@link #rollback} is a NEW authorized promotion to
 * a previously certified bundle. Old bundles remain immutable and available for the retention period.
 *
 * <p>Authoring-plane only: {@code PolicyAuthoringBoundaryArchTest} proves no runtime-enforcement code can
 * reach this service.
 */
@Service
public class PolicyPromotionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyPromotionService.class);

    private final ActiveTenantDirectory directory;
    private final ConsequenceApprovalService approvals;
    private final PromotionRepository promotions;
    private final PolicyBundleRepository bundles;
    private final ApprovalRepository approvalStore;
    private final BundleCanonicalizer canonicalizer;
    private final GitCommitResolver gitResolver;
    private final CandidateProbe probe;

    public PolicyPromotionService(ActiveTenantDirectory directory,
                                  ConsequenceApprovalService approvals,
                                  PromotionRepository promotions,
                                  PolicyBundleRepository bundles,
                                  ApprovalRepository approvalStore,
                                  BundleCanonicalizer canonicalizer,
                                  GitCommitResolver gitResolver,
                                  CandidateProbe probe) {
        this.directory = directory;
        this.approvals = approvals;
        this.promotions = promotions;
        this.bundles = bundles;
        this.approvalStore = approvalStore;
        this.canonicalizer = canonicalizer;
        this.gitResolver = gitResolver;
        this.probe = probe;
    }

    /**
     * Promote a candidate bundle. Idempotent on the request's idempotency key.
     *
     * @throws UnauthorizedPromotionException if the approval does not authorize the exact candidate/review
     * @throws BundleTamperException          if the candidate fails its content-addressed integrity check
     * @throws StalePromotionException        if the active version drifted from the reviewed baseline (CAS)
     */
    @Transactional
    public PromotionReceipt promote(PromotionRequest request) {
        String tenantId = request.tenantId();
        String candidateId = request.candidate().bundleId();

        // ── Idempotency: a prior PROMOTED run replays; never a second CAS. ──
        PromotionRecord op = promotions.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
        if (op != null && op.getStatus() == PromotionRecord.Status.PROMOTED) {
            log.info("Promotion (key {}) already PROMOTED {} → {} — idempotent replay",
                    request.idempotencyKey(), op.getFromBundleId(), op.getToBundleId());
            return replay(op);
        }
        if (op == null) {
            op = new PromotionRecord(request.idempotencyKey(), tenantId,
                    request.reviewedCurrentBundleId(), candidateId,
                    request.review().consequenceReviewHash(), request.approval().approverId(), request.kind());
            op = promotions.save(op);
        }

        try {
            // ── 1. Authorize: signature over the EXACT C4 review hash + the exact candidate. ──
            if (!request.approval().candidateBundleId().equals(candidateId)) {
                throw new UnauthorizedPromotionException(
                        "approval authorizes candidate '" + request.approval().candidateBundleId()
                                + "' but the promotion candidate is '" + candidateId + "'");
            }
            if (!approvals.authorizesPromotion(request.approval(), request.review())) {
                throw new UnauthorizedPromotionException(
                        "approval does not authorize this review (bad signature, non-APPROVE decision, or "
                                + "hash/tuple mismatch) — refusing to promote candidate '" + candidateId + "'");
            }

            // ── 2. The candidate must be certified over the exact matrix the approval covered. ──
            if (!request.candidate().testMetadata().fixtureSetHash().equals(request.review().fixtureSetHash())) {
                throw new UnauthorizedPromotionException(
                        "candidate bundle was certified over fixture set '"
                                + request.candidate().testMetadata().fixtureSetHash()
                                + "' but the approved review covered '" + request.review().fixtureSetHash() + "'");
            }

            // ── 3. Content-addressed integrity — a tampered candidate is rejected. ──
            request.candidate().verifyIntegrity(canonicalizer);

            // ── 4. Stage alongside live + load + probe every invariant at policyVersion = candidate id. ──
            probe.verify(request.candidate());

            // ── 5. Compare-and-set the live pointer from the reviewed OLD id to the candidate. ──
            long directoryVersion;
            try {
                directoryVersion = directory.compareAndActivate(
                        tenantId, request.reviewedCurrentBundleId(), candidateId);
            } catch (IllegalStateException stale) {
                throw new StalePromotionException(stale.getMessage());
            }

            // ── 6. Persist the immutable bundle record + the signed approval; mark PROMOTED. ──
            if (!bundles.existsById(candidateId)) {
                bundles.save(new PolicyBundleRecord(request.candidate(), gitResolver.currentCommit()));
            }
            approvalStore.save(new ApprovalRecordEntity(request.approval()));
            op.markPromoted(directoryVersion);
            promotions.save(op);
            log.info("Promoted tenant '{}' {} → {} (kind={}, directory v{})",
                    tenantId, request.reviewedCurrentBundleId(), candidateId, request.kind(), directoryVersion);
            return new PromotionReceipt(op.getId(), tenantId, request.reviewedCurrentBundleId(),
                    candidateId, directoryVersion, request.kind(), false);

        } catch (RuntimeException e) {
            op.markFailed(e.toString());
            promotions.save(op);
            log.warn("FAIL-CLOSED: promotion of candidate '{}' for tenant '{}' failed — candidate stays "
                    + "INACTIVE: {}", candidateId, tenantId, e.toString());
            throw e;
        }
    }

    /**
     * Roll back to a previously certified bundle (§11) — a NEW authorized promotion, never a delete or a
     * DB reversal. The {@code priorBundle} must still exist in the immutable store; the caller supplies a
     * fresh approval over a rollback review whose current is the now-active bundle and whose candidate is
     * the prior bundle.
     */
    @Transactional
    public PromotionReceipt rollback(PromotionRequest rollbackRequest) {
        if (!bundles.existsById(rollbackRequest.candidate().bundleId())) {
            throw new UnauthorizedPromotionException(
                    "rollback target bundle '" + rollbackRequest.candidate().bundleId()
                            + "' is not in the immutable store — rollback restores a previously certified bundle");
        }
        return promote(new PromotionRequest(
                rollbackRequest.candidate(), rollbackRequest.review(), rollbackRequest.approval(),
                rollbackRequest.idempotencyKey(), PromotionRecord.Kind.ROLLBACK));
    }

    private PromotionReceipt replay(PromotionRecord op) {
        long v = op.getDirectoryVersion() == null ? 0L : op.getDirectoryVersion();
        return new PromotionReceipt(op.getId(), op.getTenantId(), op.getFromBundleId(),
                op.getToBundleId(), v, op.getKind(), true);
    }
}
