package com.frauddetection.alert.audit.external;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/audit/integrity/external")
public class ExternalAuditIntegrityController {

    private final ExternalAuditIntegrityService service;
    private final ExternalAuditCoverageRateLimiter coverageRateLimiter;

    public ExternalAuditIntegrityController(
            ExternalAuditIntegrityService service,
            ExternalAuditCoverageRateLimiter coverageRateLimiter
    ) {
        this.service = service;
        this.coverageRateLimiter = coverageRateLimiter;
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
            @RequestParam(required = false) Integer limit,
            @RequestParam(name = "from_position", required = false) Long fromPosition
    ) {
        if (!coverageRateLimiter.allow()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "External audit coverage rate limit exceeded.");
        }
        return service.coverage(sourceService, limit, fromPosition);
    }
}
