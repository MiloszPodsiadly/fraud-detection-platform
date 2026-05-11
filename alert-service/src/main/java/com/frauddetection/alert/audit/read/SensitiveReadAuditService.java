package com.frauddetection.alert.audit.read;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SensitiveReadAuditService {

    private final ReadAccessAuditService readAccessAuditService;
    private final SensitiveReadAuditPolicy policy;

    public SensitiveReadAuditService(ReadAccessAuditService readAccessAuditService, SensitiveReadAuditPolicy policy) {
        this.readAccessAuditService = readAccessAuditService;
        this.policy = policy;
    }

    public void audit(
            ReadAccessEndpointCategory endpointCategory,
            ReadAccessResourceType resourceType,
            String resourceId,
            Integer resultCount,
            HttpServletRequest request
    ) {
        audit(endpointCategory, resourceType, resourceId, resultCount, ReadAccessAuditOutcome.SUCCESS, request);
    }

    public void auditAttempt(
            ReadAccessEndpointCategory endpointCategory,
            ReadAccessResourceType resourceType,
            String resourceId,
            ReadAccessAuditOutcome outcome,
            HttpServletRequest request
    ) {
        audit(endpointCategory, resourceType, resourceId, 0, outcome, request);
    }

    private void audit(
            ReadAccessEndpointCategory endpointCategory,
            ReadAccessResourceType resourceType,
            String resourceId,
            Integer resultCount,
            ReadAccessAuditOutcome outcome,
            HttpServletRequest request
    ) {
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(
                endpointCategory,
                resourceType,
                normalize(resourceId),
                null,
                null,
                null
        );
        int boundedResultCount = resultCount == null ? 0 : Math.max(0, Math.min(resultCount, 100));
        String correlationId = request == null ? null : request.getHeader("X-Correlation-Id");
        if (!policy.failClosed()) {
            readAccessAuditService.audit(target, outcome, boundedResultCount, correlationId);
            markAudited(request);
            return;
        }
        try {
            readAccessAuditService.auditOrThrow(target, outcome, boundedResultCount, correlationId);
            markAudited(request);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sensitive read audit unavailable.");
        }
    }

    private void markAudited(HttpServletRequest request) {
        if (request != null) {
            request.setAttribute(ReadAccessAuditResponseAdvice.AUDITED_ATTRIBUTE, Boolean.TRUE);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
