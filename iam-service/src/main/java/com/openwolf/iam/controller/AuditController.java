package com.openwolf.iam.controller;

import com.openwolf.iam.dto.AuditLogResponse;
import com.openwolf.iam.dto.PageResponse;
import com.openwolf.iam.entity.AuditLog;
import com.openwolf.iam.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Paginated access to the audit log. All write operations in the IAM service
 * emit an entry — this endpoint is the read side.
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditController {

    private static final String TENANT_ID = "default";

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * {@code GET /admin/audit} — paginated audit log, newest first.
     */
    @GetMapping
    public ResponseEntity<PageResponse<AuditLogResponse>> getAuditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<AuditLog> auditPage = auditLogRepository
                .findByTenantIdOrderByOccurredAtDesc(TENANT_ID, PageRequest.of(page, size));
        Page<AuditLogResponse> responsePage = auditPage.map(this::toResponse);
        return ResponseEntity.ok(PageResponse.of(responsePage));
    }

    /**
     * {@code GET /admin/audit/export} — full export (no pagination) for download.
     */
    @GetMapping("/export")
    public ResponseEntity<List<AuditLogResponse>> exportAuditLog() {
        List<AuditLogResponse> all = auditLogRepository
                .findByTenantIdOrderByOccurredAtDesc(TENANT_ID)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(all);
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getTenantId(),
                log.getActorId(),
                log.getClientId(),
                log.getAction(),
                log.getResourceType(),
                log.getResourceId(),
                log.getBeforeState(),  // raw JSON string
                log.getAfterState(),   // raw JSON string
                log.getSourceIp(),
                log.getOccurredAt()
        );
    }
}
