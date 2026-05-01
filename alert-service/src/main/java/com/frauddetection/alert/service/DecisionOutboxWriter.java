package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Component;

@Component
public class DecisionOutboxWriter {

    private final FraudDecisionEventMapper fraudDecisionEventMapper;

    public DecisionOutboxWriter(FraudDecisionEventMapper fraudDecisionEventMapper) {
        this.fraudDecisionEventMapper = fraudDecisionEventMapper;
    }

    public FraudDecisionEvent attachPendingOutbox(
            AlertDocument document,
            AlertCase alertCase,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            String actorId
    ) {
        FraudDecisionEvent event = fraudDecisionEventMapper.toEvent(alertCase, request, resultingStatus, actorId);
        document.setDecisionOutboxEvent(event);
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
        document.setDecisionOutboxAttempts(0);
        document.setDecisionOutboxLeaseOwner(null);
        document.setDecisionOutboxLeaseExpiresAt(null);
        document.setDecisionOutboxLastError(null);
        document.setDecisionOutboxFailureReason(null);
        document.setDecisionOutboxPublishedAt(null);
        return event;
    }
}
