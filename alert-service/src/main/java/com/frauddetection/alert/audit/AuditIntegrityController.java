package com.frauddetection.alert.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/integrity")
public class AuditIntegrityController {

    private final AuditIntegrityService auditIntegrityService;

    public AuditIntegrityController(AuditIntegrityService auditIntegrityService) {
        this.auditIntegrityService = auditIntegrityService;
    }

    @GetMapping
    public AuditIntegrityResponse verifyAuditIntegrity(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "source_service", required = false) String sourceService,
            @RequestParam(required = false) Integer limit
    ) {
        return auditIntegrityService.verify(from, to, sourceService, limit);
    }
}
