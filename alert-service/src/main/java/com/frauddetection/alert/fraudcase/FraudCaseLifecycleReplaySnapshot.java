package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCaseDecisionType;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;

public record FraudCaseLifecycleReplaySnapshot(
        FraudCaseLifecycleReplaySnapshotType snapshotType,
        String action,
        String caseId,
        String caseNumber,
        FraudCaseStatus status,
        FraudCasePriority priority,
        RiskLevel riskLevel,
        List<String> linkedAlertIds,
        String assignedTo,
        String createdBy,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String closureReason,
        Instant closedAt,
        String noteId,
        String noteBody,
        Boolean noteInternalOnly,
        String decisionId,
        FraudCaseDecisionType decisionType,
        String decisionSummary
) {
}
