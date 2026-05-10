package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseStatus;

import java.time.Instant;
import java.util.Map;

public record FraudCaseAuditResponse(
        String id,
        String caseId,
        FraudCaseAuditAction action,
        String actorId,
        Instant occurredAt,
        FraudCaseStatus previousStatus,
        FraudCaseStatus newStatus,
        Map<String, String> details
) {
}
