package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.AlertStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FraudDecisionEvent(
        String delivery,
        String dedupeKey,
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
    public static final String DELIVERY_AT_LEAST_ONCE = "AT_LEAST_ONCE";

    public FraudDecisionEvent(
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
        this(
                DELIVERY_AT_LEAST_ONCE,
                eventId,
                eventId,
                decisionId,
                alertId,
                transactionId,
                customerId,
                correlationId,
                analystId,
                decision,
                resultingStatus,
                decisionReason,
                tags,
                decisionMetadata,
                createdAt,
                decidedAt
        );
    }
}
