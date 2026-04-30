package com.frauddetection.alert.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;

import java.time.Instant;

public record SubmitAnalystDecisionResponse(
        String alertId,
        AnalystDecision decision,
        AlertStatus resultingStatus,
        String decisionEventId,
        Instant decidedAt,
        @JsonProperty("operation_status")
        SubmitDecisionOperationStatus operationStatus
) {
    public SubmitAnalystDecisionResponse(
            String alertId,
            AnalystDecision decision,
            AlertStatus resultingStatus,
            String decisionEventId,
            Instant decidedAt
    ) {
        this(alertId, decision, resultingStatus, decisionEventId, decidedAt, SubmitDecisionOperationStatus.COMMITTED_AUDIT_PENDING);
    }
}
