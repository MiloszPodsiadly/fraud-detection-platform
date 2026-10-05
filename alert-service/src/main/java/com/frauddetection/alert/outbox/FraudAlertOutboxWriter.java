package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

@Primary
@Component
public class FraudAlertOutboxWriter implements FraudAlertEventPublisher {

    private final MongoTemplate mongoTemplate;

    public FraudAlertOutboxWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void publish(FraudAlertEvent event) {
        if (event == null || event.eventId() == null || event.alertId() == null || event.transactionId() == null) {
            throw new IllegalArgumentException("FRAUD_ALERT_OUTBOX_EVENT_INVALID");
        }
        FraudAlertOutboxRecord existing = mongoTemplate.findById(event.eventId(), FraudAlertOutboxRecord.class);
        if (existing != null) {
            requireIdenticalPayload(event, existing);
            return;
        }
        Instant now = Instant.now();
        FraudAlertOutboxRecord record = new FraudAlertOutboxRecord();
        record.setEventId(event.eventId());
        record.setAlertId(event.alertId());
        record.setTransactionId(event.transactionId());
        record.setPayload(event);
        record.setStatus(FraudAlertOutboxStatus.PENDING);
        record.setRevision(0L);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        try {
            mongoTemplate.insert(record);
        } catch (DuplicateKeyException duplicate) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw duplicate;
            }
            requireIdenticalPayload(
                    event,
                    mongoTemplate.findById(event.eventId(), FraudAlertOutboxRecord.class)
            );
        }
    }

    private void requireIdenticalPayload(FraudAlertEvent event, FraudAlertOutboxRecord existing) {
        if (existing == null || !event.equals(existing.getPayload())) {
            throw new IllegalStateException("FRAUD_ALERT_OUTBOX_CONFLICT");
        }
    }
}
