package com.openwolf.iam.policystudio.api;

import com.openwolf.iam.policystudio.ConsequenceReview;
import com.openwolf.iam.policystudio.ReviewAuthorRegistry;
import com.openwolf.iam.policystudio.breakglass.BreakGlassArtifact;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small, in-memory session cache that carries the heavy studio value objects across the two-step
 * REST flows (compute → re-fetch → approve/promote), keyed by id and always tagged with the owning
 * tenant so a re-fetch from another tenant resolves as not-found. It holds NO domain knowledge and is
 * not a system of record — the durable stores (bundle/promotion/approval repositories, the active
 * directory) remain the source of truth. It exists only so the SPA exchanges ids + decisions instead
 * of reconstructing crypto-bound graphs by hand.
 *
 * <p><b>Honest note:</b> this is a single-node cache (a demo/control-plane convenience). A multi-node
 * or restart-durable studio would persist reviews and break-glass artifacts; that is a deliberate,
 * documented follow-up, not silent behaviour.
 */
@Component
public class StudioSessionStore implements ReviewAuthorRegistry {

    /**
     * A stored consequence review, tagged with the tenant that computed it AND the VERIFIED identity of
     * the principal who computed it (the drafter/author). The author is captured from the authenticated
     * {@code sub} at compute time — never a request field — so the promotion gate can enforce
     * author≠approver against a trusted value rather than caller-supplied text.
     */
    public record StoredReview(String reviewId, String tenantId, String authorId,
                               ConsequenceReview review, Instant storedAt) {}

    /** A stored break-glass artifact awaiting two-person approval, tagged with tenant + requester. */
    public record PendingGrant(String grantId, String tenantId, String requestedBy,
                               BreakGlassArtifact artifact, boolean issued, Instant storedAt) {}

    private final Map<String, StoredReview> reviews = new ConcurrentHashMap<>();
    private final Map<String, PendingGrant> grants = new ConcurrentHashMap<>();

    // ── Consequence reviews (C4) ──

    public StoredReview putReview(String tenantId, String authorId, ConsequenceReview review) {
        String id = review.consequenceReviewHash(); // already content-addressed over the truth fields
        StoredReview stored = new StoredReview(id, tenantId, authorId, review, Instant.now());
        reviews.put(id, stored);
        return stored;
    }

    /** Fetch a review by id, but only if it belongs to the caller's tenant (else empty ⇒ 404). */
    public Optional<StoredReview> getReview(String tenantId, String reviewId) {
        return Optional.ofNullable(reviews.get(reviewId))
                .filter(r -> r.tenantId().equals(tenantId));
    }

    /**
     * (H3) The VERIFIED author of a review, keyed by (tenant, {@code consequenceReviewHash}) — the review
     * id IS the hash. Feeds {@code ConsequenceApprovalService}'s author≠approver gate from a trusted,
     * server-recorded value the approver cannot forge.
     */
    @Override
    public Optional<String> authorFor(String tenantId, String consequenceReviewHash) {
        return getReview(tenantId, consequenceReviewHash).map(StoredReview::authorId);
    }

    // ── Break-glass artifacts (C6) ──

    public PendingGrant putGrant(String tenantId, String requestedBy, BreakGlassArtifact artifact) {
        String id = "bg-" + Long.toHexString(System.nanoTime()) + "-" + Integer.toHexString(artifact.hashCode());
        PendingGrant pending = new PendingGrant(id, tenantId, requestedBy, artifact, false, Instant.now());
        grants.put(id, pending);
        return pending;
    }

    public Optional<PendingGrant> getGrant(String tenantId, String grantId) {
        return Optional.ofNullable(grants.get(grantId))
                .filter(g -> g.tenantId().equals(tenantId));
    }

    public void markIssued(PendingGrant grant) {
        grants.put(grant.grantId(), new PendingGrant(grant.grantId(), grant.tenantId(), grant.requestedBy(),
                grant.artifact(), true, grant.storedAt()));
    }

    /** Active (issued, unexpired) grants for a tenant — the C6 list surface. */
    public List<PendingGrant> activeGrants(String tenantId) {
        Instant now = Instant.now();
        return grants.values().stream()
                .filter(g -> g.tenantId().equals(tenantId))
                .filter(PendingGrant::issued)
                .filter(g -> g.artifact().grant().expiresAt().isAfter(now))
                .sorted((a, b) -> b.storedAt().compareTo(a.storedAt()))
                .toList();
    }
}
