package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;

public record FraudCaseSummaryResponse(
        String caseId,
        String caseNumber,
        FraudCaseStatus status,
        FraudCasePriority priority,
        RiskLevel riskLevel,
        String assignedInvestigatorId,
        List<String> linkedAlertIds,
        Instant createdAt,
        Instant updatedAt
) {
}
