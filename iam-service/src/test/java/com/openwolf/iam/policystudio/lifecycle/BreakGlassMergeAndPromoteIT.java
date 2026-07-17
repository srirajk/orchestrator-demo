package com.openwolf.iam.policystudio.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openwolf.iam.policystudio.BaseCeiling;
import com.openwolf.iam.policystudio.CanonicalPolicyWriter;
import com.openwolf.iam.policystudio.ConsequenceApprovalRecord;
import com.openwolf.iam.policystudio.ConsequenceApprovalService;
import com.openwolf.iam.policystudio.ConsequenceDiffService;
import com.openwolf.iam.policystudio.ConsequenceProseModelClient;
import com.openwolf.iam.policystudio.ConsequenceReview;
import com.openwolf.iam.policystudio.ConsequenceReviewSigner;
import com.openwolf.iam.policystudio.GroundedStudioReviewService;
import com.openwolf.iam.policystudio.ManifestBackedStudioGroundingProvider;
import com.openwolf.iam.policystudio.PolicyIR;
import com.openwolf.iam.policystudio.PolicyYamlParser;
import com.openwolf.iam.policystudio.ProductionPdpDecisionSource;
import com.openwolf.iam.policystudio.CerbosBatchDecisionSource;
import com.openwolf.iam.policystudio.TenantActiveBundleRegistry;
import com.openwolf.iam.policystudio.TenantScope;
import com.openwolf.iam.policystudio.ApprovalDecision;
import com.openwolf.iam.policystudio.GeneratedPolicyValidator;
import com.openwolf.iam.policystudio.ManifestVocabulary;
import com.openwolf.iam.policystudio.api.StudioSessionStore;
import com.openwolf.iam.policystudio.breakglass.BreakGlassAllowlist;
import com.openwolf.iam.policystudio.breakglass.BreakGlassApprovalService;
import com.openwolf.iam.policystudio.breakglass.BreakGlassArtifact;
import com.openwolf.iam.policystudio.breakglass.BreakGlassAuditPartition;
import com.openwolf.iam.policystudio.breakglass.BreakGlassGrant;
import com.openwolf.iam.policystudio.breakglass.BreakGlassPolicyCompiler;
import com.openwolf.iam.policystudio.breakglass.BreakGlassPromotionService;
import com.openwolf.iam.policystudio.breakglass.BreakGlassSodException;
import com.openwolf.iam.policystudio.breakglass.BreakGlassValidator;
import com.openwolf.iam.tenancy.ActiveTenantDirectory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * S4 — THE HEADLINE: a break-glass approval MERGES the emergency grant into the tenant's current active
 * bundle and PROMOTES it through C5, so the grant actually enforces in the serving PDP — additive, never a
 * revoke. Before S4, approval only recorded an audit event + flipped an in-memory {@code issued} flag (no
 * runtime effect), and the compiler produced a standalone deny-all-else that would have revoked the
 * tenant's normal access if ever loaded.
 *
 * <p>End to end against a REAL pinned Cerbos with the demo's disk + {@code watchForChanges} config, on an
 * EPHEMERAL Testcontainers PDP with a random host port — NEVER the demo's {@code conduit-cerbos:3594}:
 *
 * <ol>
 *   <li>Provision tenant {@code acme} and promote bundle A — a restriction child that ALLOWs
 *       {@code platform_admin:invoke} (a NORMAL grant) but DENYs {@code platform_admin:register}.</li>
 *   <li>Drive the REAL {@link BreakGlassPromotionService} to approve a break-glass grant of
 *       {@code platform_admin:register}: it grounds A as current, MERGES the time-boxed grant in, signs the
 *       approval (two-person SoD), and promotes → the runtime loader writes bundle B into the watched dir.</li>
 *   <li>At the tenant's new active {@code policyVersion=B}: a real {@code CheckResources} <b>ALLOWs the
 *       emergency tuple</b> ({@code register}) AND <b>still ALLOWs the normal grant</b> ({@code invoke}) —
 *       proving MERGE, not replace. A deny-all-else compiler would DENY {@code invoke} here and fail this.</li>
 *   <li>For an EXPIRED merged bundle (the CEL window already past), the emergency tuple <b>DENIES</b>
 *       (self-limiting inside the PDP) while the normal grant <b>still ALLOWs</b> — the grant expires with
 *       the rest of the bundle intact, no external revocation.</li>
 * </ol>
 */
class BreakGlassMergeAndPromoteIT {

    private static final DockerImageName CERBOS_IMAGE = DockerImageName.parse(
            System.getenv().getOrDefault("CERBOS_IMAGE", "ghcr.io/cerbos/cerbos:0.53.0"));
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TENANT = "acme";
    private static final String NORMAL_ACTION = "invoke";       // A ALLOWs this for platform_admin
    private static final String EMERGENCY_ACTION = "register";  // A DENYs this; break-glass grants it
    private static final String ROLE = "platform_admin";        // unconditional at the base ⇒ parental consent

    /**
     * Bundle A's tenant restriction child: platform_admin may invoke/invoke_membership (NORMAL access) but
     * NOT register/deregister; every other ceiling tuple is denied. Total over the agent ceiling.
     */
    private static String childABody() {
        return """
                apiVersion: api.cerbos.dev/v1
                resourcePolicy:
                  version: "default"
                  resource: agent
                  scope: "acme"
                  scopePermissions: SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS
                  rules:
                    - actions: ["invoke", "invoke_membership"]
                      effect: EFFECT_ALLOW
                      roles: ["platform_admin"]
                    - actions: ["register", "deregister"]
                      effect: EFFECT_DENY
                      roles: ["platform_admin"]
                    - actions: ["invoke", "invoke_membership", "register", "deregister"]
                      effect: EFFECT_DENY
                      roles: ["domain_admin"]
                    - actions: ["invoke", "invoke_membership"]
                      effect: EFFECT_DENY
                      roles: ["chat_user", "relationship_manager"]
                """;
    }

    @Test
    void breakGlassApprovalMergesEmergencyGrantAndEnforcesItAdditivelyThenExpires() throws Exception {
        requireDocker();

        // ── Runtime PDP topology: a writable /policies volume iam-service writes and Cerbos watches. ──
        Path work = Files.createTempDirectory("breakglass-s4-merge-");
        Path conf = work.resolve("conf");
        Path policies = work.resolve("policies");
        Files.createDirectories(conf);
        Files.createDirectories(policies);
        Files.writeString(conf.resolve("config.yaml"), """
                server:
                  httpListenAddr: ":3592"
                storage:
                  driver: "disk"
                  disk:
                    directory: /policies
                    watchForChanges: true
                """);
        // The base scope-chain's name-keyed derived-roles module (imported by the agent root) is global —
        // the loader never writes a second copy, so seed it into the watched dir exactly as the demo does.
        String baseDir = infraBaseDir();
        Files.copy(Path.of(baseDir).resolve("business_derived_roles.yaml"),
                policies.resolve("business_derived_roles.yaml"));

        // ── Shared control-plane state: ONE directory + ONE immutable bundle store (grounding reads it,
        //    promotion writes it) — the real wiring. ──
        CanonicalPolicyWriter writer = new CanonicalPolicyWriter();
        PolicyYamlParser parser = new PolicyYamlParser();
        ObjectMapper mapper = new ObjectMapper();
        ActiveTenantDirectory directory = C5LifecycleFixtures.directory();
        PolicyBundleRepository bundleRepo = C5LifecycleFixtures.bundleRepo();
        PromotionRepository promotions = C5LifecycleFixtures.promotionRepo();

        CerbosBatchDecisionSource cerbos = new CerbosBatchDecisionSource(
                System.getenv().getOrDefault("CERBOS_IMAGE", "ghcr.io/cerbos/cerbos:0.53.0"),
                120, "0.53.0", baseDir, writer);
        requireRealPdp(cerbos.isAvailable());
        ProductionPdpDecisionSource pdp = new ProductionPdpDecisionSource(cerbos);

        ManifestBackedStudioGroundingProvider grounding =
                new ManifestBackedStudioGroundingProvider(mapper, writer, parser, directory, bundleRepo, "registry", baseDir, "infra/cerbos/tenants", "default");
        StudioSessionStore store = new StudioSessionStore();
        SelfContainedBundleAssembler assembler = new SelfContainedBundleAssembler(baseDir);
        GroundedStudioReviewService reviews = new GroundedStudioReviewService(
                grounding, parser, writer, new ConsequenceDiffService(), pdp, noProse(),
                new BundleCanonicalizer(), store, new StudioBaselineActivationService(directory), assembler);

        // Approvals wired with the SAME session store as the review-author registry (author≠approver) and
        // the live directory as the staleness pointer — the real production topology.
        ConsequenceReviewSigner signer = ConsequenceReviewSigner.withKey(C5LifecycleFixtures.SIGNING_KEY);
        TenantActiveBundleRegistry activeBundles = new DirectoryBackedActiveBundleRegistry(directory);
        ConsequenceApprovalService approvals =
                new ConsequenceApprovalService(signer, activeBundles, store, Set.of("security_reviewer"));

        // The REAL runtime loader — writes promoted bundles into the Cerbos-watched dir.
        PromotedBundleLoader loader = new PromotedBundleLoader(policies.toString());
        PolicyPromotionService promotion = new PolicyPromotionService(
                directory, approvals, promotions, bundleRepo, C5LifecycleFixtures.approvalRepo(),
                C5LifecycleFixtures.canon(), C5LifecycleFixtures.gitResolver("s4-breakglass"),
                C5LifecycleFixtures.passingProbe(), C5LifecycleFixtures.validator(), new PolicyYamlParser(),
                C5LifecycleFixtures.validationProvider(), loader);

        BreakGlassPolicyCompiler compiler = new BreakGlassPolicyCompiler();
        BreakGlassPromotionService breakGlass = new BreakGlassPromotionService(
                grounding, reviews, approvals, promotion, compiler, writer);

        try (GenericContainer<?> ephemeralPdp = cerbos(conf.toString(), policies.toString())) {
            ephemeralPdp.start();
            String base = "http://" + ephemeralPdp.getHost() + ":" + ephemeralPdp.getMappedPort(3592);
            assertThat(ephemeralPdp.getContainerName())
                    .as("must be an ephemeral container, never the demo's conduit-cerbos")
                    .doesNotContain("conduit-cerbos");

            // ── 1. Promote bundle A (normal grants) — the tenant's current active bundle. ──
            ConsequenceReview reviewA = reviews.assembleAndReview(TENANT, "author-alice", "agent", childABody());
            ConsequenceApprovalRecord approvalA =
                    approvals.approve(reviewA, "approver-bob", Set.of("security_reviewer"), ApprovalDecision.APPROVE);
            PolicyBundle bundleA = reviews.assembleCandidateBundle(TENANT, "agent", childABody(), null);
            promotion.promote(new PromotionRequest(
                    bundleA, reviewA, approvalA, "key-A", PromotionRecord.Kind.PROMOTION));
            assertThat(directory.find(TENANT)).as("A is now the active bundle").hasValue(bundleA.bundleId());

            // Sanity at version=A: invoke ALLOWs (normal), register DENYs (A withholds it).
            assertThat(awaitDecision(base, bundleA.bundleId(), ROLE, NORMAL_ACTION, "EFFECT_ALLOW"))
                    .as("A grants the normal tuple").isEqualTo("EFFECT_ALLOW");
            assertThat(decide(base, bundleA.bundleId(), ROLE, EMERGENCY_ACTION))
                    .as("A withholds register — the emergency the break-glass grant will add")
                    .isEqualTo("EFFECT_DENY");

            // Capture A's child IR (the tenant's current restriction child) for the expiry variant below.
            PolicyIR currentChildA = grounding.snapshot(TENANT, "agent").current().policy();
            assertThat(currentChildA).as("A has a real tenant restriction child").isNotNull();

            // ── 2. Break-glass approval: merge register into A and promote through C5 (real path). ──
            Instant now = Instant.now();
            BreakGlassGrant liveGrant = new BreakGlassGrant(
                    TenantScope.of(TENANT), "agent", EMERGENCY_ACTION, ROLE,
                    now, now.plus(Duration.ofMinutes(30)), "S4 incident — restore admin registration", "author-alice");
            StudioSessionStore.PendingGrant pending =
                    store.putGrant(TENANT, "author-alice", artifact(liveGrant));
            PromotionReceipt receipt = breakGlass.mergeAndPromote(
                    pending, "approver-bob", Set.of("security_reviewer"), "corr-live");
            String bundleB = receipt.toBundleId();
            assertThat(directory.find(TENANT)).as("B (A + emergency) is now active").hasValue(bundleB);
            assertThat(bundleB).isNotEqualTo(bundleA.bundleId());

            // ── 3. At version=B: the emergency tuple ALLOWs AND the normal tuple STILL ALLOWs (MERGE). ──
            assertThat(awaitDecision(base, bundleB, ROLE, EMERGENCY_ACTION, "EFFECT_ALLOW"))
                    .as("after break-glass approval the emergency tuple (register) ALLOWs in the serving PDP")
                    .isEqualTo("EFFECT_ALLOW");
            assertThat(decide(base, bundleB, ROLE, NORMAL_ACTION))
                    .as("MERGE, not replace: the tenant's normal grant (invoke) STILL ALLOWs — a deny-all-else "
                            + "compiler would DENY this")
                    .isEqualTo("EFFECT_ALLOW");

            // ── 4. Expiry: an EXPIRED merged bundle DENYs the emergency tuple while normal grants ALLOW. ──
            BreakGlassGrant expiredGrant = new BreakGlassGrant(
                    TenantScope.of(TENANT), "agent", EMERGENCY_ACTION, ROLE,
                    now.minus(Duration.ofMinutes(10)), now.minusSeconds(5), "S4 incident — already lapsed", "author-alice");
            PolicyBundle expiredBundle = materializeMerged(
                    compiler.compile(expiredGrant, agentCeiling(), currentChildA), writer, assembler);
            loader.load(expiredBundle);
            String bundleExpired = expiredBundle.bundleId();

            assertThat(awaitDecision(base, bundleExpired, ROLE, NORMAL_ACTION, "EFFECT_ALLOW"))
                    .as("the normal grant survives inside the expired bundle — the rest of the bundle is intact")
                    .isEqualTo("EFFECT_ALLOW");
            assertThat(decide(base, bundleExpired, ROLE, EMERGENCY_ACTION))
                    .as("past its CEL window the emergency tuple self-limits to DENY inside the PDP")
                    .isEqualTo("EFFECT_DENY");
        }
    }

    /**
     * Two-person SoD and the C6/H1 bounds are still enforced on the expedited path: the author may not
     * approve their own grant, and an over-TTL / wildcard grant never becomes admissible.
     */
    @Test
    void twoPersonSodAndBoundsStillEnforcedOnTheExpeditedPath() {
        BreakGlassApprovalService approval = new BreakGlassApprovalService(
                mock(BreakGlassAuditPartition.class), "studio_policy_approver");
        BreakGlassArtifact art = artifact(new BreakGlassGrant(
                TenantScope.of(TENANT), "agent", EMERGENCY_ACTION, ROLE,
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15)), "incident", "alice"));
        // author == approver ⇒ rejected (two-person by construction).
        assertThatThrownBy(() -> approval.approveAndIssue(
                art, "alice", "alice", Set.of("studio_policy_approver"), "corr"))
                .isInstanceOf(BreakGlassSodException.class);

        // H1 bounds: a TTL beyond the 60-minute ceiling, and a wildcard action, are inadmissible.
        BreakGlassValidator bounds = new BreakGlassValidator(60);
        Instant now = Instant.now();
        BreakGlassAllowlist allowlist = new BreakGlassAllowlist(Set.of("agent"), Set.of(EMERGENCY_ACTION));
        assertThat(bounds.validate(new BreakGlassGrant(TenantScope.of(TENANT), "agent", EMERGENCY_ACTION, ROLE,
                now, now.plus(Duration.ofMinutes(120)), "too long", "alice"), allowlist, vocab()).accepted())
                .as("a >60m TTL is rejected").isFalse();
        assertThat(bounds.validate(new BreakGlassGrant(TenantScope.of(TENANT), "agent", "*", ROLE,
                now, now.plus(Duration.ofMinutes(10)), "wildcard", "alice"), allowlist, vocab()).accepted())
                .as("a wildcard action is rejected").isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────

    /** Materialise a self-contained candidate bundle from a merged child IR (mirrors GroundedStudioReviewService). */
    private static PolicyBundle materializeMerged(PolicyIR merged, CanonicalPolicyWriter writer,
                                                  SelfContainedBundleAssembler assembler) {
        PolicyIR sentinel = new PolicyIR(merged.apiVersion(), BundleCanonicalizer.BUNDLE_VERSION_SENTINEL,
                merged.resource(), TENANT, merged.scopePermissions(), merged.importDerivedRoles(), merged.rules());
        List<BundleFile> files = new ArrayList<>(assembler.captureScopeChain(merged.resource()));
        files.add(new BundleFile("policies/" + merged.resource() + "@" + TENANT + ".yaml", writer.write(sentinel)));
        return PolicyBundle.materialize(TENANT, files, List.of("manifest:agent@sha-s4-expired"),
                new BundleTestMetadata("fs-s4-expired", 1, "c3-independent-oracle", "cerbos:0.53.0"),
                new BundleCanonicalizer());
    }

    /** A doubly-admissible artifact carrying the grant — mergeAndPromote only reads its grant + author. */
    private static BreakGlassArtifact artifact(BreakGlassGrant grant) {
        PolicyIR ir = new PolicyIR("api.cerbos.dev/v1", "default", "agent", TENANT,
                "SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS", List.of(), List.of());
        return new BreakGlassArtifact(grant, ir, "",
                new BreakGlassValidator.Result(true, List.of()),
                new GeneratedPolicyValidator.Result(true, List.of()));
    }

    private static ManifestVocabulary vocab() {
        return new ManifestVocabulary("agent",
                Set.of("invoke", "invoke_membership", "register", "deregister"),
                Set.of("internal", "confidential", "confidential-pii"),
                Set.of("domain", "audience", "access_mode", "data_classification"),
                Set.of("platform_admin", "domain_admin", "chat_user", "relationship_manager", "conduit_admin"),
                Set.of("business_derived_roles"));
    }

    /** The agent base ceiling (12 tuples) — mirrors the manifest grounding provider. */
    private static BaseCeiling agentCeiling() {
        return new BaseCeiling("agent", Set.of(
                new BaseCeiling.Tuple("invoke", "platform_admin"),
                new BaseCeiling.Tuple("invoke_membership", "platform_admin"),
                new BaseCeiling.Tuple("register", "platform_admin"),
                new BaseCeiling.Tuple("deregister", "platform_admin"),
                new BaseCeiling.Tuple("invoke", "domain_admin"),
                new BaseCeiling.Tuple("invoke_membership", "domain_admin"),
                new BaseCeiling.Tuple("register", "domain_admin"),
                new BaseCeiling.Tuple("deregister", "domain_admin"),
                new BaseCeiling.Tuple("invoke", "chat_user"),
                new BaseCeiling.Tuple("invoke", "relationship_manager"),
                new BaseCeiling.Tuple("invoke_membership", "chat_user"),
                new BaseCeiling.Tuple("invoke_membership", "relationship_manager")),
                true, Set.of("agent@"));
    }

    private GenericContainer<?> cerbos(String confDir, String policyDir) {
        return new GenericContainer<>(CERBOS_IMAGE)
                .withExposedPorts(3592)
                .withFileSystemBind(confDir, "/conf", BindMode.READ_ONLY)
                .withFileSystemBind(policyDir, "/policies", BindMode.READ_ONLY)
                .withCommand("server", "--config=/conf/config.yaml")
                .waitingFor(Wait.forHttp("/_cerbos/health").forPort(3592).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(90)));
    }

    /** One real CheckResources for (role, action) at {@code scope=acme, policyVersion}; returns the effect. */
    private String decide(String baseUrl, String policyVersion, String role, String action) throws Exception {
        String body = """
                {"principal":{"id":"admin","policyVersion":"%s","roles":["%s"],"attr":{}},
                 "resources":[{"actions":["%s"],
                  "resource":{"kind":"agent","policyVersion":"%s","scope":"%s","id":"a1","attr":{}}}]}
                """.formatted(policyVersion, role, action, policyVersion, TENANT);
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/check/resources"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("check body: %s", resp.body()).isEqualTo(200);
        JsonNode actions = JSON.readTree(resp.body()).path("results").path(0).path("actions");
        return actions.path(action).asText();
    }

    /** Poll until the PDP's decision reaches {@code want} (watch reload), or time out. */
    private String awaitDecision(String baseUrl, String policyVersion, String role, String action, String want)
            throws Exception {
        String last = "";
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(40).toMillis();
        while (System.currentTimeMillis() < deadline) {
            last = decide(baseUrl, policyVersion, role, action);
            if (want.equals(last)) {
                return last;
            }
            Thread.sleep(500);
        }
        return last;
    }

    /** The repo's base policy dir, resolved from the iam-service module cwd (with a {@code ..} fallback). */
    private static String infraBaseDir() {
        Path direct = Path.of("infra/cerbos/policies");
        return Files.isDirectory(direct)
                ? direct.toString()
                : Path.of("..").resolve("infra/cerbos/policies").normalize().toString();
    }

    /** A no-op prose provider — the LLM display seam is absent, proving it is not in the truth path. */
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
                    .as("CONDUIT_DOCKER_REQUIRED=true but pinned Cerbos is unavailable; this proof must run")
                    .isTrue();
        } else {
            Assumptions.assumeTrue(available, "pinned Cerbos unavailable — skipping the S4 merge-and-promote proof");
        }
    }

    private static void requireDocker() {
        boolean docker;
        try {
            Process p = new ProcessBuilder("docker", "--version").redirectErrorStream(true).start();
            docker = p.waitFor() == 0;
        } catch (Exception e) {
            docker = false;
        }
        boolean requiredInCi = Boolean.parseBoolean(
                System.getenv().getOrDefault("CONDUIT_DOCKER_REQUIRED", "false"));
        if (requiredInCi) {
            assertThat(docker)
                    .as("CONDUIT_DOCKER_REQUIRED=true (CI) but Docker is unavailable — this live-PDP proof must RUN")
                    .isTrue();
        } else {
            Assumptions.assumeTrue(docker, "Docker not available — skipping the S4 merge-and-promote headline");
        }
    }
}
