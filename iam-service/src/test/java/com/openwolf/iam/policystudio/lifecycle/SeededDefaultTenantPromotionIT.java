package com.openwolf.iam.policystudio.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openwolf.iam.policystudio.ApprovalDecision;
import com.openwolf.iam.policystudio.CanonicalPolicyWriter;
import com.openwolf.iam.policystudio.CerbosBatchDecisionSource;
import com.openwolf.iam.policystudio.CerbosCompileGate;
import com.openwolf.iam.policystudio.ConsequenceApprovalRecord;
import com.openwolf.iam.policystudio.ConsequenceApprovalService;
import com.openwolf.iam.policystudio.ConsequenceDiffService;
import com.openwolf.iam.policystudio.ConsequenceProseModelClient;
import com.openwolf.iam.policystudio.ConsequenceReview;
import com.openwolf.iam.policystudio.ConsequenceReviewSigner;
import com.openwolf.iam.policystudio.GeneratedPolicyValidator;
import com.openwolf.iam.policystudio.GroundedStudioReviewService;
import com.openwolf.iam.policystudio.InMemoryTenantActiveBundleRegistry;
import com.openwolf.iam.policystudio.ManifestBackedStudioGroundingProvider;
import com.openwolf.iam.policystudio.PolicyYamlParser;
import com.openwolf.iam.policystudio.ProductionPdpDecisionSource;
import com.openwolf.iam.policystudio.api.StudioSessionStore;
import com.openwolf.iam.tenancy.ActiveTenantDirectory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Regression for the live default-tenant promotion failure caused by two agent@default definitions. */
class SeededDefaultTenantPromotionIT {

    private static final String TENANT = "default";

    @Test
    void reviewedSeededChildReplacementCompilesAndPromotesAsOneTenantWideSnapshot(@TempDir Path work)
            throws Exception {
        Path base = repoPath("infra/cerbos/policies");
        Path registry = repoPath("registry");
        Path tenantDeployments = repoPath("infra/cerbos/tenants");
        CanonicalPolicyWriter writer = new CanonicalPolicyWriter();
        PolicyYamlParser parser = new PolicyYamlParser();
        BundleCanonicalizer canonicalizer = new BundleCanonicalizer();
        CerbosCompileGate compileGate = new CerbosCompileGate("ghcr.io/cerbos/cerbos:0.53.0", 120);
        CerbosBatchDecisionSource cerbos = new CerbosBatchDecisionSource(
                "ghcr.io/cerbos/cerbos:0.53.0", 120, "0.53.0", base.toString(), writer);
        requireRealPdp(compileGate.isAvailable() && cerbos.isAvailable());

        ActiveTenantDirectory directory = C5LifecycleFixtures.directory();
        PolicyBundleRepository bundleRepo = C5LifecycleFixtures.bundleRepo();
        ManifestBackedStudioGroundingProvider grounding = new ManifestBackedStudioGroundingProvider(
                new ObjectMapper(), writer, parser, directory, bundleRepo,
                registry.toString(), base.toString(), tenantDeployments.toString());
        GroundedStudioReviewService studio = new GroundedStudioReviewService(
                grounding, parser, writer, new ConsequenceDiffService(),
                new ProductionPdpDecisionSource(cerbos), noProse(), canonicalizer,
                new StudioSessionStore(), new StudioBaselineActivationService(directory),
                new SelfContainedBundleAssembler(base.toString()));

        // Re-author the seeded default child. The source file is intentionally named
        // tenant_default_agent.yaml while Studio emits agent@default.yaml: identity, not name, must win.
        String authored = Files.readString(base.resolve("tenant_default_agent.yaml"));
        ConsequenceReview review = studio.assembleAndReview(TENANT, "author-alice", "agent", authored);
        PolicyBundle candidate = studio.assembleCandidateBundle(TENANT, "agent", authored, null);
        assertThat(candidate.bundleId()).isEqualTo(review.candidateBundleId());

        assertThat(resourcePolicies(candidate, "agent", "default"))
                .as("the tenant-wide candidate must carry exactly one agent@default definition")
                .hasSize(1)
                .extracting(BundleFile::path)
                .containsExactly("policies/agent@default.yaml");
        assertThat(resourcePolicies(candidate, "agent", ""))
                .as("semantic replacement must retain the immutable agent root")
                .hasSize(1);
        assertThat(resourcePolicies(candidate, "relationship", "default"))
                .as("semantic replacement must retain unrelated tenant children")
                .hasSize(1);
        assertThat(candidate.files()).extracting(BundleFile::path)
                .as("semantic replacement must retain imported modules")
                .contains("policies/business_derived_roles.yaml", "policies/policy_studio_derived_roles.yaml");

        // This is the exact promotion-time structural probe, over the exact materialized candidate.
        StagingCandidateProbe promotionProbe = new StagingCandidateProbe(compileGate, base.toString());
        promotionProbe.verify(candidate);

        InMemoryTenantActiveBundleRegistry active = new InMemoryTenantActiveBundleRegistry();
        active.setActiveBundle(TENANT, review.currentBundleId());
        ConsequenceApprovalService approvals = new ConsequenceApprovalService(
                ConsequenceReviewSigner.withKey("default-tenant-promotion-regression"), active);
        ConsequenceApprovalRecord approval = approvals.approve(
                review, "approver-bob", Set.of("security_reviewer"), ApprovalDecision.APPROVE);
        CerbosRuntimeActivationProbe runtimeProbe = mock(CerbosRuntimeActivationProbe.class);
        PromotedBundleLoader loader = new PromotedBundleLoader(
                work.resolve("runtime").toString(), 0L, 1, runtimeProbe,
                "", "runtime", "", "us-east-1", true, "", "");
        PolicyPromotionService promotion = new PolicyPromotionService(
                directory, approvals, C5LifecycleFixtures.promotionRepo(), bundleRepo,
                C5LifecycleFixtures.approvalRepo(), canonicalizer, () -> "seeded-default-regression",
                promotionProbe, new GeneratedPolicyValidator(), parser,
                new ManifestPromotionValidationContextProvider(grounding), loader);

        promotion.promote(new PromotionRequest(
                candidate, review, approval, "seeded-default-promotion", PromotionRecord.Kind.PROMOTION));

        assertThat(directory.find(TENANT)).contains(candidate.bundleId());
        verify(runtimeProbe).awaitLoaded(candidate);
    }

    private static List<BundleFile> resourcePolicies(PolicyBundle bundle, String resource, String scope) {
        ResourcePolicyIdentity expected = new ResourcePolicyIdentity(
                resource, scope, BundleCanonicalizer.BUNDLE_VERSION_SENTINEL);
        return bundle.files().stream()
                .filter(file -> ResourcePolicyIdentity.fromYaml(file.yaml()).map(expected::equals).orElse(false))
                .toList();
    }

    private static Path repoPath(String relative) {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(relative);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("could not locate " + relative);
    }

    private static ObjectProvider<ConsequenceProseModelClient> noProse() {
        return new ObjectProvider<>() {
            @Override public ConsequenceProseModelClient getObject(Object... args) { return null; }
            @Override public ConsequenceProseModelClient getObject() { return null; }
            @Override public ConsequenceProseModelClient getIfAvailable() { return null; }
            @Override public ConsequenceProseModelClient getIfUnique() { return null; }
            @Override public Iterator<ConsequenceProseModelClient> iterator() {
                return List.<ConsequenceProseModelClient>of().iterator();
            }
        };
    }

    private static void requireRealPdp(boolean available) {
        boolean requiredInCi = Boolean.parseBoolean(
                System.getenv().getOrDefault("CONDUIT_DOCKER_REQUIRED", "false"));
        if (requiredInCi) {
            assertThat(available)
                    .as("CONDUIT_DOCKER_REQUIRED=true but pinned Cerbos is unavailable")
                    .isTrue();
        } else {
            Assumptions.assumeTrue(available, "pinned Cerbos unavailable — skipping promotion regression");
        }
    }
}
