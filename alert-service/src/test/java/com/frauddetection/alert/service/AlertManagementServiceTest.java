package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudAlertEventMapper;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertManagementServiceTest {

    @Test
    void shouldCreateAlertAndPublishEventForRecommendedScoredTransaction() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudDecisionEventPublisher decisionPublisher = mock(FraudDecisionEventPublisher.class);
        AlertDocumentMapper documentMapper = new AlertDocumentMapper();
        FraudAlertEventMapper alertEventMapper = new FraudAlertEventMapper();
        FraudDecisionEventMapper decisionEventMapper = new FraudDecisionEventMapper();
        AlertCaseFactory alertCaseFactory = new AlertCaseFactory();
        AnalystDecisionStatusMapper statusMapper = new AnalystDecisionStatusMapper();
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);

        var service = new AlertManagementService(repository, documentMapper, alertEventMapper, decisionEventMapper, alertCaseFactory, statusMapper, alertPublisher, decisionPublisher, fraudCaseManagementService);
        var event = TransactionFixtures.scoredTransaction().build();

        when(repository.existsByTransactionId(event.transactionId())).thenReturn(false);
        when(repository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.handleScoredTransaction(event);

        verify(repository).save(any(AlertDocument.class));
        verify(alertPublisher).publish(any(FraudAlertEvent.class));
    }

    @Test
    void shouldIgnoreDuplicateAlertCreatedConcurrently() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudDecisionEventPublisher decisionPublisher = mock(FraudDecisionEventPublisher.class);
        AlertDocumentMapper documentMapper = new AlertDocumentMapper();
        FraudAlertEventMapper alertEventMapper = new FraudAlertEventMapper();
        FraudDecisionEventMapper decisionEventMapper = new FraudDecisionEventMapper();
        AlertCaseFactory alertCaseFactory = new AlertCaseFactory();
        AnalystDecisionStatusMapper statusMapper = new AnalystDecisionStatusMapper();
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);

        var service = new AlertManagementService(repository, documentMapper, alertEventMapper, decisionEventMapper, alertCaseFactory, statusMapper, alertPublisher, decisionPublisher, fraudCaseManagementService);
        var event = TransactionFixtures.scoredTransaction().build();

        when(repository.existsByTransactionId(event.transactionId())).thenReturn(false);
        when(repository.save(any(AlertDocument.class))).thenThrow(new DuplicateKeyException("duplicate transaction alert"));

        service.handleScoredTransaction(event);

        verify(alertPublisher, never()).publish(any(FraudAlertEvent.class));
    }

    @Test
    void shouldStoreAnalystDecisionAndPublishDecisionEvent() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudDecisionEventPublisher decisionPublisher = mock(FraudDecisionEventPublisher.class);
        AlertDocumentMapper documentMapper = new AlertDocumentMapper();
        FraudAlertEventMapper alertEventMapper = new FraudAlertEventMapper();
        FraudDecisionEventMapper decisionEventMapper = new FraudDecisionEventMapper();
        AlertCaseFactory alertCaseFactory = new AlertCaseFactory();
        AnalystDecisionStatusMapper statusMapper = new AnalystDecisionStatusMapper();
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);

        var service = new AlertManagementService(repository, documentMapper, alertEventMapper, decisionEventMapper, alertCaseFactory, statusMapper, alertPublisher, decisionPublisher, fraudCaseManagementService);
        AlertDocument document = new AlertDocument();
        document.setAlertId("alert-1");
        document.setTransactionId("txn-1");
        document.setCustomerId("cust-1");
        document.setCorrelationId("corr-1");
        document.setCreatedAt(Instant.now());
        document.setAlertTimestamp(Instant.now());
        document.setAlertStatus(AlertStatus.OPEN);
        document.setRiskLevel(RiskLevel.HIGH);
        document.setFraudScore(0.82d);
        document.setFeatureSnapshot(Map.of("recentTransactionCount", 5));

        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.submitDecision("alert-1", new SubmitAnalystDecisionRequest(
                "analyst-7",
                AnalystDecision.CONFIRMED_FRAUD,
                "Confirmed after manual review",
                List.of("chargeback", "ip-risk"),
                Map.of("queue", "night-shift")
        ));

        assertThat(response.resultingStatus()).isEqualTo(AlertStatus.RESOLVED);
        ArgumentCaptor<FraudDecisionEvent> eventCaptor = ArgumentCaptor.forClass(FraudDecisionEvent.class);
        verify(decisionPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().decisionMetadata())
                .containsEntry("modelScore", 0.82d)
                .containsEntry("featureSnapshot", Map.of("recentTransactionCount", 5))
                .containsEntry("modelFeedbackVersion", "2026-04-22.v1")
                .containsEntry("queue", "night-shift");
    }
}
