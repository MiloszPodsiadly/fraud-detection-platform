package com.frauddetection.alert.system.trustlevel.health;

import com.frauddetection.alert.trust.TrustIncidentSummary;

public record LiveTrustSnapshot(
        String coverageStatus,
        String coverageReasonCode,
        String witnessStatus,
        int requiredPublicationFailures,
        int localStatusUnverified,
        int missingRanges,
        long postCommitAuditDegraded,
        long pendingDegradationResolutionCount,
        long postCommitAuditDegradedResolved,
        OutboxRecoveryHealthSnapshot outboxRecovery,
        TrustIncidentSummary incidentSummary
) {
}
