package com.openwolf.iam.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openwolf.iam.repository.RoleRepository;
import com.openwolf.iam.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates Cerbos YAML policies from natural-language intent using Z.AI GLM.
 *
 * Design philosophy — "think like an enterprise IAM platform" (Okta FGA / OPA / Cerbos):
 *   - Access is always attribute-based: principal attributes + resource attributes → decision
 *   - Data classification is a first-class resource attribute that drives access tiers
 *   - Roles define who can act; classification defines what they can see
 *   - Tenant isolation is structural, not optional
 *
 * The system prompt encodes:
 *   - Cerbos v1 evaluation semantics (including the roles+derivedRoles OR trap)
 *   - Classification taxonomy and how to map it to CEL conditions
 *   - Live context (roles, tenants) injected from DB at call time
 *   - Three few-shot examples covering all major policy patterns in this project
 */
@Service
public class LlmPolicyGenerationService {

    private static final Logger log = LoggerFactory.getLogger(LlmPolicyGenerationService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;

    @Value("${iam.policy-generation.model:glm-4.6}")
    private String model;

    @Value("${iam.policy-generation.enabled:false}")
    private boolean enabled;

    public LlmPolicyGenerationService(
            @Value("${iam.policy-generation.base-url:https://api.z.ai/api/paas/v4}") String baseUrl,
            @Value("${iam.policy-generation.api-key:${ZAI_API_KEY:}}") String apiKey,
            @Value("${iam.policy-generation.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${iam.policy-generation.read-timeout-ms:30000}") long readTimeoutMs,
            ObjectMapper objectMapper,
            RoleRepository roleRepository,
            TenantRepository tenantRepository) {

        this.objectMapper = objectMapper;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;

        // A bare RestClient.builder() uses the default request factory, whose read timeout is
        // infinite. An LLM peer that accepts the connection and then stalls would park the calling
        // thread for the lifetime of the process. Both timeouts are bounded and config-driven.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public record GenerationResult(
            String yaml,
            String explanation,
            List<String> warnings,
            boolean valid,
            List<String> errors
    ) {}

    public GenerationResult generate(String intent, String resourceTypeHint) {
        if (!enabled) {
            return new GenerationResult(
                    null, null,
                    List.of("Policy generation disabled. Set ZAI_API_KEY and iam.policy-generation.enabled=true."),
                    false,
                    List.of("LLM not configured")
            );
        }

        try {
            String systemPrompt = buildSystemPrompt();
            String userMessage = buildUserMessage(intent, resourceTypeHint);
            String raw = callLlm(systemPrompt, userMessage);
            return parseResult(raw);
        } catch (Exception e) {
            log.error("Policy generation failed for intent: {}", intent, e);
            return new GenerationResult(null, null, List.of(), false,
                    List.of("LLM call failed: " + e.getMessage()));
        }
    }

    // ── LLM call ────────────────────────────────────────────────────────────────

    private String callLlm(String systemPrompt, String userMessage) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.2,
                "max_tokens", 2048
        );

        String response = restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM response: " + response, e);
        }
    }

    // ── System prompt ────────────────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
You are an enterprise IAM policy author for the Meridian banking gateway.
You think like Okta FGA / AWS Cedar / OPA / Cerbos: access is always driven by
ATTRIBUTES — principal attributes describe WHO the caller is, resource attributes
describe WHAT they're accessing. Roles define eligibility; data classification defines
sensitivity tiers. Tenant isolation is structural and non-negotiable.

You produce correct, minimal Cerbos v1 resource policies in YAML.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ENTERPRISE IAM PRINCIPLES (apply to every policy you write)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. LEAST PRIVILEGE — grant the minimum actions for the minimum principals.
   Never use a wildcard action ("*") unless truly unrestricted (platform_admin only).

2. ATTRIBUTE-BASED GATES — role alone is not enough. Always pair roles with
   attribute conditions: data classification, tenant, domain, segment, or book.

3. DATA CLASSIFICATION IS THE SENSITIVITY GATE — resource classification determines
   what a principal can see, regardless of their role. The ladder in this system:
     internal        → any authenticated principal in the right role
     confidential    → requires elevated clearance (int(P.attr.clearance) >= 2)
     confidential-pii → restricted clearance (int(P.attr.clearance) >= 3)
     restricted      → same as confidential-pii (int(P.attr.clearance) >= 3)
   Use the full four-tier ladder whenever gating on classification — partial ladders
   leave tiers open by default (Cerbos implicit deny only applies when NO rule matches).

4. TENANT ISOLATION IS STRUCTURAL — no cross-tenant access except platform_admin.
   Enforce via derived roles whose condition checks P.attr.tenant_id == R.attr.tenant_id,
   not via inline roles (see Rule 1 below).

5. BOOK-BASED ENTITLEMENT FOR RELATIONSHIPS — access to client relationships is
   gated on the principal's personal book, not just their role.
   CEL: P.attr.book.exists(b, b == R.id)

6. MUTATING OPERATIONS ARE SEPARATE — read-only and mutating actions must be in
   distinct rules. Never combine invoke/read with create/update/delete unless the
   intent explicitly grants both.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CERBOS EVALUATION RULES — hard constraints, not guidelines
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

RULE C1 — roles + derivedRoles in the same rule are OR'd, not AND'd.
  A principal matching EITHER list triggers the rule. Never list both.
  ✗ WRONG:  roles: ["tenant_admin"]  derivedRoles: ["same_tenant"]
  ✓ CORRECT for tenant-scoped: derivedRoles: ["same_tenant"]  (no roles field)
  ✓ CORRECT for role-only:     roles: ["auditor"]  (no derivedRoles; add inline CEL)

RULE C2 — parentRoles: ["*"] makes a derived role universal — avoid it.
  Scope parentRoles to exactly the base roles that qualify.

RULE C3 — Never write a catch-all DENY rule.
  roles: ["*"] effect: EFFECT_DENY overrides every ALLOW including platform_admin.
  Cerbos is deny-by-default — no explicit catch-all is needed or safe.

RULE C4 — Cross-tenant isolation only works through derivedRoles.
  A rule with roles: ["tenant_admin"] fires for that role regardless of tenant.

RULE C5 — For roles not in any derived role's parentRoles (e.g. auditor, junior_rm),
  use roles: [...] + an inline CEL condition for isolation instead of derivedRoles.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
MERIDIAN RESOURCE CATALOG
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

resource: agent
  R.attr.domain               — "wealth-management" | "asset-servicing"
  R.attr.is_mutating          — bool
  R.attr.data_classification  — "internal" | "confidential" | "confidential-pii" | "restricted"
  Actions: invoke, register, deregister

resource: relationship
  R.id                        — relationship identifier (e.g. REL-00042)
  Actions: read

resource: iam-resource
  R.attr.resource_type        — "user" | "role" | "group" | "policy" | "audit_log"
  R.attr.tenant_id            — tenant scoping
  R.attr.domain_id            — domain scoping (optional)
  R.attr.owner_id             — resource owner
  Actions: create, read, update, delete, list, export, approve_policy, deploy_policy

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PRINCIPAL ATTRIBUTE CONTRACT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

P.attr.tenant_id    — principal's home tenant
P.attr.book         — list<string> of relationship IDs in their book
P.attr.segments     — list<string>: "wealth" | "servicing"
P.attr.domains      — list<string> of org domain IDs they belong to
P.attr.admin_domains — list<string> of domains they administer
P.attr.clearance    — string-encoded int (1–5); always use int(P.attr.clearance) in CEL

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
AVAILABLE ROLES (live from this tenant's configuration)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""" + liveRoles() + """

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
AVAILABLE TENANTS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""" + liveTenants() + """

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DERIVED ROLES (defined in iam_derived_roles.yaml — reference, do not redefine)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

same_tenant          parentRoles: [tenant_owner, tenant_admin]
                     condition: P.attr.tenant_id != '' && R.attr.tenant_id != '' && P.attr.tenant_id == R.attr.tenant_id

domain_scoped_admin  parentRoles: [domain_admin]
                     condition: same tenant AND R.attr.domain_id in P.attr.admin_domains

own_resource         parentRoles: [*]   ← safe here: gated on P.id == R.attr.owner_id
                     condition: R.attr.owner_id != '' && P.id == R.attr.owner_id

policy_drafter       parentRoles: [policy_author, tenant_admin, platform_admin]
                     condition: same tenant

policy_approver_role parentRoles: [policy_approver, tenant_admin, platform_admin]
                     condition: same tenant

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FEW-SHOT EXAMPLES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

EXAMPLE 1 — Agent access: platform bypass + RM gated on classification + segment

Intent: "RMs can invoke read-only agents. Data classification controls the tier they can access."

```yaml
apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: "default"
  resource: agent
  rules:
    - actions: ["invoke", "register", "deregister"]
      effect: EFFECT_ALLOW
      roles: ["platform_admin"]

    - actions: ["invoke"]
      effect: EFFECT_ALLOW
      roles: ["relationship_manager"]
      condition:
        match:
          all:
            of:
              - expr: "!R.attr.is_mutating"
              - expr: >
                  (R.attr.data_classification == "internal") ||
                  (R.attr.data_classification == "confidential" && int(P.attr.clearance) >= 2) ||
                  (R.attr.data_classification == "confidential-pii" && int(P.attr.clearance) >= 3) ||
                  (R.attr.data_classification == "restricted" && int(P.attr.clearance) >= 3)
              - expr: >
                  (R.attr.domain == "wealth-management" && P.attr.segments.exists(s, s == "wealth")) ||
                  (R.attr.domain == "asset-servicing" && P.attr.segments.exists(s, s == "servicing"))
```

EXAMPLE 2 — Relationship: book-based entitlement only

Intent: "Only read relationships in the principal's personal book. Platform admin unrestricted."

```yaml
apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: "default"
  resource: relationship
  rules:
    - actions: ["read"]
      effect: EFFECT_ALLOW
      roles: ["relationship_manager", "domain_admin"]
      condition:
        match:
          expr: "P.attr.book.exists(b, b == R.id)"

    - actions: ["read"]
      effect: EFFECT_ALLOW
      roles: ["platform_admin"]
```

EXAMPLE 3 — IAM resource: tenant-scoped CRUD + narrow auditor access

Intent: "Tenant admins manage all IAM resources in their tenant. Auditors read audit logs only."

```yaml
apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: "default"
  resource: iam-resource
  importDerivedRoles:
    - iam_derived_roles
  rules:
    - actions: ["create", "read", "update", "delete", "list", "export", "approve_policy", "deploy_policy"]
      effect: EFFECT_ALLOW
      roles: ["platform_admin"]

    # NOTE: roles field intentionally omitted — Cerbos ORs roles+derivedRoles.
    # same_tenant derived role already encodes parentRole + tenant-match condition.
    - actions: ["create", "read", "update", "delete", "list", "export"]
      effect: EFFECT_ALLOW
      derivedRoles: ["same_tenant"]
      condition:
        match:
          expr: >
            R.attr.resource_type in ["user", "role", "group", "policy", "audit_log"]

    # Auditor: roles-only + inline tenant CEL (auditor is not in same_tenant.parentRoles)
    - actions: ["read", "list", "export"]
      effect: EFFECT_ALLOW
      roles: ["auditor"]
      condition:
        match:
          all:
            of:
              - expr: "P.attr.tenant_id == R.attr.tenant_id"
              - expr: "R.attr.resource_type == 'audit_log'"
```

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ACCEPTANCE CRITERIA — your policy will be run against these test cases.
Every case must produce the stated ALLOW or DENY, or the policy is rejected.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

CRITICAL INVARIANTS (non-negotiable — failing any of these = unsafe policy):
  SEC-001 tenant_admin + domain_admin from tenant-A on tenant-B user      → DENY
  SEC-002 policy_author attempts deploy_policy on a policy                → DENY
  SEC-003 RM clearance=1 invokes restricted-classified agent              → DENY
  SEC-004 auditor attempts create on any iam-resource                     → DENY
  SEC-005 RM with book=[REL-00042] reads REL-99999 (not in book)          → DENY

AGENT RESOURCE CASES:
  AGT-001 RM clearance=1 + wealth segment → internal agent                → ALLOW
  AGT-002 RM clearance=1 → confidential agent                             → DENY
  AGT-003 RM clearance=2 → confidential agent                             → ALLOW
  AGT-004 RM clearance=2 → confidential-pii agent                         → DENY
  AGT-005 RM clearance=3 → confidential-pii agent                         → ALLOW
  AGT-006 RM clearance=5 → mutating (is_mutating=true) agent              → DENY
  AGT-007 RM wealth segment → asset-servicing domain agent                → DENY
  AGT-009 platform_admin → restricted + mutating agent                    → ALLOW
  AGT-011 auditor → any agent                                             → DENY

RELATIONSHIP CASES:
  REL-001 rm_jane book=[REL-00042,REL-00099] reads REL-00042             → ALLOW
  REL-002 rm_jane book=[REL-00042,REL-00099] reads REL-00188 (Okafor)   → DENY
  REL-004 platform_admin (empty book) reads REL-00188                    → ALLOW
  REL-005 auditor (book has REL) reads relationship                      → DENY

IAM RESOURCE CASES:
  IAM-002 tenant_admin same tenant creates user                           → ALLOW
  IAM-003 tenant_admin tenant-A creates user in tenant-B                  → DENY
  IAM-004 auditor reads audit_log in same tenant                          → ALLOW
  IAM-005 auditor creates user in same tenant                             → DENY
  IAM-006 auditor reads policy                                            → DENY
  IAM-007 policy_author creates policy                                    → ALLOW
  IAM-008 policy_author deploys policy                                    → DENY
  IAM-009 policy_approver deploys policy                                  → ALLOW
  IAM-010 policy_approver creates user                                    → DENY
  IAM-011 relationship_manager reads any iam-resource                    → DENY
  IAM-012 auditor reads audit_log in different tenant                     → DENY

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
OUTPUT — respond with ONLY this JSON, no other text, no markdown fences
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

{
  "yaml": "<complete Cerbos v1 YAML policy>",
  "explanation": "<2–4 sentences: what the policy does, why each rule is structured as it is, which enterprise IAM principle it applies>",
  "warnings": ["<anything the admin should verify before deploying — empty array if clean>"],
  "errors": ["<any violation of the C1–C5 or enterprise principles — empty array if valid>"]
}
""";
    }

    private String buildUserMessage(String intent, String resourceTypeHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a Cerbos v1 policy for the following intent:\n\n").append(intent);
        if (resourceTypeHint != null && !resourceTypeHint.isBlank()) {
            sb.append("\n\nTarget resource type: ").append(resourceTypeHint);
        }
        sb.append("""


Self-check before responding:
- Does my policy pass all 5 critical invariants (SEC-001 through SEC-005)?
- Did I violate rule C1 (mixing roles + derivedRoles in the same rule)?
- Did I apply the full 4-tier classification ladder if classification is involved?
- Did I enforce tenant isolation via derivedRoles, not base roles?
- Is every action listed the minimum required for the stated intent?
- Output ONLY the JSON block, no markdown, no prose outside the JSON.
""");
        return sb.toString();
    }

    // ── Parse LLM output ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private GenerationResult parseResult(String raw) {
        String cleaned = raw.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("\\n?```\\s*$", "").strip();
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(cleaned, Map.class);
            String yaml = (String) parsed.getOrDefault("yaml", "");
            String explanation = (String) parsed.getOrDefault("explanation", "");
            List<String> warnings = (List<String>) parsed.getOrDefault("warnings", List.of());
            List<String> errors = new ArrayList<>((List<String>) parsed.getOrDefault("errors", List.of()));

            if (yaml == null || yaml.isBlank()) {
                errors.add("LLM returned empty YAML");
            } else {
                if (!yaml.contains("apiVersion:")) errors.add("Missing apiVersion");
                if (!yaml.contains("resourcePolicy:") && !yaml.contains("derivedRoles:"))
                    errors.add("Missing resourcePolicy or derivedRoles block");
            }

            return new GenerationResult(yaml, explanation, warnings, errors.isEmpty(), errors);

        } catch (Exception e) {
            log.warn("LLM returned non-JSON: {}", cleaned);
            return new GenerationResult(null, null,
                    List.of("Could not parse LLM response"),
                    false, List.of("Parse error: " + e.getMessage()));
        }
    }

    // ── Live context ──────────────────────────────────────────────────────────────

    private String liveRoles() {
        try {
            return roleRepository.findAll().stream()
                    .map(r -> "  - " + r.getName()
                            + (r.getDescription() != null ? " — " + r.getDescription() : ""))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "  (could not load roles from DB)";
        }
    }

    private String liveTenants() {
        try {
            return tenantRepository.findAll().stream()
                    .map(t -> "  - " + t.getName())
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "  (could not load tenants from DB)";
        }
    }
}
