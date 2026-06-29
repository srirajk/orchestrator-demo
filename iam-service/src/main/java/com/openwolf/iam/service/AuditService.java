package com.openwolf.iam.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openwolf.iam.entity.AuditLog;
import com.openwolf.iam.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Records immutable audit entries for every write operation in the IAM service.
 * <p>
 * CRITICAL: audit failures MUST NOT propagate to the caller — they are caught and logged
 * internally. An audit write error must never break the primary request.
 * </p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String TENANT_ID = "default";

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes an audit log entry. Never throws — all exceptions are swallowed and logged.
     *
     * @param tenantId     tenant this action belongs to
     * @param actorId      who performed the action (null = system)
     * @param action       what was done (e.g. "CREATE_USER", "ASSIGN_ROLE")
     * @param resourceType what kind of resource was affected (e.g. "user", "role")
     * @param resourceId   identifier of the affected resource
     * @param before       state before the change (null for creates)
     * @param after        state after the change (null for deletes)
     * @param req          HTTP request — used to capture source IP
     */
    public void log(String tenantId, String actorId, String action,
                    String resourceType, String resourceId,
                    Object before, Object after, HttpServletRequest req) {
        try {
            String beforeJson = toJson(before);
            String afterJson = toJson(after);
            String sourceIp = extractClientIp(req);
            String correlationId = req != null ? req.getHeader("X-Correlation-ID") : null;

            AuditLog entry = new AuditLog(
                    tenantId != null ? tenantId : TENANT_ID,
                    actorId,
                    "system",
                    action,
                    resourceType,
                    resourceId,
                    beforeJson,
                    afterJson,
                    sourceIp,
                    correlationId
            );
            auditLogRepository.save(entry);
        } catch (Exception ex) {
            // Never break the request — log and continue
            log.error("Failed to write audit log for action={} resource={}/{}: {}",
                    action, resourceType, resourceId, ex.getMessage(), ex);
        }
    }

    /**
     * Resolves the current actor's user ID from the Spring Security context.
     * Returns "system" if no authenticated principal is present.
     */
    public String currentActor() {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return "system";
            }
            Object principal = authentication.getPrincipal();
            if (principal instanceof Jwt jwt) {
                String sub = jwt.getSubject();
                return sub != null ? sub : "system";
            }
            return authentication.getName() != null ? authentication.getName() : "system";
        } catch (Exception ex) {
            log.warn("Could not resolve current actor from security context: {}", ex.getMessage());
            return "system";
        }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof String s) return s;
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise audit state to JSON: {}", ex.getMessage());
            return "{\"error\":\"serialisation_failed\"}";
        }
    }

    private String extractClientIp(HttpServletRequest req) {
        if (req == null) return null;
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
