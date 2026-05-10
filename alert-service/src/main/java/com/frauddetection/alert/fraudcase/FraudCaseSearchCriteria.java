package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;

public record FraudCaseSearchCriteria(
        FraudCaseStatus status,
        String assignedInvestigatorId,
        FraudCasePriority priority,
        RiskLevel riskLevel,
        Instant createdFrom,
        Instant createdTo,
        String linkedAlertId
) {
}
