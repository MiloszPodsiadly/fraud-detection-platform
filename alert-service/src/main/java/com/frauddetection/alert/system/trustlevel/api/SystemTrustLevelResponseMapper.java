package com.frauddetection.alert.system.trustlevel.api;

import com.frauddetection.alert.system.trustlevel.application.SystemTrustLevel;

public class SystemTrustLevelResponseMapper {

    public SystemTrustLevelResponse map(SystemTrustLevel trustLevel) {
        return new SystemTrustLevelResponse(
                trustLevel.guaranteeLevel(),
                trustLevel.bankProfileStatus(),
                trustLevel.publicationEnabled(),
                trustLevel.publicationRequired(),
                trustLevel.failClosed(),
                trustLevel.externalAnchorStrength(),
                trustLevel.coverageStatus(),
                trustLevel.witnessStatus(),
                trustLevel.signaturePolicy(),
                trustLevel.requiredPublicationFailures(),
                trustLevel.localStatusUnverified(),
                trustLevel.missingRanges(),
                trustLevel.postCommitAuditDegraded(),
                trustLevel.unresolvedDegradationCount(),
                trustLevel.pendingDegradationResolutionCount(),
                trustLevel.postCommitAuditDegradedResolved(),
                trustLevel.outboxFailedTerminalCount(),
                trustLevel.outboxPendingCount(),
                trustLevel.outboxProcessingCount(),
                trustLevel.outboxPublishAttemptedCount(),
                trustLevel.outboxConfirmationUnknownCount(),
                trustLevel.outboxProjectionMismatchCount(),
                trustLevel.outboxProjectionReconciliationPendingCount(),
                trustLevel.outboxRecoveryRequiredCount(),
                trustLevel.terminalOutboxFailureCount(),
                trustLevel.outboxPublishConfirmationUnknownCount(),
                trustLevel.unknownOutboxConfirmationCount(),
                trustLevel.pendingOutboxResolutionCount(),
                trustLevel.outboxOldestPendingAgeSeconds(),
                trustLevel.outboxOldestAmbiguousAgeSeconds(),
                trustLevel.regulatedMutationRecoveryRequiredCount(),
                trustLevel.staleProcessingLeaseCount(),
                trustLevel.finalizeRecoveryRequiredCount(),
                trustLevel.evidenceConfirmationPendingCount(),
                trustLevel.repeatedRecoveryFailureCount(),
                trustLevel.oldestRecoveryRequiredAgeSeconds(),
                trustLevel.reasonCode(),
                trustLevel.transactionMode(),
                trustLevel.transactionCapabilityStatus(),
                trustLevel.outboxDeliveryMode(),
                trustLevel.evidenceConfirmationMode(),
                trustLevel.openCriticalIncidentCount(),
                trustLevel.openHighIncidentCount(),
                trustLevel.unacknowledgedCriticalIncidentCount(),
                trustLevel.oldestOpenIncidentAgeSeconds(),
                trustLevel.topIncidentTypes(),
                trustLevel.incidentHealthStatus()
        );
    }
}
