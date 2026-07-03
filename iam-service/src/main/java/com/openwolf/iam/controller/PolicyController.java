package com.openwolf.iam.controller;

import com.openwolf.iam.dto.CreatePolicyRequest;
import com.openwolf.iam.dto.PolicyResponse;
import com.openwolf.iam.service.PolicyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages Cerbos YAML policies: CRUD + generate/validate/apply lifecycle.
 * All endpoints under {@code /admin/policies} and the segments/policy-resources endpoints.
 */
@RestController
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    // -------------------------------------------------------
    // CRUD at /admin/policies
    // -------------------------------------------------------

    @GetMapping("/admin/policies")
    public ResponseEntity<List<PolicyResponse>> listPolicies(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(policyService.listPolicies(status));
    }

    @PostMapping("/admin/policies")
    public ResponseEntity<PolicyResponse> createPolicy(
            @Valid @RequestBody CreatePolicyRequest req,
            HttpServletRequest httpReq) {
        PolicyResponse policy = policyService.createPolicy(req, httpReq);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/admin/policies/{id}")
                .buildAndExpand(policy.id())
                .toUri();
        return ResponseEntity.created(location).body(policy);
    }

    @GetMapping("/admin/policies/{policyId}")
    public ResponseEntity<PolicyResponse> getPolicy(@PathVariable UUID policyId) {
        return ResponseEntity.ok(policyService.getPolicy(policyId));
    }

    @PutMapping("/admin/policies/{policyId}/status")
    public ResponseEntity<PolicyResponse> updateStatus(
            @PathVariable UUID policyId,
            @RequestBody Map<String, String> req,
            HttpServletRequest httpReq) {
        String newStatus = req.get("status");
        if (newStatus == null) {
            throw new IllegalArgumentException("status field is required");
        }
        return ResponseEntity.ok(policyService.updateStatus(policyId, newStatus, httpReq));
    }

    @DeleteMapping("/admin/policies/{policyId}")
    public ResponseEntity<Void> deletePolicy(
            @PathVariable UUID policyId,
            HttpServletRequest httpReq) {
        policyService.deletePolicy(policyId, httpReq);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------
    // LLM policy lifecycle
    // -------------------------------------------------------

    @PostMapping("/admin/policies/generate")
    public ResponseEntity<Map<String, Object>> generatePolicy(
            @RequestBody Map<String, Object> intent) {
        return ResponseEntity.ok(policyService.generatePolicy(intent));
    }

    @PostMapping("/admin/policies/validate")
    public ResponseEntity<Map<String, Object>> validatePolicy(
            @RequestBody Map<String, String> req) {
        String yaml = req.getOrDefault("yaml", req.get("content"));
        return ResponseEntity.ok(policyService.validatePolicy(yaml));
    }

    @PostMapping("/admin/policies/apply")
    public ResponseEntity<Map<String, Object>> applyPolicy(
            @RequestBody Map<String, String> req,
            HttpServletRequest httpReq) {
        String yaml = req.getOrDefault("yaml", req.get("content"));
        String filename = req.get("filename");
        return ResponseEntity.ok(policyService.applyPolicy(yaml, filename, httpReq));
    }

    // -------------------------------------------------------
    // Reference endpoints
    // -------------------------------------------------------

    @GetMapping("/admin/policy-resources")
    public ResponseEntity<Map<String, List<String>>> listPolicyResources() {
        return ResponseEntity.ok(Map.of(
                "resources", List.of("agent", "relationship", "domain", "user", "policy", "audit_log")
        ));
    }

    @GetMapping("/admin/segments")
    public ResponseEntity<Map<String, List<String>>> listSegments() {
        return ResponseEntity.ok(Map.of(
                "segments", List.of("wealth", "servicing", "insurance", "platform")
        ));
    }
}
