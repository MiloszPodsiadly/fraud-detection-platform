package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Component;

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
                request.decisionMetadata() == null ? Map.of() : request.decisionMetadata(),
                Instant.now(),
                Instant.now()
        );
    }
}
