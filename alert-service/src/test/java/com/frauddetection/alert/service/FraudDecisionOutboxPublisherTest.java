package com.frauddetection.alert.service;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudDecisionOutboxPublisherTest {

    @Test
    void shouldPublishPendingOutboxEventAndMarkPublished() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher);
        AlertDocument document = pendingDocument();
        when(repository.findTop100ByDecisionOutboxStatusOrderByDecidedAtAsc(DecisionOutboxStatus.PENDING))
                .thenReturn(List.of(document));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isEqualTo(1);
        assertThat(document.getDecisionOutboxStatus()).isEqualTo(DecisionOutboxStatus.PUBLISHED);
        assertThat(document.getDecisionOutboxPublishedAt()).isNotNull();
        assertThat(document.getDecisionOutboxFailureReason()).isNull();
        verify(publisher).publish(document.getDecisionOutboxEvent());
        verify(repository).save(document);
    }

    @Test
    void shouldKeepPendingOutboxEventWhenKafkaPublishFails() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudDecisionEventPublisher publisher = mock(FraudDecisionEventPublisher.class);
        FraudDecisionOutboxPublisher outboxPublisher = new FraudDecisionOutboxPublisher(repository, publisher);
        AlertDocument document = pendingDocument();
        when(repository.findTop100ByDecisionOutboxStatusOrderByDecidedAtAsc(DecisionOutboxStatus.PENDING))
                .thenReturn(List.of(document));
        doThrow(new IllegalStateException("kafka down")).when(publisher).publish(any(FraudDecisionEvent.class));

        int published = outboxPublisher.publishPending(100);

        assertThat(published).isZero();
        assertThat(document.getDecisionOutboxStatus()).isEqualTo(DecisionOutboxStatus.PENDING);
        assertThat(document.getDecisionOutboxAttempts()).isEqualTo(1);
        assertThat(document.getDecisionOutboxLastAttemptAt()).isNotNull();
        assertThat(document.getDecisionOutboxFailureReason()).isEqualTo("PUBLISH_FAILED");
        verify(repository).save(document);
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
