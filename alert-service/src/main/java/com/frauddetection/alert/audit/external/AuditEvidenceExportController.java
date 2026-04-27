package com.frauddetection.alert.audit.external;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/evidence/export")
public class AuditEvidenceExportController {

    private final AuditEvidenceExportService service;

    public AuditEvidenceExportController(AuditEvidenceExportService service) {
        this.service = service;
    }

    @GetMapping
    public AuditEvidenceExportResponse exportEvidence(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "source_service") String sourceService,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean strict
    ) {
        return service.export(from, to, sourceService, limit, strict);
    }
}
