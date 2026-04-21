package com.frauddetection.alert.api;

import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;

import java.time.Instant;

public record SubmitAnalystDecisionResponse(
        String alertId,
        AnalystDecision decision,
        AlertStatus resultingStatus,
        String decisionEventId,
        Instant decidedAt
) {
}
