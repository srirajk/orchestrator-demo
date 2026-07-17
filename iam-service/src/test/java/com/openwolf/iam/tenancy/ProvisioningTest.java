package com.openwolf.iam.tenancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Axiom B4.2 — a freshly provisioned tenant uses the EXACT B1 deny-all bootstrap posture and DENIES
 * every enumerated base-ceiling tuple, until a reviewed total restriction policy is promoted. Nothing
 * falls through the parental-consent inheritance.
 */
class ProvisioningTest {

    private static final String TENANT = "acme";
    private static final String PARENTAL_CONSENT = "SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS";

    @Test
    @SuppressWarnings("unchecked")
    void freshTenantBootstrapDeniesEverything(@TempDir Path staging) throws IOException {
        CerbosPolicyBootstrapAdapter policy = ProvisioningTestSupport.realPolicyAdapter(staging);
        TenantBootstrapBundle bundle = policy.stage(TENANT);

        assertThat(bundle.childByResource()).isNotEmpty();
        assertThat(bundle.policyVersion()).startsWith("pv_");

        Yaml yaml = new Yaml();
        for (Map.Entry<String, String> child : bundle.childByResource().entrySet()) {
            String resource = child.getKey();
            Map<String, Object> doc = yaml.load(child.getValue());
            Map<String, Object> rp = (Map<String, Object>) doc.get("resourcePolicy");

            // EXACT B1 posture: scope = tenant id, parental-consent, non-empty rules (never an empty policy).
            assertThat(rp.get("scope")).as("scope must be the tenant id").isEqualTo(TENANT);
            assertThat(rp.get("scopePermissions")).as("must use the B1 parental-consent posture")
                    .isEqualTo(PARENTAL_CONSENT);
            List<Map<String, Object>> rules = (List<Map<String, Object>>) rp.get("rules");
            assertThat(rules).as("a deny-all child is never empty (empty = fail-open)").isNotEmpty();

            // Every rule is EFFECT_DENY — the fresh tenant grants NOTHING.
            for (Map<String, Object> rule : rules) {
                assertThat(rule.get("effect")).as("every fresh-tenant rule must DENY").isEqualTo("EFFECT_DENY");
            }

            // The deny-all covers EXACTLY the base ceiling's enumerated (action, role) tuples — no gap,
            // so no tuple can fall through to the parent ALLOW.
            Set<String> denied = tuples(rules);
            Set<String> ceiling = ceilingTuples(resource);
            assertThat(denied).as("deny-all must cover every enumerated base tuple for '%s'", resource)
                    .isEqualTo(ceiling);
        }
        // The live Cerbos compile of the staged bundle is captured as B4.2 evidence out of band (it
        // needs the pinned image and an ephemeral docker run); the deterministic deny-all totality
        // above is the hermetic proof that the fresh tenant denies every enumerated base tuple.
    }

    @Test
    void provisionActivatesWithTheContentAddressedBootstrapVersion(@TempDir Path staging) {
        ActiveTenantDirectory directory = new ActiveTenantDirectory(ProvisioningTestSupport.activeRepo());
        TenantProvisioningService service = new TenantProvisioningService(
                ProvisioningTestSupport.opsRepo(), directory,
                ProvisioningTestSupport.realPolicyAdapterNoProbe(staging),
                new InProcessTenantNamespaceAdapter(), new InProcessRegistrySpaceAdapter(),
                new PersistentAuditPartitionAdapter(ProvisioningTestSupport.auditRepo()));

        ProvisioningResult result = service.provision(
                new ProvisioningRequest(TENANT, "Acme", "acme"), "key-fresh-1", "admin");

        assertThat(result.isActive()).isTrue();
        assertThat(result.policyVersion()).startsWith("pv_");
        assertThat(directory.find(TENANT)).contains(result.policyVersion());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Set<String> tuples(List<Map<String, Object>> rules) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> rule : rules) {
            for (Object action : (List<Object>) rule.get("actions")) {
                for (Object role : (List<Object>) rule.get("roles")) {
                    out.add(action + "@" + role);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> ceilingTuples(String resource) throws IOException {
        Path base = ProvisioningTestSupport.baseBundleDir();
        Yaml yaml = new Yaml();
        try (Stream<Path> files = Files.list(base)) {
            List<Path> defaults = files
                    .filter(p -> p.getFileName().toString().matches("tenant_default_.*\\.ya?ml"))
                    .toList();
            for (Path p : defaults) {
                Map<String, Object> doc = yaml.load(Files.readString(p, StandardCharsets.UTF_8));
                Map<String, Object> rp = (Map<String, Object>) doc.get("resourcePolicy");
                if (!resource.equals(String.valueOf(rp.get("resource")))) continue;
                List<Map<String, Object>> rules = new ArrayList<>();
                for (Object o : (List<Object>) rp.get("rules")) rules.add((Map<String, Object>) o);
                return tuples(rules);
            }
        }
        throw new IllegalStateException("no ceiling default policy for resource " + resource);
    }
}
