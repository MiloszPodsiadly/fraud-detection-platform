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
            readAccessAuditService.audit(target, ReadAccessAuditOutcome.SUCCESS, boundedResultCount, correlationId);
            return;
        }
        try {
            readAccessAuditService.auditOrThrow(target, ReadAccessAuditOutcome.SUCCESS, boundedResultCount, correlationId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sensitive read audit unavailable.");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
