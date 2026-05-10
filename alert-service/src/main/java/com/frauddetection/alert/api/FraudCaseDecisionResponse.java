package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCaseDecisionType;

import java.time.Instant;

public record FraudCaseDecisionResponse(
        String id,
        String caseId,
        FraudCaseDecisionType decisionType,
        String summary,
        String createdBy,
        Instant createdAt
) {
}
