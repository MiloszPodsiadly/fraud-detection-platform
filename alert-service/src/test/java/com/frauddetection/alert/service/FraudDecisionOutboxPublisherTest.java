package com.frauddetection.alert.service;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class FraudDecisionOutboxPublisherTest {

    @Test
    void shouldPublishPendingOutboxEventAndMarkPublished() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher, mongoTemplate, metrics, Duration.ofMinutes(1), 5);
        TransactionalOutboxRecordDocument document = pendingOutboxRecord();
        document.setStatus(TransactionalOutboxStatus.PROCESSING);
        document.setAttempts(1);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TransactionalOutboxRecordDocument.class)))
                .thenReturn(document)
                .thenReturn(null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TransactionalOutboxRecordDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isEqualTo(1);
        verify(publisher).publish(document.getPayload());
        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class), eq(TransactionalOutboxRecordDocument.class));
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class));
    }

    @Test
    void shouldMarkConfirmationUnknownWhenKafkaPublishFailsAfterPublishAttempt() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher, mongoTemplate, metrics, Duration.ofMinutes(1), 5);
        TransactionalOutboxRecordDocument document = pendingOutboxRecord();
        document.setStatus(TransactionalOutboxStatus.PROCESSING);
        document.setAttempts(1);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TransactionalOutboxRecordDocument.class)))
                .thenReturn(document)
                .thenReturn(null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TransactionalOutboxRecordDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        doThrow(new IllegalStateException("kafka down")).when(publisher).publish(any(FraudDecisionEvent.class));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isZero();
        assertThat(document.getAttempts()).isEqualTo(1);
        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class), eq(TransactionalOutboxRecordDocument.class));
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class));
    }

    @Test
    void shouldExposePublishConfirmationFailureWhenKafkaSucceededButDbMarkPublishedFails() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher, mongoTemplate, metrics, Duration.ofMinutes(1), 5);
        TransactionalOutboxRecordDocument document = pendingOutboxRecord();
        document.setStatus(TransactionalOutboxStatus.PROCESSING);
        document.setAttempts(1);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TransactionalOutboxRecordDocument.class)))
                .thenReturn(document)
                .thenReturn(null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TransactionalOutboxRecordDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isZero();
        verify(publisher).publish(document.getPayload());
        verify(metrics).recordDecisionOutboxPublishConfirmationFailed();
        org.mockito.ArgumentCaptor<Update> updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(3)).updateFirst(any(Query.class), updateCaptor.capture(), eq(TransactionalOutboxRecordDocument.class));
        org.bson.Document setDocument = (org.bson.Document) updateCaptor.getAllValues().get(2).getUpdateObject().get("$set");
        assertThat(setDocument.get("status")).isEqualTo(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
    }

    @Test
    void shouldNotMarkUnknownWhenProjectionUpdateFailsAfterOutboxRecordPublished() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher, mongoTemplate, metrics, Duration.ofMinutes(1), 5);
        TransactionalOutboxRecordDocument document = pendingOutboxRecord();
        document.setStatus(TransactionalOutboxStatus.PROCESSING);
        document.setAttempts(1);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TransactionalOutboxRecordDocument.class)))
                .thenReturn(document)
                .thenReturn(null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TransactionalOutboxRecordDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isEqualTo(1);
        verify(publisher).publish(document.getPayload());
        verify(metrics, never()).recordDecisionOutboxPublishConfirmationFailed();
        verify(metrics).recordOutboxProjectionMismatch(1);
    }

    private TransactionalOutboxRecordDocument pendingOutboxRecord() {
        TransactionalOutboxRecordDocument document = new TransactionalOutboxRecordDocument();
        document.setEventId("event-1");
        document.setDedupeKey("event-1");
        document.setMutationCommandId("command-1");
        document.setResourceType("ALERT");
        document.setResourceId("alert-1");
        document.setEventType("FRAUD_DECISION");
        document.setPayloadHash("payload-hash");
        document.setStatus(TransactionalOutboxStatus.PENDING);
        document.setCreatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        document.setPayload(new FraudDecisionEvent(
                "event-1",
                "decision-1",
                "alert-1",
                "txn-1",
                "cust-1",
                "corr-1",
                "principal-7",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "Confirmed",
                List.of("chargeback"),
                Map.of(),
                Instant.parse("2026-04-27T10:00:00Z"),
                Instant.parse("2026-04-27T10:00:00Z")
        ));
        return document;
    }
}
