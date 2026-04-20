package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.AlertStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FraudDecisionEvent(
        String eventId,
        String decisionId,
        String alertId,
        String transactionId,
        String customerId,
        String correlationId,
        String analystId,
        AnalystDecision decision,
        AlertStatus resultingStatus,
        String decisionReason,
        List<String> tags,
        Map<String, Object> decisionMetadata,
        Instant createdAt,
        Instant decidedAt
) {
}
