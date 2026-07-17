package com.openwolf.iam.policystudio;

import com.openwolf.iam.policystudio.api.StudioSessionStore;
import com.openwolf.iam.policystudio.lifecycle.BundleCanonicalizer;
import com.openwolf.iam.policystudio.lifecycle.PolicyBundleRepository;
import com.openwolf.iam.policystudio.lifecycle.SelfContainedBundleAssembler;
import com.openwolf.iam.policystudio.lifecycle.StudioBaselineActivationService;
import com.openwolf.iam.tenancy.ActiveTenantDirectory;
import com.openwolf.iam.tenancy.ActiveTenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * S5 — the consequence fixture matrix is no longer only same-tenant positives with empty attributes. For the
 * condition-gated front-door role it also carries an <b>attribute-removed</b>, a <b>cross-tenant</b>, a
 * <b>missing-attribute</b> and a <b>wrong-segment</b> cell, and the real-PDP consequence diff evaluates all
 * of them — so the over-permission alarm can catch a candidate that widens any of those negative classes.
 *
 * <p>End-to-end against a REAL pinned Cerbos: the four negative-class cells (plus the attributed positive)
 * are present in the matrix; a review evaluates them with real Cerbos; and against a base-only current the
 * positive is ALLOW while every negative is DENY (the semantics the alarm depends on).
 */
class ConsequenceMatrixCoversNegativeClassesTest {

    private static final String TENANT = "acme";

    /** A fresh tenant child (scope acme) that grants the front-door role invoke — a valid review candidate. */
    private static final String CANDIDATE_BODY = """
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
                - actions: ["invoke", "invoke_membership", "register", "deregister"]
                  effect: EFFECT_ALLOW
                  roles: ["domain_admin"]
                - actions: ["invoke", "invoke_membership"]
                  effect: EFFECT_ALLOW
                  roles: ["chat_user", "relationship_manager"]
            """;

    private ManifestBackedStudioGroundingProvider provider(ActiveTenantDirectory directory, String baseDir) {
        return new ManifestBackedStudioGroundingProvider(
                new ObjectMapper(), new CanonicalPolicyWriter(), new PolicyYamlParser(),
                directory, mock(PolicyBundleRepository.class),
                "registry", baseDir, "infra/cerbos/tenants", "default");
    }

    @Test
    void matrixCarriesTheFourNegativeClassesAndRealPdpEvaluatesThem() {
        String baseDir = infraBaseDir();
        ActiveTenantDirectory directory = new ActiveTenantDirectory(mock(ActiveTenantRepository.class));
        ManifestBackedStudioGroundingProvider grounding = provider(directory, baseDir);

        // ── the matrix contains all four negative classes (+ the attributed positive), by label ──
        StudioGroundingSnapshot snapshot = grounding.snapshot(TENANT, "agent");
        List<FixtureCell> cells = snapshot.matrix().cells();
        List<String> labels = cells.stream().map(FixtureCell::label).toList();

        assertThat(labels).anyMatch(l -> l.endsWith("::attribute_removed"));
        assertThat(labels).anyMatch(l -> l.endsWith("::cross_tenant"));
        assertThat(labels).anyMatch(l -> l.endsWith("::missing_attribute"));
        assertThat(labels).anyMatch(l -> l.endsWith("::wrong_segment"));
        assertThat(labels).anyMatch(l -> l.endsWith("::segment_positive"));
        // a genuine cross-tenant cell (principal tenant ≠ resource tenant) exists — not just same-tenant.
        assertThat(cells).anyMatch(FixtureCell::isCrossTenant);
        // the matrix is strictly richer than the same-tenant positives alone.
        assertThat(cells).anyMatch(c -> !c.resourceAttrs().isEmpty());

        // ── a review evaluates those cells with REAL Cerbos ──
        CerbosBatchDecisionSource cerbos = new CerbosBatchDecisionSource(
                "ghcr.io/cerbos/cerbos:0.53.0", 120, "0.53.0", baseDir, new CanonicalPolicyWriter());
        requireRealPdp(cerbos.isAvailable());
        ProductionPdpDecisionSource pdp = new ProductionPdpDecisionSource(cerbos);

        PolicyYamlParser parser = new PolicyYamlParser();
        CanonicalPolicyWriter writer = new CanonicalPolicyWriter();
        GroundedStudioReviewService reviews = new GroundedStudioReviewService(
                grounding, parser, writer, new ConsequenceDiffService(), pdp, noProse(),
                new BundleCanonicalizer(), new StudioSessionStore(),
                new StudioBaselineActivationService(directory), new SelfContainedBundleAssembler(baseDir));

        ConsequenceReview review = reviews.assembleAndReview(
                TENANT, "author-alice", "agent", CANDIDATE_BODY);

        // the truth source is the pinned Cerbos, and it decided the whole (expanded) matrix on both sides.
        assertThat(review.provenance().sourceId()).startsWith("cerbos:");
        Map<String, Effect> currentByCell = review.provenance().currentBatch().byCell();
        Map<String, Effect> candidateByCell = review.provenance().candidateBatch().byCell();
        assertThat(currentByCell.keySet())
                .as("real Cerbos evaluated every matrix cell against the current bundle")
                .containsAll(labels);
        assertThat(candidateByCell.keySet()).containsAll(labels);

        // ── the attributed cells carry the right base-PDP semantics (positive ALLOW, negatives DENY) ──
        String positive = labelEndingWith(labels, "::segment_positive");
        String crossTenant = labelEndingWith(labels, "::cross_tenant");
        String attributeRemoved = labelEndingWith(labels, "::attribute_removed");
        String missingAttribute = labelEndingWith(labels, "::missing_attribute");
        String wrongSegment = labelEndingWith(labels, "::wrong_segment");

        // current is a base-only bundle: the mapped-segment member is allowed; every negative is denied.
        assertThat(currentByCell.get(positive)).isEqualTo(Effect.ALLOW);
        assertThat(currentByCell.get(crossTenant)).isEqualTo(Effect.DENY);
        assertThat(currentByCell.get(attributeRemoved)).isEqualTo(Effect.DENY);
        assertThat(currentByCell.get(missingAttribute)).isEqualTo(Effect.DENY);
        assertThat(currentByCell.get(wrongSegment)).isEqualTo(Effect.DENY);
    }

    private static String labelEndingWith(List<String> labels, String suffix) {
        return labels.stream().filter(l -> l.endsWith(suffix)).findFirst().orElseThrow();
    }

    /** The repo's base policy dir, resolved from the iam-service module cwd (with a {@code ..} fallback). */
    private static String infraBaseDir() {
        Path direct = Path.of("infra/cerbos/policies");
        return Files.isDirectory(direct)
                ? direct.toString()
                : Path.of("..").resolve("infra/cerbos/policies").normalize().toString();
    }

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
            Assumptions.assumeTrue(available, "pinned Cerbos unavailable — skipping the negative-class matrix proof");
        }
    }
}
