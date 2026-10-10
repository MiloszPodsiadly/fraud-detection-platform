package com.frauddetection.alert.system.trustlevel.health;

public record OutboxRecoveryHealthSnapshot(
        boolean outboxAvailable,
        long outboxPendingCount,
        long outboxProcessingCount,
        long outboxPublishAttemptedCount,
        long outboxFailedTerminalCount,
        long outboxRecoveryRequiredCount,
        long outboxPublishConfirmationUnknownCount,
        long outboxProjectionMismatchCount,
        long outboxProjectionReconciliationPendingCount,
        long outboxPendingResolutionCount,
        Long outboxOldestPendingAgeSeconds,
        Long outboxOldestAmbiguousAgeSeconds,
        boolean outboxStalePending,
        String outboxReasonCode,
        long regulatedMutationRecoveryRequiredCount,
        long staleProcessingLeaseCount,
        long finalizeRecoveryRequiredCount,
        long evidenceConfirmationPendingCount,
        long repeatedRecoveryFailureCount,
        Long oldestRecoveryRequiredAgeSeconds
) {
}
