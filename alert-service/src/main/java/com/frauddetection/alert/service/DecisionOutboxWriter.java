package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DecisionOutboxWriter {

    private final FraudDecisionEventMapper fraudDecisionEventMapper;
    private final TransactionalOutboxRecordRepository outboxRepository;

    @Autowired
    public DecisionOutboxWriter(
            FraudDecisionEventMapper fraudDecisionEventMapper,
            TransactionalOutboxRecordRepository outboxRepository
    ) {
        this.fraudDecisionEventMapper = fraudDecisionEventMapper;
        this.outboxRepository = outboxRepository;
    }

    public DecisionOutboxWriter(FraudDecisionEventMapper fraudDecisionEventMapper) {
        this(fraudDecisionEventMapper, null);
    }

    public FraudDecisionEvent attachPendingOutbox(
            AlertDocument document,
            AlertCase alertCase,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            String actorId,
            String mutationCommandId
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
        if (outboxRepository != null) {
            outboxRepository.save(record(document, event, mutationCommandId));
        }
        return event;
    }

    private TransactionalOutboxRecordDocument record(
            AlertDocument document,
            FraudDecisionEvent event,
            String mutationCommandId
    ) {
        Instant now = Instant.now();
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(event.eventId());
        record.setDedupeKey(event.dedupeKey());
        record.setMutationCommandId(mutationCommandId);
        record.setResourceType("ALERT");
        record.setResourceId(document.getAlertId());
        record.setEventType("FRAUD_DECISION");
        record.setPayloadHash(RegulatedMutationIntentHasher.hash(event));
        record.setPayload(event);
        record.setStatus(TransactionalOutboxStatus.PENDING);
        record.setAttempts(0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }
}
