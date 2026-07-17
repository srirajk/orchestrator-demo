package com.openwolf.iam.policystudio.lifecycle;

import com.openwolf.iam.policystudio.CanonicalPolicyWriter;
import com.openwolf.iam.policystudio.ConsequenceApprovalRecord;
import com.openwolf.iam.policystudio.ConsequenceDiffService;
import com.openwolf.iam.policystudio.ConsequenceProseModelClient;
import com.openwolf.iam.policystudio.ConsequenceReview;
import com.openwolf.iam.policystudio.GroundedStudioReviewService;
import com.openwolf.iam.policystudio.ManifestBackedStudioGroundingProvider;
import com.openwolf.iam.policystudio.PolicyYamlParser;
import com.openwolf.iam.policystudio.ProductionPdpDecisionSource;
import com.openwolf.iam.policystudio.CerbosBatchDecisionSource;
import com.openwolf.iam.policystudio.api.StudioSessionStore;
import com.openwolf.iam.tenancy.ActiveTenantDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3, Bug A — a SECOND promotion reviews against the tenant's ACTUAL active bundle, not a synthetic
 * base-only snapshot. Before this fix, grounding always returned a base-only {@code current}: once a
 * tenant already had a promoted version, the second review's baseline was wrong (bad consequences) and
 * its compare-and-set from the base id failed against the real active id.
 *
 * <p>End to end against a REAL pinned Cerbos: provision → promote bundle A → assemble the review for a
 * candidate B and assert its {@code current} is A (its content-addressed id, never the base baseline) →
 * promote B and assert the A→B compare-and-set succeeds and the live pointer advances.
 */
class SecondPromotionReviewsAgainstActiveBundleIT {

    private static final String TENANT = C5LifecycleFixtures.TENANT; // "acme"

    @Test
    void secondPromotionReviewsAgainstTheRealActiveBundleAndCasSucceeds() {
        CanonicalPolicyWriter writer = new CanonicalPolicyWriter();
        PolicyYamlParser parser = new PolicyYamlParser();
        ObjectMapper mapper = new ObjectMapper();

        // Shared control-plane state: ONE directory + ONE immutable bundle store, used by BOTH the grounding
        // provider (reads the active bundle) and the promotion service (writes it) — the real wiring.
        ActiveTenantDirectory directory = C5LifecycleFixtures.directory();
        PolicyBundleRepository bundleRepo = C5LifecycleFixtures.bundleRepo();

        // CerbosBatchDecisionSource has no ".."-fallback, so resolve the base dir to whatever exists from
        // the module cwd (iam-service) — repo root's infra/cerbos/policies.
        String baseDir = infraBaseDir();
        CerbosBatchDecisionSource cerbos = new CerbosBatchDecisionSource(
                "ghcr.io/cerbos/cerbos:0.53.0", 120, "0.53.0", baseDir, writer);
        requireRealPdp(cerbos.isAvailable());
        ProductionPdpDecisionSource pdp = new ProductionPdpDecisionSource(cerbos);

        // Grounding derives vocabulary/ceiling/matrix from the real registry manifests, and now loads the
        // tenant's ACTUAL active bundle as `current` from the shared directory + bundle store.
        ManifestBackedStudioGroundingProvider grounding =
                new ManifestBackedStudioGroundingProvider(mapper, writer, parser, directory, bundleRepo,
                        "registry", baseDir, "infra/cerbos/tenants", "default");

        StudioSessionStore store = new StudioSessionStore();
        GroundedStudioReviewService reviews = new GroundedStudioReviewService(
                grounding, parser, writer, new ConsequenceDiffService(), pdp, noProse(),
                new BundleCanonicalizer(), store, new StudioBaselineActivationService(directory),
                new SelfContainedBundleAssembler(baseDir));

        PromotionRepository promotions = C5LifecycleFixtures.promotionRepo();
        PolicyPromotionService promotion = C5LifecycleFixtures.promotionService(
                directory, promotions, bundleRepo, C5LifecycleFixtures.approvalRepo(),
                C5LifecycleFixtures.passingProbe(), "commit-s3");

        // ── 1. Provision (tenant is on the inherited base) → promote bundle A. ──
        String yamlA = C5LifecycleFixtures.currentBody();
        ConsequenceReview reviewA = reviews.assembleAndReview(TENANT, "author-alice", "agent", yamlA);
        PolicyBundle bundleA = reviews.assembleCandidateBundle(TENANT, "agent", yamlA, null);
        assertThat(bundleA.bundleId())
                .as("the candidate bundle id must match the review it was diffed for")
                .isEqualTo(reviewA.candidateBundleId());

        ConsequenceApprovalRecord approvalA = C5LifecycleFixtures.approve(reviewA, "approver-bob");
        promotion.promote(new PromotionRequest(bundleA, reviewA, approvalA, "key-A", PromotionRecord.Kind.PROMOTION));
        assertThat(directory.find(TENANT))
                .as("after promoting A the live pointer is A")
                .hasValue(bundleA.bundleId());

        // ── 2. Review candidate B. Its `current` MUST be the real active bundle A, not the base baseline. ──
        String yamlB = C5LifecycleFixtures.candidateBody();
        ConsequenceReview reviewB = reviews.assembleAndReview(TENANT, "author-alice", "agent", yamlB);

        assertThat(reviewB.currentBundleId())
                .as("Bug A: B's review must use the ACTUAL active bundle A as current")
                .isEqualTo(bundleA.bundleId());
        assertThat(reviewB.currentBundleId())
                .as("B's current must NOT be A's base baseline (the review is against A, not base)")
                .isNotEqualTo(reviewA.currentBundleId());

        PolicyBundle bundleB = reviews.assembleCandidateBundle(TENANT, "agent", yamlB, null);
        assertThat(reviewB.candidateBundleId()).isEqualTo(bundleB.bundleId());
        assertThat(bundleB.bundleId()).isNotEqualTo(bundleA.bundleId());

        // The consequence diff was computed A→B: current bundle id is A, candidate id is B.
        assertThat(reviewB.currentBundleId()).isEqualTo(bundleA.bundleId());
        assertThat(reviewB.candidateBundleId()).isEqualTo(bundleB.bundleId());

        // ── 3. Promote B. The A→B compare-and-set must SUCCEED (baseline A is live). ──
        ConsequenceApprovalRecord approvalB = C5LifecycleFixtures.approve(reviewB, "approver-bob");
        promotion.promote(new PromotionRequest(bundleB, reviewB, approvalB, "key-B", PromotionRecord.Kind.PROMOTION));

        assertThat(directory.find(TENANT))
                .as("the A→B promotion CAS succeeds and the live pointer advances to B")
                .hasValue(bundleB.bundleId());
    }

    /** The repo's base policy dir, resolved from the iam-service module cwd (with a {@code ..} fallback). */
    private static String infraBaseDir() {
        java.nio.file.Path direct = java.nio.file.Path.of("infra/cerbos/policies");
        return java.nio.file.Files.isDirectory(direct)
                ? direct.toString()
                : java.nio.file.Path.of("..").resolve("infra/cerbos/policies").normalize().toString();
    }

    /** A no-op prose provider — the LLM display seam is absent, proving it is not in the truth path. */
    private static ObjectProvider<ConsequenceProseModelClient> noProse() {
        return new ObjectProvider<>() {
            @Override public ConsequenceProseModelClient getObject(Object... args) { return null; }
            @Override public ConsequenceProseModelClient getObject() { return null; }
            @Override public ConsequenceProseModelClient getIfAvailable() { return null; }
            @Override public ConsequenceProseModelClient getIfUnique() { return null; }
            @Override public Iterator<ConsequenceProseModelClient> iterator() { return List.<ConsequenceProseModelClient>of().iterator(); }
        };
    }

    private static void requireRealPdp(boolean available) {
        boolean requiredInCi = Boolean.parseBoolean(
                System.getenv().getOrDefault("CONDUIT_DOCKER_REQUIRED", "false"));
        if (requiredInCi) {
            assertThat(available)
                    .as("CONDUIT_DOCKER_REQUIRED=true but pinned Cerbos is unavailable; this proof must run")
                    .isTrue();
        } else {
            Assumptions.assumeTrue(available, "pinned Cerbos unavailable — skipping the second-promotion proof");
        }
    }
}
