package com.frauddetection.alert.audit.read;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SensitiveReadAuditService {

    private final ReadAccessAuditService readAccessAuditService;
    private final SensitiveReadAuditPolicy policy;
    private final ReadAccessAuditClassifier classifier;

    public SensitiveReadAuditService(ReadAccessAuditService readAccessAuditService, SensitiveReadAuditPolicy policy) {
        this(readAccessAuditService, policy, new ReadAccessAuditClassifier());
    }

    @Autowired
    public SensitiveReadAuditService(
            ReadAccessAuditService readAccessAuditService,
            SensitiveReadAuditPolicy policy,
            ReadAccessAuditClassifier classifier
    ) {
        this.readAccessAuditService = readAccessAuditService;
        this.policy = policy;
        this.classifier = classifier;
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
        ReadAccessAuditTarget target = target(endpointCategory, resourceType, resourceId, request);
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

    private ReadAccessAuditTarget target(
            ReadAccessEndpointCategory endpointCategory,
            ReadAccessResourceType resourceType,
            String resourceId,
            HttpServletRequest request
    ) {
        ReadAccessAuditTarget classified = request == null ? null : classifier.classify(request).orElse(null);
        return new ReadAccessAuditTarget(
                endpointCategory,
                resourceType,
                normalize(resourceId),
                classified == null ? null : classified.queryHash(),
                classified == null ? null : classified.filterBucket(),
                classified == null ? null : classified.page(),
                classified == null ? null : classified.size()
        );
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
