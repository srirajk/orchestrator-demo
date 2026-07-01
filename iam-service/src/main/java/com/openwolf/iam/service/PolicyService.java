package com.openwolf.iam.service;

import com.openwolf.iam.dto.CreatePolicyRequest;
import com.openwolf.iam.dto.PolicyResponse;
import com.openwolf.iam.entity.Policy;
import com.openwolf.iam.exception.EntityNotFoundException;
import com.openwolf.iam.exception.ResourceConflictException;
import com.openwolf.iam.repository.PolicyRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    private static final String TENANT_ID = "default";

    private final PolicyRepository policyRepository;
    private final AuditService auditService;
    private final LlmPolicyGenerationService llmPolicyGenerationService;

    public PolicyService(PolicyRepository policyRepository,
                         AuditService auditService,
                         LlmPolicyGenerationService llmPolicyGenerationService) {
        this.policyRepository = policyRepository;
        this.auditService = auditService;
        this.llmPolicyGenerationService = llmPolicyGenerationService;
    }

    // -------------------------------------------------------
    // CRUD
    // -------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PolicyResponse> listPolicies(String statusFilter) {
        List<Policy> policies = statusFilter != null
                ? policyRepository.findByTenantIdAndStatus(TENANT_ID, statusFilter)
                : policyRepository.findByTenantId(TENANT_ID);
        return policies.stream().map(this::toPolicyResponse).toList();
    }

    @Transactional(readOnly = true)
    public PolicyResponse getPolicy(UUID policyId) {
        Policy p = policyRepository.findById(policyId)
                .orElseThrow(() -> EntityNotFoundException.forId("Policy", policyId));
        return toPolicyResponse(p);
    }

    public PolicyResponse createPolicy(CreatePolicyRequest req, HttpServletRequest httpReq) {
        if (policyRepository.findByTenantIdAndName(TENANT_ID, req.name()).isPresent()) {
            throw new ResourceConflictException("Policy already exists: " + req.name());
        }
        Policy p = new Policy(TENANT_ID, req.name(), req.resourceType(), req.content());
        policyRepository.save(p);
        PolicyResponse response = toPolicyResponse(p);
        auditService.log(TENANT_ID, auditService.currentActor(),
                "CREATE_POLICY", "policy", p.getId().toString(), null, response, httpReq);
        return response;
    }

    /**
     * Transitions a policy status: draft → approved → deployed.
     */
    public PolicyResponse updateStatus(UUID policyId, String newStatus,
                                       HttpServletRequest httpReq) {
        Policy p = policyRepository.findById(policyId)
                .orElseThrow(() -> EntityNotFoundException.forId("Policy", policyId));

        if (!List.of("draft", "approved", "deployed").contains(newStatus)) {
            throw new IllegalArgumentException("Invalid policy status: " + newStatus);
        }

        PolicyResponse before = toPolicyResponse(p);
        p.setStatus(newStatus);
        policyRepository.save(p);
        PolicyResponse after = toPolicyResponse(p);
        auditService.log(TENANT_ID, auditService.currentActor(),
                "UPDATE_POLICY_STATUS", "policy", policyId.toString(), before, after, httpReq);
        return after;
    }

    public void deletePolicy(UUID policyId, HttpServletRequest httpReq) {
        Policy p = policyRepository.findById(policyId)
                .orElseThrow(() -> EntityNotFoundException.forId("Policy", policyId));
        PolicyResponse before = toPolicyResponse(p);
        policyRepository.delete(p);
        auditService.log(TENANT_ID, auditService.currentActor(),
                "DELETE_POLICY", "policy", policyId.toString(), before, null, httpReq);
    }

    // -------------------------------------------------------
    // LLM Policy Generation / Validation / Apply
    // -------------------------------------------------------

    /**
     * Generates a Cerbos policy from a PolicyIntent (UI form) or a free-text intent.
     *
     * Accepts two shapes:
     *   Structured (from the Admin UI form):
     *     { resource, subject_roles[], actions[], conditions{...}, policy_name, description }
     *   Free-text (from API or curl):
     *     { intent: "natural language description", resourceType: "agent" }
     *
     * The structured form is converted into a rich natural-language prompt that gives
     * the LLM precise, unambiguous constraints to work from.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generatePolicy(Map<String, Object> body) {
        String resourceTypeHint = (String) body.getOrDefault("resource",
                body.getOrDefault("resourceType", null));

        // Build intent text — prefer explicit intent field, else synthesise from struct
        String intentText;
        if (body.containsKey("intent") && !((String) body.get("intent")).isBlank()) {
            intentText = (String) body.get("intent");
        } else {
            intentText = buildIntentFromStruct(body);
        }

        if (intentText == null || intentText.isBlank()) {
            Map<String, Object> result = new HashMap<>();
            result.put("valid", false);
            result.put("errors", List.of("Provide either 'intent' (free text) or a structured PolicyIntent body"));
            result.put("yaml", null);
            result.put("explanation", null);
            result.put("warnings", List.of());
            return result;
        }

        LlmPolicyGenerationService.GenerationResult generated =
                llmPolicyGenerationService.generate(intentText, resourceTypeHint);

        Map<String, Object> result = new HashMap<>();
        result.put("yaml", generated.yaml());
        result.put("explanation", generated.explanation());
        result.put("warnings", generated.warnings());
        result.put("valid", generated.valid());
        result.put("errors", generated.errors());
        return result;
    }

    /**
     * Converts the UI's structured PolicyIntent form into a precise natural-language
     * intent string that the LLM can reason about unambiguously.
     */
    @SuppressWarnings("unchecked")
    private String buildIntentFromStruct(Map<String, Object> body) {
        String resource = (String) body.getOrDefault("resource", "");
        List<String> roles = (List<String>) body.getOrDefault("subject_roles", List.of());
        List<String> actions = (List<String>) body.getOrDefault("actions", List.of());
        String description = (String) body.getOrDefault("description", "");
        String policyName = (String) body.getOrDefault("policy_name", "");

        Map<String, Object> conditions = (Map<String, Object>) body.getOrDefault("conditions", Map.of());
        Number clearanceMin = (Number) conditions.getOrDefault("clearance_min", null);
        List<String> segments = (List<String>) conditions.getOrDefault("segments", List.of());
        Boolean nonMutatingOnly = (Boolean) conditions.getOrDefault("non_mutating_only", false);
        String customCel = (String) conditions.getOrDefault("custom_cel", "");

        if (resource.isBlank() && roles.isEmpty() && actions.isEmpty()) {
            return description; // fall back to raw description if struct is empty
        }

        StringBuilder sb = new StringBuilder();
        if (!description.isBlank()) sb.append(description).append("\n\n");
        sb.append("Policy name: ").append(policyName.isBlank() ? "generated-policy" : policyName).append("\n");
        if (!resource.isBlank()) sb.append("Target resource: ").append(resource).append("\n");
        if (!roles.isEmpty()) sb.append("Roles that should have access: ").append(String.join(", ", roles)).append("\n");
        if (!actions.isEmpty()) sb.append("Actions to allow: ").append(String.join(", ", actions)).append("\n");
        if (clearanceMin != null && clearanceMin.intValue() > 0)
            sb.append("Minimum clearance level required: ").append(clearanceMin).append("\n");
        if (!segments.isEmpty())
            sb.append("Business segments that apply: ").append(String.join(", ", segments)).append("\n");
        if (Boolean.TRUE.equals(nonMutatingOnly))
            sb.append("Only read-only (non-mutating) operations are permitted.\n");
        if (customCel != null && !customCel.isBlank())
            sb.append("Additional CEL condition to include: ").append(customCel).append("\n");

        return sb.toString().strip();
    }

    /**
     * Validates a Cerbos YAML policy string.
     * Performs structural checks without calling Cerbos PDP.
     */
    public Map<String, Object> validatePolicy(String yaml) {
        List<String> errors = new ArrayList<>();

        if (yaml == null || yaml.isBlank()) {
            errors.add("Policy YAML is empty");
        } else {
            if (!yaml.contains("apiVersion:")) {
                errors.add("Missing required field: apiVersion");
            }
            if (!yaml.contains("resourcePolicy:") && !yaml.contains("derivedRoles:")) {
                errors.add("Policy must contain either resourcePolicy or derivedRoles block");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        return result;
    }

    /**
     * Applies (deploys) a policy by persisting it with status=deployed.
     */
    public Map<String, Object> applyPolicy(String yaml, String filename,
                                           HttpServletRequest httpReq) {
        // Validate before applying
        Map<String, Object> validation = validatePolicy(yaml);
        if (!(boolean) validation.get("valid")) {
            throw new IllegalArgumentException("Policy is invalid: " + validation.get("errors"));
        }

        String policyName = filename != null ? filename.replace(".yaml", "").replace(".yml", "") :
                "applied-" + System.currentTimeMillis();

        Policy p = policyRepository.findByTenantIdAndName(TENANT_ID, policyName)
                .orElseGet(() -> new Policy(TENANT_ID, policyName, null, yaml));
        p.setContent(yaml);
        p.setStatus("deployed");
        policyRepository.save(p);

        auditService.log(TENANT_ID, auditService.currentActor(),
                "APPLY_POLICY", "policy", p.getId().toString(), null,
                Map.of("name", policyName, "status", "deployed"), httpReq);

        Map<String, Object> result = new HashMap<>();
        result.put("applied", true);
        result.put("path", filename != null ? filename : policyName + ".yaml");
        result.put("policyId", p.getId().toString());
        return result;
    }

    // -------------------------------------------------------
    // Internal
    // -------------------------------------------------------

    private PolicyResponse toPolicyResponse(Policy p) {
        return new PolicyResponse(
                p.getId(),
                p.getName(),
                p.getResourceType(),
                p.getContent(),
                p.getStatus(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
