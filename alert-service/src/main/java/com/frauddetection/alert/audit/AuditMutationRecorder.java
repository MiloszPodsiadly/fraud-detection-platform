package com.frauddetection.alert.audit;

import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class AuditMutationRecorder {

    private static final String BUSINESS_WRITE_FAILED = "BUSINESS_WRITE_FAILED";

    private final AuditService auditService;

    public AuditMutationRecorder(AuditService auditService) {
        this.auditService = auditService;
    }

    public <T> T record(
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String correlationId,
            String actorId,
            Supplier<T> operation
    ) {
        auditService.audit(action, resourceType, resourceId, correlationId, actorId, AuditOutcome.ATTEMPTED, null);
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException exception) {
            auditFailure(action, resourceType, resourceId, correlationId, actorId, exception);
            throw exception;
        } catch (Error error) {
            auditFailure(action, resourceType, resourceId, correlationId, actorId, error);
            throw error;
        }
        auditService.audit(action, resourceType, resourceId, correlationId, actorId, AuditOutcome.SUCCESS, null);
        return result;
    }

    private void auditFailure(
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String correlationId,
            String actorId,
            Throwable originalFailure
    ) {
        try {
            auditService.audit(action, resourceType, resourceId, correlationId, actorId, AuditOutcome.FAILED, BUSINESS_WRITE_FAILED);
        } catch (RuntimeException auditFailure) {
            originalFailure.addSuppressed(auditFailure);
        }
    }
}
