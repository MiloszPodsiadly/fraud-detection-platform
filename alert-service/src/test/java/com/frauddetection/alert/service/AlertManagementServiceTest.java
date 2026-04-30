package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditMutationRecorder;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudAlertEventMapper;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);

        var service = new AlertManagementService(repository, documentMapper, alertEventMapper, decisionEventMapper, alertCaseFactory, statusMapper, alertPublisher, decisionPublisher, fraudCaseManagementService, new AuditMutationRecorder(auditService), analystActorResolver, metrics);
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
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);

        var service = new AlertManagementService(repository, documentMapper, alertEventMapper, decisionEventMapper, alertCaseFactory, statusMapper, alertPublisher, decisionPublisher, fraudCaseManagementService, new AuditMutationRecorder(auditService), analystActorResolver, metrics);
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
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);

        var service = new AlertManagementService(repository, documentMapper, alertEventMapper, decisionEventMapper, alertCaseFactory, statusMapper, alertPublisher, decisionPublisher, fraudCaseManagementService, new AuditMutationRecorder(auditService), analystActorResolver, metrics);
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
        when(analystActorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        var response = service.submitDecision("alert-1", new SubmitAnalystDecisionRequest(
                "analyst-7",
                AnalystDecision.CONFIRMED_FRAUD,
                "Confirmed after manual review",
                List.of("chargeback", "ip-risk"),
                Map.of("queue", "night-shift")
        ));

        assertThat(response.resultingStatus()).isEqualTo(AlertStatus.RESOLVED);
        ArgumentCaptor<AlertDocument> documentCaptor = ArgumentCaptor.forClass(AlertDocument.class);
        verify(repository).save(documentCaptor.capture());
        assertThat(documentCaptor.getValue().getAnalystId()).isEqualTo("principal-7");

        ArgumentCaptor<FraudDecisionEvent> eventCaptor = ArgumentCaptor.forClass(FraudDecisionEvent.class);
        verify(decisionPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().decisionMetadata())
                .containsEntry("modelScore", 0.82d)
                .containsEntry("featureSnapshot", Map.of("recentTransactionCount", 5))
                .containsEntry("modelFeedbackVersion", "2026-04-22.v1")
                .containsEntry("queue", "night-shift");
        InOrder inOrder = inOrder(auditService, repository);
        inOrder.verify(auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.ATTEMPTED,
                null
        );
        inOrder.verify(repository).save(any(AlertDocument.class));
        inOrder.verify(auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.SUCCESS,
                null
        );
        verify(metrics).recordAnalystDecisionSubmitted();
        assertThat(eventCaptor.getValue().analystId()).isEqualTo("principal-7");
    }

    @Test
    void shouldBlockAnalystDecisionWhenDurableAuditFails() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudDecisionEventPublisher decisionPublisher = mock(FraudDecisionEventPublisher.class);
        AlertDocumentMapper documentMapper = new AlertDocumentMapper();
        FraudAlertEventMapper alertEventMapper = new FraudAlertEventMapper();
        FraudDecisionEventMapper decisionEventMapper = new FraudDecisionEventMapper();
        AlertCaseFactory alertCaseFactory = new AlertCaseFactory();
        AnalystDecisionStatusMapper statusMapper = new AnalystDecisionStatusMapper();
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);

        var service = new AlertManagementService(repository, documentMapper, alertEventMapper, decisionEventMapper, alertCaseFactory, statusMapper, alertPublisher, decisionPublisher, fraudCaseManagementService, new AuditMutationRecorder(auditService), analystActorResolver, metrics);
        AlertDocument document = new AlertDocument();
        document.setAlertId("alert-1");
        document.setCorrelationId("corr-1");
        document.setAlertStatus(AlertStatus.OPEN);

        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(analystActorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");
        doThrow(new AuditPersistenceUnavailableException()).when(auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.ATTEMPTED,
                null
        );

        assertThatThrownBy(() -> service.submitDecision("alert-1", new SubmitAnalystDecisionRequest(
                "analyst-7",
                AnalystDecision.CONFIRMED_FRAUD,
                "Confirmed after manual review",
                List.of("chargeback"),
                Map.of()
        ))).isInstanceOf(AuditPersistenceUnavailableException.class);

        verify(repository, never()).save(any(AlertDocument.class));
        verify(decisionPublisher, never()).publish(any(FraudDecisionEvent.class));
        verify(metrics, never()).recordAnalystDecisionSubmitted();
    }

    @Test
    void shouldAuditFailedAnalystDecisionWhenBusinessWriteFails() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudDecisionEventPublisher decisionPublisher = mock(FraudDecisionEventPublisher.class);
        AlertDocumentMapper documentMapper = new AlertDocumentMapper();
        FraudAlertEventMapper alertEventMapper = new FraudAlertEventMapper();
        FraudDecisionEventMapper decisionEventMapper = new FraudDecisionEventMapper();
        AlertCaseFactory alertCaseFactory = new AlertCaseFactory();
        AnalystDecisionStatusMapper statusMapper = new AnalystDecisionStatusMapper();
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);

        var service = new AlertManagementService(repository, documentMapper, alertEventMapper, decisionEventMapper, alertCaseFactory, statusMapper, alertPublisher, decisionPublisher, fraudCaseManagementService, new AuditMutationRecorder(auditService), analystActorResolver, metrics);
        AlertDocument document = new AlertDocument();
        document.setAlertId("alert-1");
        document.setCorrelationId("corr-1");
        document.setAlertStatus(AlertStatus.OPEN);

        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(any(AlertDocument.class))).thenThrow(new DataAccessResourceFailureException("mongo down"));
        when(analystActorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        assertThatThrownBy(() -> service.submitDecision("alert-1", new SubmitAnalystDecisionRequest(
                "analyst-7",
                AnalystDecision.CONFIRMED_FRAUD,
                "Confirmed after manual review",
                List.of("chargeback"),
                Map.of()
        ))).isInstanceOf(DataAccessResourceFailureException.class);

        InOrder inOrder = inOrder(auditService, repository);
        inOrder.verify(auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.ATTEMPTED,
                null
        );
        inOrder.verify(repository).save(any(AlertDocument.class));
        inOrder.verify(auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.FAILED,
                "BUSINESS_WRITE_FAILED"
        );
        verify(auditService, never()).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.SUCCESS,
                null
        );
        verify(decisionPublisher, never()).publish(any(FraudDecisionEvent.class));
        verify(metrics, never()).recordAnalystDecisionSubmitted();
    }
}
