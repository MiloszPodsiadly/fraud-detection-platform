package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
    private final AlertServiceMetrics metrics;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    ExternalAuditIntegrityController(
            ExternalAuditIntegrityService service,
            ExternalAuditCoverageRateLimiter coverageRateLimiter
    ) {
        this(service, coverageRateLimiter, new AlertServiceMetrics(new SimpleMeterRegistry()), null);
    }

    @Autowired
    public ExternalAuditIntegrityController(
            ExternalAuditIntegrityService service,
            ExternalAuditCoverageRateLimiter coverageRateLimiter,
            AlertServiceMetrics metrics,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.service = service;
        this.coverageRateLimiter = coverageRateLimiter;
        this.metrics = metrics;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @GetMapping
    @AuditedSensitiveRead
    public ExternalAuditIntegrityResponse verifyExternalIntegrity(
            @RequestParam(name = "source_service", required = false) String sourceService,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request
    ) {
        ExternalAuditIntegrityResponse response = service.verify(sourceService, limit);
        audit(ReadAccessEndpointCategory.EXTERNAL_AUDIT_INTEGRITY, ReadAccessResourceType.EXTERNAL_AUDIT_INTEGRITY, response.checked(), request);
        return response;
    }

    @GetMapping("/coverage")
    @AuditedSensitiveRead
    public ExternalAuditAnchorCoverageResponse coverage(
            @RequestParam(name = "source_service", required = false) String sourceService,
            @RequestParam(required = false) Integer limit,
            @RequestParam(name = "from_position", required = false) Long fromPosition,
            Authentication authentication,
            HttpServletRequest request
    ) {
        int cost = Math.max(1, Math.min(limit == null ? 100 : limit, 100));
        if (!coverageRateLimiter.allow(rateLimitIdentity(authentication, request), cost)) {
            metrics.recordExternalCoverageRequestCost("RATE_LIMITED", cost);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "External audit coverage rate limit exceeded.");
        }
        metrics.recordExternalCoverageRequestCost("ALLOWED", cost);
        ExternalAuditAnchorCoverageResponse response = service.coverage(sourceService, limit, fromPosition);
        audit(
                ReadAccessEndpointCategory.EXTERNAL_AUDIT_COVERAGE,
                ReadAccessResourceType.EXTERNAL_AUDIT_COVERAGE,
                response.missingRanges() == null ? 0 : response.missingRanges().size(),
                request
        );
        return response;
    }

    ExternalAuditAnchorCoverageResponse coverage(String sourceService, Integer limit, Long fromPosition) {
        return coverage(sourceService, limit, fromPosition, null, null);
    }

    private void audit(ReadAccessEndpointCategory category, ReadAccessResourceType resourceType, int count, HttpServletRequest request) {
        if (sensitiveReadAuditService != null) {
            sensitiveReadAuditService.audit(category, resourceType, null, count, request);
        }
    }

    private String rateLimitIdentity(Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            String authorities = authentication.getAuthorities().stream()
                    .map(Object::toString)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            return "principal:" + authentication.getName() + "|authorities:" + authorities;
        }
        String remoteAddr = request == null ? null : request.getRemoteAddr();
        return "ip:" + (remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr);
    }
}
