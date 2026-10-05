package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class AuditMutationRecorder {

    private static final String BUSINESS_WRITE_FAILED = "BUSINESS_WRITE_FAILED";

    private final AuditService auditService;
    private final AuditDegradationService auditDegradationService;
    private final AlertServiceMetrics metrics;

    public AuditMutationRecorder(AuditService auditService) {
        this(auditService, null, null);
    }

    @Autowired
    public AuditMutationRecorder(
            AuditService auditService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics
    ) {
        this.auditService = auditService;
        this.auditDegradationService = auditDegradationService;
        this.metrics = metrics;
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
        try {
            auditService.audit(action, resourceType, resourceId, correlationId, actorId, AuditOutcome.SUCCESS, null);
        } catch (RuntimeException exception) {
            recordPostCommitDegradation(action, resourceType, resourceId);
            throw new PostCommitAuditDegradedException(result, exception);
        }
        return result;
    }

    public <T> T recordWithDurableSuccessIntent(
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String correlationId,
            String actorId,
            Supplier<T> transactionalOperationWithSuccessAuditIntent
    ) {
        auditService.audit(action, resourceType, resourceId, correlationId, actorId, AuditOutcome.ATTEMPTED, null);
        try {
            return transactionalOperationWithSuccessAuditIntent.get();
        } catch (RuntimeException exception) {
            auditFailure(action, resourceType, resourceId, correlationId, actorId, exception);
            throw exception;
        } catch (Error error) {
            auditFailure(action, resourceType, resourceId, correlationId, actorId, error);
            throw error;
        }
    }

    private void recordPostCommitDegradation(
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId
    ) {
        if (metrics != null) {
            metrics.recordPostCommitAuditDegraded(action == null ? "UNKNOWN" : action.name());
        }
        if (auditDegradationService == null) {
            return;
        }
        try {
            auditDegradationService.recordPostCommitDegraded(
                    action,
                    resourceType,
                    resourceId,
                    "SUCCESS_AUDIT_PERSISTENCE_FAILED"
            );
        } catch (RuntimeException ignored) {
            // Preserve the original audit failure while the metric keeps the degraded state visible.
        }
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
