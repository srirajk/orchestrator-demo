package com.openwolf.iam.policystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4 — the REAL PDP evidence run (headline). Runs the consequence diff over the PINNED Cerbos
 * ({@link CerbosBatchDecisionSource}) via an ephemeral {@code docker run --rm} (no name, no port →
 * never touches the running {@code conduit-cerbos}); a widening candidate (platform_admin regains the
 * full agent surface at scope {@code acme}) produces a genuine DENY→ALLOW over-permission alarm from
 * real policy decisions. Skips (assumeTrue) where neither Docker nor a cerbos binary is available.
 *
 * <p>On a run it also WRITES the checked-in evidence bundle to
 * {@code docs/implementation/evidence/studio/c4/} — the two immutable bundle YAMLs, the CheckResources
 * batch request, the raw per-cell PDP responses for both snapshots, the computed
 * {@link ConsequenceReview}, and a sample signed approval record — so the truth path is auditable.
 */
class C4RealPdpEvidenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String currentBody() {
        return """
                apiVersion: api.cerbos.dev/v1
                resourcePolicy:
                  version: "default"
                  resource: agent
                  scope: "acme"
                  scopePermissions: SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS
                  rules:
                    - actions: ["invoke", "invoke_membership", "register", "deregister"]
                      effect: EFFECT_DENY
                      roles: ["platform_admin"]
                """;
    }

    private static String candidateBody() {
        return """
                apiVersion: api.cerbos.dev/v1
                resourcePolicy:
                  version: "default"
                  resource: agent
                  scope: "acme"
                  scopePermissions: SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS
                  rules:
                    - actions: ["invoke", "invoke_membership", "register", "deregister"]
                      effect: EFFECT_ALLOW
                      roles: ["platform_admin"]
                """;
    }

    private static FixtureCell cell(String action, String label) {
        return new FixtureCell(Set.of("platform_admin"), "acme", Map.of(), "acme", Map.of(), action, label);
    }

    @Test
    void realCerbosDiffRaisesOverPermissionAlarmAndWritesEvidence() throws Exception {
        CanonicalPolicyWriter writer = new CanonicalPolicyWriter();
        Path baseBundleDir = PolicyStudioFixtures.baseBundleDir();
        CerbosBatchDecisionSource cerbos = new CerbosBatchDecisionSource(
                "ghcr.io/cerbos/cerbos:0.53.0", 120, "0.53.0", baseBundleDir.toString(), writer);

        Assumptions.assumeTrue(cerbos.isAvailable(),
                "neither a pinned cerbos binary nor Docker is available — skipping the real-PDP evidence run");

        BundleSnapshot current = BundleSnapshot.of(
                new PolicyYamlParser().parse(currentBody()), PolicyStudioFixtures.agentCeiling(), writer);
        BundleSnapshot candidate = BundleSnapshot.of(
                new PolicyYamlParser().parse(candidateBody()), PolicyStudioFixtures.agentCeiling(), writer);
        ConsequenceFixtureMatrix matrix = ConsequenceFixtureMatrix.of(List.of(
                cell("register", "platformAdmin_register"),
                cell("invoke", "platformAdmin_invoke")));

        ConsequenceReview review = new ConsequenceDiffService().computeReview(
                "acme", PolicyStudioFixtures.agentVocab(), current, candidate, matrix, cerbos);

        // The alarm is real — DENY→ALLOW decisions from the pinned PDP.
        assertThat(review.provenance().sourceId()).startsWith("cerbos:");
        assertThat(review.overPermissionAlarm()).isTrue();
        assertThat(review.principalsGainingAccess()).isEqualTo(2);
        assertThat(review.wideningDeltas()).hasSize(2)
                .allSatisfy(d -> {
                    assertThat(d.from()).isEqualTo(Effect.DENY);
                    assertThat(d.to()).isEqualTo(Effect.ALLOW);
                });
        assertThat(review.provenance().currentBatch().byCell().get("platformAdmin_register"))
                .isEqualTo(Effect.DENY);
        assertThat(review.provenance().candidateBatch().byCell().get("platformAdmin_register"))
                .isEqualTo(Effect.ALLOW);

        // Sign a sample approval bound to this exact review.
        InMemoryTenantActiveBundleRegistry registry = new InMemoryTenantActiveBundleRegistry();
        registry.setActiveBundle("acme", current.bundleId());
        ConsequenceReviewSigner signer = ConsequenceReviewSigner.withKey("c4-evidence-signing-key");
        ConsequenceApprovalService approvals = new ConsequenceApprovalService(signer, registry);
        ConsequenceApprovalRecord record = approvals.approve(
                review, "sec-reviewer-01", Set.of("security_reviewer"), ApprovalDecision.APPROVE);
        assertThat(approvals.authorizesPromotion(record, review)).isTrue();

        writeEvidence(baseBundleDir, current, candidate, matrix, review, record, writer);
    }

    @Test
    void seededDefaultTenantCandidateReplacesExistingChildDuringRealPdpEvaluation() {
        CanonicalPolicyWriter writer = new CanonicalPolicyWriter();
        Path productionBundle = Path.of("../infra/cerbos/policies").toAbsolutePath().normalize();
        CerbosBatchDecisionSource cerbos = new CerbosBatchDecisionSource(
                "ghcr.io/cerbos/cerbos:0.53.0", 120, "0.53.0", productionBundle.toString(), writer);
        Assumptions.assumeTrue(cerbos.isAvailable(),
                "neither a pinned cerbos binary nor Docker is available — skipping the real-PDP evaluation");

        PolicyIR replacement = new PolicyYamlParser().parse(
                PolicyStudioFixtures.candidateWithScope("default"));
        BundleSnapshot candidate = BundleSnapshot.of(replacement, PolicyStudioFixtures.agentCeiling(), writer);
        FixtureCell invoke = new FixtureCell(Set.of("platform_admin"), "default", Map.of(),
                "default", Map.of(), "invoke", "default-platform-admin-invoke");

        PdpBatchResult result = cerbos.evaluate(candidate, List.of(invoke));

        assertThat(result.byCell().get("default-platform-admin-invoke")).isEqualTo(Effect.ALLOW);
    }

    private void writeEvidence(Path baseBundleDir, BundleSnapshot current, BundleSnapshot candidate,
                               ConsequenceFixtureMatrix matrix, ConsequenceReview review,
                               ConsequenceApprovalRecord record, CanonicalPolicyWriter writer) throws Exception {
        // Module CWD is iam-service/; the repo root is its parent.
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path dir = repoRoot.resolve("docs/implementation/evidence/studio/c4");
        if (!Files.isDirectory(dir.getParent())) {
            return; // evidence tree not present in this checkout — nothing to write
        }
        Files.createDirectories(dir);

        Files.writeString(dir.resolve("current-bundle.yaml"), writer.write(current.policy()));
        Files.writeString(dir.resolve("candidate-bundle.yaml"), writer.write(candidate.policy()));

        // Raw per-cell PDP responses for both immutable snapshots (audit / replay).
        Files.writeString(dir.resolve("pdp-response-current.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(batchView(review.provenance().currentBatch())));
        Files.writeString(dir.resolve("pdp-response-candidate.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(batchView(review.provenance().candidateBatch())));

        // The computed review (truth fields + hash + disclosure + rendered consequences).
        Files.writeString(dir.resolve("consequence-review.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reviewView(review, matrix, current, candidate)));

        // A sample signed approval record bound to the exact review hash.
        Files.writeString(dir.resolve("signed-approval-record.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(recordView(record)));
    }

    private Map<String, Object> batchView(PdpBatchResult batch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bundleId", batch.bundleId());
        m.put("sourceId", batch.sourceId());
        List<Object> decisions = batch.decisions().stream().map(d -> {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("cell", d.cellLabel());
            dm.put("effect", "EFFECT_" + d.effect());
            dm.put("callId", d.callId());
            dm.put("rawResponse", d.rawResponse());
            return (Object) dm;
        }).toList();
        m.put("decisions", decisions);
        return m;
    }

    private Map<String, Object> reviewView(ConsequenceReview r, ConsequenceFixtureMatrix matrix,
                                           BundleSnapshot current, BundleSnapshot candidate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", r.tenantId());
        m.put("resourceKind", r.resourceKind());
        m.put("currentBundleId", r.currentBundleId());
        m.put("candidateBundleId", r.candidateBundleId());
        m.put("fixtureSetHash", r.fixtureSetHash());
        m.put("sampledCellCount", matrix.cells().size());
        m.put("overPermissionAlarm", r.overPermissionAlarm());
        m.put("principalsGainingAccess", r.principalsGainingAccess());
        m.put("consequenceReviewHash", r.consequenceReviewHash());
        m.put("pdpSourceId", r.provenance().sourceId());
        Map<String, Object> disclosure = new LinkedHashMap<>();
        disclosure.put("sampledNotFormal", r.disclosure().sampledNotFormal());
        disclosure.put("sampledCellCount", r.disclosure().sampledCellCount());
        disclosure.put("statement", r.disclosure().statement());
        m.put("sampledNotFormalDisclosure", disclosure);
        List<Object> deltas = r.deltas().stream().map(d -> {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("direction", d.direction().toString());
            dm.put("overPermission", d.overPermission());
            dm.put("from", "EFFECT_" + d.from());
            dm.put("to", "EFFECT_" + d.to());
            dm.put("businessConsequence", d.businessConsequence());
            dm.put("canonicalRow", d.canonicalRow());
            return (Object) dm;
        }).toList();
        m.put("deltas", deltas);
        m.put("canonicalDelta", r.canonicalDelta());
        return m;
    }

    private Map<String, Object> recordView(ConsequenceApprovalRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", r.tenantId());
        m.put("currentBundleId", r.currentBundleId());
        m.put("candidateBundleId", r.candidateBundleId());
        m.put("fixtureSetHash", r.fixtureSetHash());
        m.put("consequenceReviewHash", r.consequenceReviewHash());
        m.put("overPermissionAlarm", r.overPermissionAlarm());
        m.put("approverId", r.approverId());
        m.put("approverRoles", r.approverRoles());
        m.put("decision", r.decision().toString());
        m.put("signatureAlg", "HmacSHA256");
        m.put("signature", r.signature());
        m.put("signedAt", r.signedAt().toString());
        m.put("signingPayload", r.signingPayload());
        return m;
    }
}
