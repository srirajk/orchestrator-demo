package com.openwolf.iam.policystudio.api;

import com.openwolf.iam.policystudio.BundleSnapshot;
import com.openwolf.iam.policystudio.ConsequenceDiffService;
import com.openwolf.iam.policystudio.ConsequenceFixtureMatrix;
import com.openwolf.iam.policystudio.ConsequenceReview;
import com.openwolf.iam.policystudio.ManifestVocabulary;
import com.openwolf.iam.policystudio.ProductionPdpDecisionSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The C4 consequence-diff surface — the adoption unlock: a non-engineer approves business
 * <em>consequences</em>, not YAML. Thin over {@link ConsequenceDiffService}, which runs real PDP
 * evaluations of the same sampled fixture matrix against the current and candidate immutable snapshots
 * and diffs the two answer sets. <b>No LLM touches the truth</b> — the returned {@link ConsequenceReview}
 * (deltas, wideningDeltas, overPermissionAlarm, principalsGainingAccess, consequenceReviewHash, the
 * sampled-not-formal disclosure) is computed entirely from PDP decisions.
 *
 * <p>The review is cached (keyed by its {@code consequenceReviewHash}, tagged with the caller's tenant)
 * so the SPA can re-fetch it and later echo it into the promotion request.
 *
 * <p><b>Tenant scope</b> is the principal's {@code tenant_id} claim. Production truth comes only from
 * {@link ProductionPdpDecisionSource}, which evaluates both snapshots with pinned Cerbos and fails
 * closed when that runtime is unavailable. There is no local-evaluator fallback. Requires a studio role.
 *
 * <p><b>Grounding note (documented gap):</b> the two {@link BundleSnapshot}s, the sampled
 * {@link ConsequenceFixtureMatrix}, and the {@link ManifestVocabulary} are supplied in the request body.
 * A future entry that assembles them from two draft ids + the tenant's C3 expectation set is the
 * intended follow-up.
 */
@RestController
@RequestMapping("/admin/studio")
@PreAuthorize("hasAnyRole('policy_author','policy_approver','platform_admin')")
public class StudioReviewController {

    private final ConsequenceDiffService diff;
    private final ProductionPdpDecisionSource pdpSource;
    private final StudioSessionStore store;

    public StudioReviewController(ConsequenceDiffService diff, ProductionPdpDecisionSource pdpSource,
                                  StudioSessionStore store) {
        this.diff = diff;
        this.pdpSource = pdpSource;
        this.store = store;
    }

    /** The consequence-diff request: the two immutable snapshots, the sampled matrix, and the vocabulary. */
    public record ReviewPayload(BundleSnapshot current, BundleSnapshot candidate,
                                ConsequenceFixtureMatrix matrix, ManifestVocabulary vocabulary) {}

    /** {@code POST /admin/studio/reviews} — compute the consequence review from real PDP decisions. */
    @PostMapping("/reviews")
    public ResponseEntity<ConsequenceReview> compute(@RequestBody ReviewPayload payload, Authentication auth) {
        if (payload == null || payload.current() == null || payload.candidate() == null
                || payload.matrix() == null || payload.vocabulary() == null) {
            throw new IllegalArgumentException("current, candidate, matrix and vocabulary are required");
        }
        String author = StudioPrincipal.actor(auth);
        String tenant = StudioPrincipal.tenant(auth);
        ConsequenceReview review = diff.computeReview(
                tenant, payload.vocabulary(), payload.current(), payload.candidate(), payload.matrix(), pdpSource);
        // Record the VERIFIED author (drafter) alongside the review so promotion can enforce
        // author≠approver against a trusted identity, never a caller-supplied field.
        store.putReview(tenant, author, review);
        return ResponseEntity.ok(review);
    }

    /** {@code GET /admin/studio/reviews/{id}} — re-fetch a cached review (404 if absent / another tenant's). */
    @GetMapping("/reviews/{reviewId}")
    public ResponseEntity<ConsequenceReview> get(@PathVariable String reviewId, Authentication auth) {
        String tenant = StudioPrincipal.tenant(auth);
        return store.getReview(tenant, reviewId)
                .map(r -> ResponseEntity.ok(r.review()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
