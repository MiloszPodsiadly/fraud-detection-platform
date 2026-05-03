package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/evidence/export")
public class AuditEvidenceExportController {

    private final AuditEvidenceExportService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public AuditEvidenceExportController(AuditEvidenceExportService service, SensitiveReadAuditService sensitiveReadAuditService) {
        this.service = service;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @GetMapping
    @AuditedSensitiveRead
    public AuditEvidenceExportResponse exportEvidence(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "source_service") String sourceService,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean strict,
            HttpServletRequest request
    ) {
        AuditEvidenceExportResponse response = service.export(from, to, sourceService, limit, strict);
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.AUDIT_EVIDENCE_EXPORT,
                ReadAccessResourceType.AUDIT_EVIDENCE_EXPORT,
                null,
                response.count(),
                request
        );
        return response;
    }
}
