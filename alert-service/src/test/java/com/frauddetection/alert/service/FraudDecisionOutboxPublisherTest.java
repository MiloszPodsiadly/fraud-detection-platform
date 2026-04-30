package com.frauddetection.alert.service;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudDecisionOutboxPublisherTest {

    @Test
    void shouldPublishPendingOutboxEventAndMarkPublished() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher, mongoTemplate, metrics, Duration.ofMinutes(1), 5);
        AlertDocument document = pendingDocument();
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PROCESSING);
        document.setDecisionOutboxAttempts(1);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(AlertDocument.class)))
                .thenReturn(document)
                .thenReturn(null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isEqualTo(1);
        verify(publisher).publish(document.getDecisionOutboxEvent());
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class));
    }

    @Test
    void shouldKeepPendingOutboxEventWhenKafkaPublishFails() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher, mongoTemplate, metrics, Duration.ofMinutes(1), 5);
        AlertDocument document = pendingDocument();
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PROCESSING);
        document.setDecisionOutboxAttempts(1);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(AlertDocument.class)))
                .thenReturn(document)
                .thenReturn(null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        doThrow(new IllegalStateException("kafka down")).when(publisher).publish(any(FraudDecisionEvent.class));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isZero();
        assertThat(document.getDecisionOutboxAttempts()).isEqualTo(1);
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class));
    }

    @Test
    void shouldExposePublishConfirmationFailureWhenKafkaSucceededButDbMarkPublishedFails() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher, mongoTemplate, metrics, Duration.ofMinutes(1), 5);
        AlertDocument document = pendingDocument();
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PROCESSING);
        document.setDecisionOutboxAttempts(1);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(AlertDocument.class)))
                .thenReturn(document)
                .thenReturn(null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isZero();
        verify(publisher).publish(document.getDecisionOutboxEvent());
        verify(metrics).recordDecisionOutboxPublishConfirmationFailed();
    }

    private AlertDocument pendingDocument() {
        AlertDocument document = new AlertDocument();
        document.setAlertId("alert-1");
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
        document.setDecisionOutboxEvent(new FraudDecisionEvent(
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
