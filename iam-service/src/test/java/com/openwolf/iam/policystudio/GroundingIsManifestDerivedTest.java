package com.openwolf.iam.policystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openwolf.iam.tenancy.ActiveTenantDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 — the studio grounding vocabulary (resource kind, actions, roles, approved imports) and the base
 * ceiling are DERIVED from the tenant-effective Cerbos base policy bundle, not Java literals. A distinctive
 * action / role / derived-role written into the base bundle appears in the grounding; a literal that is NOT
 * in that bundle (e.g. {@code invoke}, {@code platform_admin}, {@code business_derived_roles}) does not.
 *
 * <p>This is the regression that would fail if any future edit re-hardcodes the agent vocabulary or ceiling
 * back into {@link ManifestBackedStudioGroundingProvider}.
 */
class GroundingIsManifestDerivedTest {

    @Test
    void vocabularyActionsRolesAndCeilingComeFromTheBaseBundleNotLiterals(@TempDir Path temp) throws IOException {
        Path baseBundle = Files.createDirectories(temp.resolve("policies"));
        Path tenantRoot = temp.resolve("tenants");
        StudioGroundingTestFixtures.writeTenantDeployment(tenantRoot, "acme");
        // A distinctive base bundle for a made-up kind: actions {frobnicate, inspect, manage}, a raw role
        // {overlord}, and a derived role {tenant_wizard → wizard} carrying the tenant-equality backstop.
        Files.writeString(baseBundle.resolve("widget_resource.yaml"), """
                apiVersion: api.cerbos.dev/v1
                resourcePolicy:
                  version: "default"
                  resource: widget
                  scope: ""
                  importDerivedRoles:
                    - widget_roles
                  rules:
                    - actions: ["frobnicate"]
                      effect: EFFECT_ALLOW
                      derivedRoles: ["tenant_wizard"]
                    - actions: ["inspect", "manage"]
                      effect: EFFECT_ALLOW
                      roles: ["overlord"]
                """);
        Files.writeString(baseBundle.resolve("widget_roles.yaml"), """
                apiVersion: api.cerbos.dev/v1
                derivedRoles:
                  name: widget_roles
                  definitions:
                    - name: tenant_wizard
                      parentRoles: ["wizard"]
                      condition:
                        match:
                          expr: >
                            (has(P.attr.tenant_id) && has(R.attr.tenant_id))
                              ? P.attr.tenant_id == R.attr.tenant_id : true
                """);
        Files.writeString(baseBundle.resolve("tenant_acme_widget.yaml"), """
                apiVersion: api.cerbos.dev/v1
                resourcePolicy:
                  version: "default"
                  resource: widget
                  scope: "acme"
                  scopePermissions: SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS
                  rules:
                    - actions: ["frobnicate"]
                      effect: EFFECT_ALLOW
                      roles: ["wizard"]
                """);

        ManifestBackedStudioGroundingProvider provider = new ManifestBackedStudioGroundingProvider(
                new ObjectMapper(),
                new CanonicalPolicyWriter(),
                new PolicyYamlParser(),
                freshDirectory(),
                StudioGroundingTestFixtures.emptyBundleRepository(),
                "registry",                       // real registry supplies data classifications
                baseBundle.toString(),            // the distinctive base bundle above
                tenantRoot.toString());

        StudioGroundingSnapshot snapshot = provider.snapshot("acme", "widget");
        ManifestVocabulary vocab = snapshot.vocabulary();
        BaseCeiling ceiling = snapshot.baseCeiling();

        // Resource kind + actions are parsed from the base policy, not the retired "agent" literals.
        assertThat(vocab.resourceKind()).isEqualTo("widget");
        assertThat(vocab.actions())
                .as("actions come from the base bundle rules")
                .contains("frobnicate", "inspect", "manage")
                .doesNotContain("invoke", "invoke_membership", "register", "deregister", "exfiltrate");

        // Roles = raw roles named for the kind ∪ every parentRole of an imported derived-role module.
        assertThat(vocab.roles())
                .as("a raw role and a derived-role parent both appear; unrelated agent roles do not")
                .contains("overlord", "wizard")
                .doesNotContain("platform_admin", "domain_admin", "chat_user", "conduit_admin");

        assertThat(vocab.approvedImports())
                .contains("widget_roles")
                .doesNotContain("business_derived_roles");

        // The ceiling tuples expand derivedRoles → parentRoles, so (frobnicate, wizard) is present.
        assertThat(ceiling.resourceKind()).isEqualTo("widget");
        assertThat(ceiling.tuples())
                .contains(new BaseCeiling.Tuple("frobnicate", "wizard"),
                        new BaseCeiling.Tuple("inspect", "overlord"),
                        new BaseCeiling.Tuple("manage", "overlord"))
                .doesNotContain(new BaseCeiling.Tuple("invoke", "platform_admin"));

        // The backstop flag is read from the imported module's condition, not hardcoded true.
        assertThat(ceiling.carriesTenantEqualityBackstop()).isTrue();
        assertThat(ceiling.reservedIdentities())
                .as("the immutable root is reserved; the tenant-owned child remains replaceable")
                .containsExactly("widget@");
    }

    @Test
    void backstopFlagIsFalseWhenTheBaseBundleImportsNoTenantEqualityModule(@TempDir Path baseBundle) throws IOException {
        Files.writeString(baseBundle.resolve("widget_resource.yaml"), """
                apiVersion: api.cerbos.dev/v1
                resourcePolicy:
                  version: "default"
                  resource: widget
                  scope: ""
                  rules:
                    - actions: ["frobnicate"]
                      effect: EFFECT_ALLOW
                      roles: ["overlord"]
                """);

        ManifestBackedStudioGroundingProvider provider = new ManifestBackedStudioGroundingProvider(
                new ObjectMapper(), new CanonicalPolicyWriter(), new PolicyYamlParser(),
                freshDirectory(), StudioGroundingTestFixtures.emptyBundleRepository(),
                "registry", baseBundle.toString(), "infra/cerbos/tenants");

        BaseCeiling ceiling = provider.snapshot("acme", "widget").baseCeiling();
        assertThat(ceiling.carriesTenantEqualityBackstop())
                .as("no imported derived-role module carries the tenant-equality backstop")
                .isFalse();
        assertThat(ceiling.tuples()).containsExactly(new BaseCeiling.Tuple("frobnicate", "overlord"));
    }

    /** A fresh B4 directory (empty in-memory snapshot) — the tenant resolves as unknown → base-only current. */
    private static ActiveTenantDirectory freshDirectory() {
        return StudioGroundingTestFixtures.emptyTenantDirectory();
    }
}
