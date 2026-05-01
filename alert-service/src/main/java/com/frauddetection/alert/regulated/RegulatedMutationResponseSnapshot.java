package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;

import java.time.Instant;

public record RegulatedMutationResponseSnapshot(
        @JsonProperty("alert_id")
        String alertId,
        AnalystDecision decision,
        @JsonProperty("resulting_status")
        AlertStatus resultingStatus,
        @JsonProperty("decision_event_id")
        String decisionEventId,
        @JsonProperty("decided_at")
        Instant decidedAt,
        @JsonProperty("operation_status")
        SubmitDecisionOperationStatus operationStatus
) {
    public static RegulatedMutationResponseSnapshot from(SubmitAnalystDecisionResponse response) {
        return new RegulatedMutationResponseSnapshot(
                response.alertId(),
                response.decision(),
                response.resultingStatus(),
                response.decisionEventId(),
                response.decidedAt(),
                response.operationStatus()
        );
    }

    public SubmitAnalystDecisionResponse toSubmitDecisionResponse() {
        return new SubmitAnalystDecisionResponse(
                alertId,
                decision,
                resultingStatus,
                decisionEventId,
                decidedAt,
                operationStatus
        );
    }
}
