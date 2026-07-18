package com.openwolf.iam.policystudio.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openwolf.iam.policystudio.ApprovalDecision;
import com.openwolf.iam.policystudio.CanonicalPolicyWriter;
import com.openwolf.iam.policystudio.ConsequenceApprovalRecord;
import com.openwolf.iam.policystudio.ConsequenceApprovalService;
import com.openwolf.iam.policystudio.ConsequenceReview;
import com.openwolf.iam.policystudio.ConsequenceReviewSigner;
import com.openwolf.iam.policystudio.GeneratedPolicyValidator;
import com.openwolf.iam.policystudio.GroundedStudioReviewService;
import com.openwolf.iam.policystudio.InMemoryTenantActiveBundleRegistry;
import com.openwolf.iam.policystudio.ManifestBackedStudioGroundingProvider;
import com.openwolf.iam.policystudio.PolicyYamlParser;
import com.openwolf.iam.policystudio.StudioGroundingSnapshot;
import com.openwolf.iam.policystudio.StudioGroundingTestFixtures;
import com.openwolf.iam.policystudio.api.StudioSessionStore;
import com.openwolf.iam.tenancy.ActiveTenantDirectory;
import com.openwolf.iam.tenancy.AuditPartitionAdapter;
import com.openwolf.iam.tenancy.CerbosPolicyBootstrapAdapter;
import com.openwolf.iam.tenancy.InProcessRegistrySpaceAdapter;
import com.openwolf.iam.tenancy.InProcessTenantNamespaceAdapter;
import com.openwolf.iam.tenancy.PolicyBootstrapAdapter;
import com.openwolf.iam.tenancy.ProvisioningOperation;
import com.openwolf.iam.tenancy.ProvisioningOperationRepository;
import com.openwolf.iam.tenancy.ProvisioningRequest;
import com.openwolf.iam.tenancy.ProvisioningResult;
import com.openwolf.iam.tenancy.RuntimeBootstrapPolicyPublisher;
import com.openwolf.iam.tenancy.TenantBootstrapBundle;
import com.openwolf.iam.tenancy.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proves the B4 bootstrap is the real C5 baseline and the first Studio promotion CASes from it. */
class ProvisioningStudioBootstrapIntegrationTest {

    private static final String TENANT = "acme";

    @Test
    void provisionPublishesExactBundleThenFirstStudioPromotionSucceeds(@TempDir Path work) throws Exception {
        Path base = repoPath("infra/cerbos/policies");
        Path registry = repoPath("registry");
        Path tenantDeployments = work.resolve("tenants");
        StudioGroundingTestFixtures.writeTenantDeployment(tenantDeployments, TENANT);

        CanonicalPolicyWriter writer = new CanonicalPolicyWriter();
        PolicyYamlParser parser = new PolicyYamlParser();
        BundleCanonicalizer canonicalizer = new BundleCanonicalizer();
        SelfContainedBundleAssembler assembler = new SelfContainedBundleAssembler(base.toString());
        ActiveTenantDirectory directory = C5LifecycleFixtures.directory();
        PolicyBundleRepository bundleRepo = C5LifecycleFixtures.bundleRepo();
        CerbosRuntimeActivationProbe activationProbe = mock(CerbosRuntimeActivationProbe.class);
        PromotedBundleLoader loader = new PromotedBundleLoader(
                work.resolve("runtime").toString(), 0L, 1, activationProbe,
                "", "runtime", "", "us-east-1", true, "", "");

        CerbosPolicyBootstrapAdapter realBootstrap = new CerbosPolicyBootstrapAdapter(
                writer,
                new com.openwolf.iam.policystudio.CerbosCompileGate(
                        "ghcr.io/cerbos/cerbos:0.53.0", 60),
                assembler,
                canonicalizer,
                base.toString(),
                work.resolve("staging").toString(),
                registry.toString());
        PolicyBootstrapAdapter bootstrapWithoutDocker = new PolicyBootstrapAdapter() {
            @Override public TenantBootstrapBundle stage(String tenantId) { return realBootstrap.stage(tenantId); }
            @Override public void probe(TenantBootstrapBundle bundle) { /* structural assertions below */ }
            @Override public boolean isProbeAvailable() { return false; }
            @Override public void discardStaged(TenantBootstrapBundle bundle) { realBootstrap.discardStaged(bundle); }
        };

        TenantProvisioningService provisioning = new TenantProvisioningService(
                operations(), directory, bootstrapWithoutDocker,
                new InProcessTenantNamespaceAdapter(), new InProcessRegistrySpaceAdapter(), audit(),
                new RuntimeBootstrapPolicyPublisher(loader, bundleRepo, () -> "bootstrap-commit"));

        ProvisioningResult provisioned = provisioning.provision(
                new ProvisioningRequest(TENANT, "Acme", "acme"), "bootstrap-key", "platform-admin");

        String bootstrapId = provisioned.policyVersion();
        assertThat(bootstrapId).startsWith("b_");
        assertThat(directory.find(TENANT)).contains(bootstrapId);
        verify(activationProbe).awaitLoaded(
                org.mockito.ArgumentMatchers.argThat(bundle -> bundle.bundleId().equals(bootstrapId)));
        PolicyBundleRecord bootstrapRecord = bundleRepo.findById(bootstrapId).orElseThrow();
        assertThat(bootstrapRecord.getCanonicalContent()).contains("agent@acme.yaml");
        assertThat(bootstrapRecord.getCanonicalContent()).contains("relationship@acme.yaml");
        assertThat(bootstrapRecord.getCanonicalContent()).contains("policy_draft@acme.yaml");
        assertThat(bootstrapRecord.getCanonicalContent()).contains("business_derived_roles.yaml");
        assertThat(bootstrapRecord.getCanonicalContent()).contains("MANIFESTS:");
        assertThat(bootstrapRecord.getCanonicalContent()).contains("domains/wealth-management.json#");
        try (java.util.stream.Stream<Path> runtimeFiles = Files.list(work.resolve("runtime"))) {
            assertThat(runtimeFiles.map(Path::getFileName).map(Path::toString).toList())
                    .allMatch(name -> name.startsWith(bootstrapId + "__"));
        }

        // Studio resolves the provisioned b_* record as current (not a synthetic base-only snapshot).
        ManifestBackedStudioGroundingProvider grounding = new ManifestBackedStudioGroundingProvider(
                new ObjectMapper(), writer, parser, directory, bundleRepo,
                registry.toString(), base.toString(), tenantDeployments.toString());
        StudioGroundingSnapshot current = grounding.snapshot(TENANT, "agent");
        assertThat(current.current().bundleId()).isEqualTo(bootstrapId);
        assertThat(current.current().policy()).isNotNull();

        GroundedStudioReviewService studio = new GroundedStudioReviewService(
                grounding, parser, writer, null, null, null, canonicalizer, new StudioSessionStore(),
                new StudioBaselineActivationService(directory), assembler);
        String agentDeny = BundleContentReader.tenantChildYaml(
                bootstrapRecord.getCanonicalContent(), "agent", TENANT).orElseThrow()
                .replace(BundleCanonicalizer.BUNDLE_VERSION_SENTINEL, "default");
        PolicyBundle candidate = studio.assembleCandidateBundle(TENANT, "agent", agentDeny, null);
        assertThat(candidate.files().stream().filter(f -> f.path().contains("@" + TENANT))).hasSize(8);

        ConsequenceReview review = review(bootstrapId, candidate);
        InMemoryTenantActiveBundleRegistry active = new InMemoryTenantActiveBundleRegistry();
        active.setActiveBundle(TENANT, bootstrapId);
        ConsequenceReviewSigner signer = ConsequenceReviewSigner.withKey("bootstrap-first-promotion-key");
        ConsequenceApprovalService approvals = new ConsequenceApprovalService(signer, active);
        ConsequenceApprovalRecord approval = approvals.approve(
                review, "security-reviewer", Set.of("security_reviewer"), ApprovalDecision.APPROVE);

        PolicyPromotionService promotion = new PolicyPromotionService(
                directory, approvals, C5LifecycleFixtures.promotionRepo(), bundleRepo,
                C5LifecycleFixtures.approvalRepo(), canonicalizer, () -> "first-promotion-commit",
                C5LifecycleFixtures.passingProbe(), new GeneratedPolicyValidator(), parser,
                new ManifestPromotionValidationContextProvider(grounding), loader);
        promotion.promote(new PromotionRequest(
                candidate, review, approval, "first-studio-promotion", PromotionRecord.Kind.PROMOTION));

        assertThat(directory.find(TENANT)).contains(candidate.bundleId());
        assertThat(bundleRepo.findById(candidate.bundleId())).isPresent();
    }

    private static ConsequenceReview review(String currentId, PolicyBundle candidate) {
        String fixtureHash = candidate.testMetadata().fixtureSetHash();
        ConsequenceReview unsigned = new ConsequenceReview(
                TENANT, "agent", currentId, candidate.bundleId(), fixtureHash,
                List.of(), false, 0, "[]", "placeholder", null, null, Instant.now(), null);
        return new ConsequenceReview(
                TENANT, "agent", currentId, candidate.bundleId(), fixtureHash,
                List.of(), false, 0, "[]", unsigned.recomputeHash(), null, null, Instant.now(), null);
    }

    private static ProvisioningOperationRepository operations() {
        Map<String, ProvisioningOperation> byKey = new HashMap<>();
        ProvisioningOperationRepository repo = mock(ProvisioningOperationRepository.class);
        when(repo.findByIdempotencyKey(anyString()))
                .thenAnswer(a -> Optional.ofNullable(byKey.get(a.<String>getArgument(0))));
        when(repo.save(any(ProvisioningOperation.class))).thenAnswer(a -> {
            ProvisioningOperation op = a.getArgument(0);
            byKey.put(op.getIdempotencyKey(), op);
            return op;
        });
        return repo;
    }

    private static AuditPartitionAdapter audit() {
        return new AuditPartitionAdapter() {
            private boolean exists;
            @Override public void recordProvisioned(String tenantId, String actor, String correlationId) { exists = true; }
            @Override public void recordDeprovisioned(String tenantId, String actor, String correlationId) { }
            @Override public boolean partitionExists(String tenantId) { return exists; }
            @Override public List<com.openwolf.iam.entity.AuditLog> export(String tenantId) { return List.of(); }
        };
    }

    private static Path repoPath(String relative) {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(relative);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("could not locate " + relative);
    }
}
