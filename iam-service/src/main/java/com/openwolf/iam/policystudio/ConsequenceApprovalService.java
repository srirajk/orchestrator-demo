package com.openwolf.iam.policystudio;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

/**
 * Binds a human approval to the exact machine consequences of a {@link ConsequenceReview} (Axiom Story
 * C4.6) and enforces the no-stale-diff rule (C4.5).
 *
 * <p>{@link #approve} refuses to sign a review that is stale (the tenant's active bundle drifted from
 * the diff's captured current bundle) or tampered (its hash no longer matches its truth fields). A
 * produced {@link ConsequenceApprovalRecord} signs the {@code consequenceReviewHash}, so
 * {@link #authorizesPromotion} accepts only the exact current/candidate/review-hash tuple the human
 * saw. Changing any truth input (candidate, current, fixture set, one delta cell) changes the review
 * hash and invalidates the approval; changing only display wording does not.
 */
@Service
public class ConsequenceApprovalService {

    private final ConsequenceReviewSigner signer;
    private final TenantActiveBundleRegistry activeBundles;

    public ConsequenceApprovalService(ConsequenceReviewSigner signer, TenantActiveBundleRegistry activeBundles) {
        this.signer = signer;
        this.activeBundles = activeBundles;
    }

    /**
     * Sign an approval for a review. Fails closed if the review is stale or tampered.
     *
     * @throws StaleReviewException if the tenant's active bundle no longer equals the review's captured
     *                              current bundle (C4.5) — a fresh diff is required first
     * @throws IllegalStateException if the review's hash does not match its truth fields (tamper)
     */
    public ConsequenceApprovalRecord approve(
            ConsequenceReview review, String approverId, Set<String> approverRoles, ApprovalDecision decision) {

        // (C4.5) staleness: the diff must have been computed against the tenant's CURRENT active bundle.
        String active = activeBundles.activeBundleId(review.tenantId());
        if (active == null || !active.equals(review.currentBundleId())) {
            throw new StaleReviewException(
                    "consequence review is stale: it was computed against current bundle '"
                            + review.currentBundleId() + "' but the tenant's active bundle is now '"
                            + active + "'. Recompute the diff against the new current bundle before approving.");
        }

        // Integrity: the review hash must actually be the hash of its own truth fields.
        String recomputed = review.recomputeHash();
        if (!recomputed.equals(review.consequenceReviewHash())) {
            throw new IllegalStateException(
                    "consequence review hash does not match its truth fields (expected " + recomputed
                            + ", was " + review.consequenceReviewHash() + ") — refusing to sign a tampered review");
        }

        ConsequenceApprovalRecord unsigned = new ConsequenceApprovalRecord(
                review.tenantId(),
                review.currentBundleId(),
                review.candidateBundleId(),
                review.fixtureSetHash(),
                review.consequenceReviewHash(),
                review.overPermissionAlarm(),
                approverId,
                approverRoles,
                decision,
                /* signature */ "",
                Instant.now());
        String signature = signer.sign(unsigned.signingPayload());
        return new ConsequenceApprovalRecord(
                unsigned.tenantId(), unsigned.currentBundleId(), unsigned.candidateBundleId(),
                unsigned.fixtureSetHash(), unsigned.consequenceReviewHash(), unsigned.overPermissionAlarm(),
                unsigned.approverId(), unsigned.approverRoles(), unsigned.decision(),
                signature, unsigned.signedAt());
    }

    /**
     * Whether a stored approval record authorizes promoting the given review's candidate. Requires an
     * APPROVE decision, an intact signature, and an EXACT match on every binding field (tenant, both
     * bundle ids, fixture set, and the review hash). Any truth change breaks this; display-wording
     * changes do not (they are not inputs to the hash).
     */
    public boolean authorizesPromotion(ConsequenceApprovalRecord record, ConsequenceReview review) {
        if (record.decision() != ApprovalDecision.APPROVE) {
            return false;
        }
        boolean tupleMatches = record.tenantId().equals(review.tenantId())
                && record.currentBundleId().equals(review.currentBundleId())
                && record.candidateBundleId().equals(review.candidateBundleId())
                && record.fixtureSetHash().equals(review.fixtureSetHash())
                && record.consequenceReviewHash().equals(review.consequenceReviewHash());
        if (!tupleMatches) {
            return false;
        }
        return signer.verify(record.signingPayload(), record.signature());
    }
}
