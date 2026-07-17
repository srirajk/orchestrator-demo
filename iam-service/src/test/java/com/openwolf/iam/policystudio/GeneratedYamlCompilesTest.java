package com.openwolf.iam.policystudio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2.2 — every generation that reaches storage {@code cerbos compile}s against the exact immutable
 * base bundle, or is rejected before storage. Drives the full pipeline (stub model client → parse →
 * deterministic gate → canonicalise → compile) over the corpus and asserts the invariant:
 * <b>accepted ⇒ compiles; rejected ⇒ nothing stored.</b>
 *
 * <p>Uses the SAME pinned Cerbos as the runtime PDP via an ephemeral {@code docker run --rm}
 * (no name, no port → never touches the running {@code conduit-cerbos}). Skips (assumeTrue) on a
 * Cerbos-less box; the deterministic gate is exercised without Cerbos by the other suites.
 */
class GeneratedYamlCompilesTest {

    private final PolicyYamlParser parser = new PolicyYamlParser();
    private final GeneratedPolicyValidator validator = new GeneratedPolicyValidator();
    private final CanonicalPolicyWriter writer = new CanonicalPolicyWriter();
    private final CerbosCompileGate gate = new CerbosCompileGate("ghcr.io/cerbos/cerbos:0.53.0", 90);

    @BeforeEach
    void requireCerbos() {
        Assumptions.assumeTrue(gate.isAvailable(),
                "no local cerbos binary or docker — skipping the compile-gate suite");
    }

    private PolicyStudioGenerationService service(PolicyAuthoringModelClient stub) {
        return new PolicyStudioGenerationService(
                stub, parser, validator, writer, gate,
                PolicyStudioFixtures.baseBundleDir().toString());
    }

    private PolicyAuthoringRequest acme() {
        return PolicyStudioFixtures.request("Restrict acme agents.", TenantScope.of("acme"), false);
    }

    @Test
    void everyGenerationCerbosCompiles() {
        // The whole corpus: one valid proposal + several adversarial ones.
        List<String> corpus = List.of(
                PolicyStudioFixtures.compliantAcme(),          // valid
                PolicyStudioFixtures.scopeEscapeToTenantB(),   // scope escape
                PolicyStudioFixtures.outOfVocabularyAction(),  // ungrounded action
                PolicyStudioFixtures.omitsBaseTuple(),         // fall-through hole
                PolicyStudioFixtures.wildcardGrant(),          // wildcard grant
                PolicyStudioFixtures.aliasVector(),            // YAML alias
                PolicyStudioFixtures.customTag(),              // custom tag
                PolicyStudioFixtures.derivedRolesDocument(),   // wrong document shape
                PolicyStudioFixtures.rootScopeRewrite());      // edits the base ceiling

        for (String proposal : corpus) {
            StudioGenerationResult result = service(PolicyStudioFixtures.stubReturning(proposal)).generate(acme());
            if (result.accepted()) {
                // INVARIANT: anything stored must compile. Re-compile the stored canonical form.
                assertThat(result.canonicalYaml()).isNotNull();
                CerbosCompileGate.CompileOutcome outcome = gate.compile(
                        result.canonicalYaml(), PolicyStudioFixtures.baseBundleDir(),
                        "generated_agent_acme.yaml");
                assertThat(outcome.success())
                        .as("accepted candidate must cerbos-compile: %s", outcome.output())
                        .isTrue();
            } else {
                // INVARIANT: a rejected proposal stores nothing.
                assertThat(result.canonicalYaml()).isNull();
            }
        }
    }

    @Test
    void acceptedGoldenCandidateCompilesAndIsStoredCanonical() {
        StudioGenerationResult result =
                service(PolicyStudioFixtures.stubReturning(PolicyStudioFixtures.compliantAcme())).generate(acme());

        assertThat(result.accepted()).isTrue();
        assertThat(result.stage()).isEqualTo(StudioGenerationResult.Stage.ACCEPTED);
        // Stored form is the IR-derived canonical YAML, not the model's raw text.
        assertThat(result.canonicalYaml()).contains("resource: agent").contains("scope: acme");
    }
}
