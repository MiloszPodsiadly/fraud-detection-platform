package com.frauddetection.alert.audit.external;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/integrity/external")
public class ExternalAuditIntegrityController {

    private final ExternalAuditIntegrityService service;

    public ExternalAuditIntegrityController(ExternalAuditIntegrityService service) {
        this.service = service;
    }

    @GetMapping
    public ExternalAuditIntegrityResponse verifyExternalIntegrity(
            @RequestParam(name = "source_service", required = false) String sourceService,
            @RequestParam(required = false) Integer limit
    ) {
        return service.verify(sourceService, limit);
    }

    @GetMapping("/coverage")
    public ExternalAuditAnchorCoverageResponse coverage(
            @RequestParam(name = "source_service", required = false) String sourceService,
            @RequestParam(required = false) Integer limit
    ) {
        return service.coverage(sourceService, limit);
    }
}
