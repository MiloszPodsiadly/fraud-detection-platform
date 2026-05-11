package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;

public record FraudCaseWorkQueueItemResponse(
        String caseId,
        String caseNumber,
        FraudCaseStatus status,
        FraudCasePriority priority,
        RiskLevel riskLevel,
        String assignedInvestigatorId,
        Instant createdAt,
        Instant updatedAt,
        Long caseAgeSeconds,
        Long lastUpdatedAgeSeconds,
        FraudCaseSlaStatus slaStatus,
        Instant slaDeadlineAt,
        int linkedAlertCount
) {
}
