package com.frauddetection.alert.system.trustlevel.health;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class OutboxRecoveryHealthCollector {

    private final TransactionalOutboxRecordRepository outboxRepository;
    private final RegulatedMutationRecoveryService recoveryService;
    private final Duration staleOutboxThreshold;

    @Autowired
    public OutboxRecoveryHealthCollector(
            ObjectProvider<TransactionalOutboxRecordRepository> outboxRepository,
            RegulatedMutationRecoveryService recoveryService,
            @Value("${app.outbox.stale-threshold:PT10M}") Duration staleOutboxThreshold
    ) {
        this(
                outboxRepository == null ? null : outboxRepository.getIfAvailable(),
                recoveryService,
                staleOutboxThreshold
        );
    }

    public OutboxRecoveryHealthCollector(
            TransactionalOutboxRecordRepository outboxRepository,
            RegulatedMutationRecoveryService recoveryService,
            Duration staleOutboxThreshold
    ) {
        this.outboxRepository = outboxRepository;
        this.recoveryService = recoveryService;
        this.staleOutboxThreshold = staleOutboxThreshold == null ? Duration.ofMinutes(10) : staleOutboxThreshold;
    }

    public OutboxRecoveryHealthSnapshot collect() {
        long recoveryRequired = recoveryService == null ? 0L : recoveryService.recoveryRequiredCount();
        long staleProcessingLease = recoveryService == null ? 0L : recoveryService.staleProcessingLeaseCount();
        long finalizeRecoveryRequired = recoveryService == null ? 0L : recoveryService.finalizeRecoveryRequiredCount();
        long evidenceConfirmationPending = recoveryService == null ? 0L : recoveryService.evidenceConfirmationPendingCount();
        long repeatedRecoveryFailure = recoveryService == null ? 0L : recoveryService.repeatedRecoveryFailureCount();
        Long oldestRecoveryRequiredAge = recoveryService == null ? null : recoveryService.oldestRecoveryRequiredAgeSeconds();
        OutboxState outbox = outboxState();
        return new OutboxRecoveryHealthSnapshot(
                outbox.available(),
                outbox.pendingCount(),
                outbox.processingCount(),
                outbox.publishAttemptedCount(),
                outbox.failedTerminalCount(),
                outbox.recoveryRequiredCount(),
                outbox.publishConfirmationUnknownCount(),
                outbox.projectionMismatchCount(),
                outbox.projectionReconciliationPendingCount(),
                outbox.pendingResolutionCount(),
                outbox.oldestPendingAgeSeconds(),
                outbox.oldestAmbiguousAgeSeconds(),
                outbox.stalePending(),
                outbox.reasonCode(),
                recoveryRequired,
                staleProcessingLease,
                finalizeRecoveryRequired,
                evidenceConfirmationPending,
                repeatedRecoveryFailure,
                oldestRecoveryRequiredAge
        );
    }

    private OutboxState outboxState() {
        if (outboxRepository == null) {
            return OutboxState.unavailable();
        }
        try {
            return transactionalOutboxState();
        } catch (DataAccessException exception) {
            return OutboxState.unavailable();
        }
    }

    private OutboxState transactionalOutboxState() {
        List<TransactionalOutboxStatus> pendingStatuses = List.of(
                TransactionalOutboxStatus.PENDING,
                TransactionalOutboxStatus.PROCESSING,
                TransactionalOutboxStatus.FAILED_RETRYABLE
        );
        long failedTerminalCount = outboxRepository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL);
        long pendingCount = outboxRepository.countByStatus(TransactionalOutboxStatus.PENDING);
        long processingCount = outboxRepository.countByStatus(TransactionalOutboxStatus.PROCESSING);
        long publishAttemptedCount = outboxRepository.countByStatus(TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        long unknownCount = outboxRepository.countByStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        long recoveryRequiredCount = outboxRepository.countByStatus(TransactionalOutboxStatus.RECOVERY_REQUIRED);
        long projectionMismatchCount = outboxRepository.countByProjectionMismatchTrue();
        long projectionReconciliationPendingCount = outboxRepository.countByProjectionReconcileAfterIsNotNull();
        long pendingResolutionCount = outboxRepository.countByResolutionPendingTrue();
        Long oldestPendingAge = outboxRepository.findTopByStatusInOrderByCreatedAtAsc(pendingStatuses)
                .map(document -> {
                    Instant created = document.getCreatedAt();
                    return created == null ? 0L : Math.max(0L, Duration.between(created, Instant.now()).toSeconds());
                })
                .orElse(null);
        boolean stalePending = oldestPendingAge != null && oldestPendingAge > staleOutboxThreshold.toSeconds();
        String reason = null;
        if (failedTerminalCount > 0) {
            reason = "OUTBOX_TERMINAL_FAILURE";
        } else if (recoveryRequiredCount > 0) {
            reason = "OUTBOX_RECOVERY_REQUIRED";
        } else if (projectionMismatchCount > 0) {
            reason = "OUTBOX_PROJECTION_MISMATCH";
        } else if (projectionReconciliationPendingCount > 0) {
            reason = "OUTBOX_PROJECTION_RECONCILIATION_PENDING";
        } else if (publishAttemptedCount > 0) {
            reason = "OUTBOX_PUBLISH_ATTEMPT_CONFIRMATION_PENDING";
        } else if (unknownCount > 0) {
            reason = "OUTBOX_PUBLISH_CONFIRMATION_UNKNOWN";
        } else if (pendingResolutionCount > 0) {
            reason = "OUTBOX_RESOLUTION_PENDING_APPROVAL";
        } else if (stalePending) {
            reason = "OUTBOX_STALE_PENDING";
        }
        return new OutboxState(
                true,
                pendingCount,
                processingCount,
                publishAttemptedCount,
                failedTerminalCount,
                recoveryRequiredCount,
                unknownCount,
                projectionMismatchCount,
                projectionReconciliationPendingCount,
                pendingResolutionCount,
                oldestPendingAge,
                null,
                stalePending,
                reason
        );
    }

    private record OutboxState(
            boolean available,
            long pendingCount,
            long processingCount,
            long publishAttemptedCount,
            long failedTerminalCount,
            long recoveryRequiredCount,
            long publishConfirmationUnknownCount,
            long projectionMismatchCount,
            long projectionReconciliationPendingCount,
            long pendingResolutionCount,
            Long oldestPendingAgeSeconds,
            Long oldestAmbiguousAgeSeconds,
            boolean stalePending,
            String reasonCode
    ) {
        private static OutboxState unavailable() {
            return new OutboxState(
                    false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    null, null, false, "OUTBOX_STATUS_UNAVAILABLE"
            );
        }
    }
}
