package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class FraudDecisionEventMapper {

    public FraudDecisionEvent toEvent(AlertCase alertCase, SubmitAnalystDecisionRequest request, AlertStatus resultingStatus) {
        return new FraudDecisionEvent(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                alertCase.alertId(),
                alertCase.transactionId(),
                alertCase.customerId(),
                alertCase.correlationId(),
                request.analystId(),
                request.decision(),
                resultingStatus,
                request.decisionReason(),
                request.tags(),
                feedbackMetadata(alertCase, request),
                Instant.now(),
                Instant.now()
        );
    }

    private Map<String, Object> feedbackMetadata(AlertCase alertCase, SubmitAnalystDecisionRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.decisionMetadata() != null) {
            metadata.putAll(request.decisionMetadata());
        }
        metadata.put("featureSnapshot", alertCase.featureSnapshot() == null ? Map.of() : alertCase.featureSnapshot());
        if (alertCase.fraudScore() != null) {
            metadata.put("modelScore", alertCase.fraudScore());
        }
        if (alertCase.riskLevel() != null) {
            metadata.put("riskLevel", alertCase.riskLevel());
        }
        metadata.put("modelFeedbackVersion", "2026-04-22.v1");
        return Map.copyOf(metadata);
    }
}
