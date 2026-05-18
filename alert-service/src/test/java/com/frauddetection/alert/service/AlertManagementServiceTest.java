package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.evidence.AlertEvidenceSnapshotProjectionService;
import com.frauddetection.alert.evidence.AlertEvidenceSnapshotProperties;
import com.frauddetection.alert.evidence.EvidenceProjectionState;
import com.frauddetection.alert.evidence.EvidenceSeverity;
import com.frauddetection.alert.evidence.EvidenceSnapshotItem;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.evidence.EvidenceType;
import com.frauddetection.alert.evidence.ScoringEvidenceSnapshotMapper;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudAlertEventMapper;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertManagementServiceTest {

    @Test
    void shouldCreateAlertAndPublishEventForRecommendedScoredTransaction() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        SubmitDecisionRegulatedMutationService submitDecisionService = mock(SubmitDecisionRegulatedMutationService.class);
        var service = service(repository, alertPublisher, fraudCaseManagementService, metrics, submitDecisionService);
        var event = TransactionFixtures.scoredTransaction().build();

        when(repository.existsByTransactionId(event.transactionId())).thenReturn(false);
        when(repository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.handleScoredTransaction(event);

        verify(repository).save(any(AlertDocument.class));
        verify(alertPublisher).publish(any(FraudAlertEvent.class));
    }

    @Test
    void alertCreationStoresProjectedEvidenceSnapshotWithoutChangingDecision() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        SubmitDecisionRegulatedMutationService submitDecisionService = mock(SubmitDecisionRegulatedMutationService.class);
        var service = service(repository, alertPublisher, fraudCaseManagementService, metrics, submitDecisionService);
        var base = TransactionFixtures.scoredTransaction().build();
        var event = new TransactionScoredEvent(
                base.eventId(),
                base.transactionId(),
                base.correlationId(),
                base.customerId(),
                base.accountId(),
                base.createdAt(),
                base.transactionTimestamp(),
                base.transactionAmount(),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                base.fraudScore(),
                base.riskLevel(),
                base.scoringStrategy(),
                base.modelName(),
                base.modelVersion(),
                base.inferenceTimestamp(),
                base.reasonCodes(),
                base.scoreDetails(),
                base.featureSnapshot(),
                true,
                List.of(scoringEvidence())
        );
        ArgumentCaptor<AlertDocument> captor = ArgumentCaptor.forClass(AlertDocument.class);

        when(repository.existsByTransactionId(event.transactionId())).thenReturn(false);
        when(repository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.handleScoredTransaction(event);

        verify(repository).save(captor.capture());
        AlertDocument saved = captor.getValue();
        assertThat(saved.getAlertStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(saved.getEvidenceSnapshot()).singleElement().satisfies(item ->
                assertThat(item.status()).isEqualTo(EvidenceStatus.AVAILABLE));
    }

    @Test
    void alertCreationStoresErrorDiagnosticWhenEvidenceProjectionFails() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        SubmitDecisionRegulatedMutationService submitDecisionService = mock(SubmitDecisionRegulatedMutationService.class);
        AlertEvidenceSnapshotProjectionService projectionService = mock(AlertEvidenceSnapshotProjectionService.class);
        var service = service(repository, alertPublisher, fraudCaseManagementService, metrics, submitDecisionService, projectionService);
        var event = TransactionFixtures.scoredTransaction().build();
        EvidenceSnapshotItem diagnostic = errorDiagnostic();
        ArgumentCaptor<AlertDocument> captor = ArgumentCaptor.forClass(AlertDocument.class);

        when(repository.existsByTransactionId(event.transactionId())).thenReturn(false);
        when(projectionService.projectOrDiagnostic(event)).thenReturn(List.of(diagnostic));
        when(repository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.handleScoredTransaction(event);

        verify(fraudCaseManagementService).handleScoredTransaction(event);
        verify(projectionService).projectOrDiagnostic(event);
        verify(repository).save(captor.capture());
        AlertDocument saved = captor.getValue();
        assertThat(saved.getEvidenceSnapshot()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(EvidenceStatus.ERROR);
            assertThat(item.evidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
            assertThat(item.attributes())
                    .containsEntry("projectionError", true)
                    .containsEntry("evidenceProjectionState", EvidenceProjectionState.ERROR_PROJECTION_FAILED.name());
            assertThat(item.attributes()).doesNotContainValue("raw exception message");
        });
        assertThat(saved.getEvidenceSnapshot()).noneMatch(item -> item.status() == EvidenceStatus.AVAILABLE);
        InOrder inOrder = inOrder(repository, alertPublisher);
        inOrder.verify(repository).save(any(AlertDocument.class));
        inOrder.verify(alertPublisher).publish(any(FraudAlertEvent.class));
    }

    @Test
    void shouldIgnoreDuplicateAlertCreatedConcurrently() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        SubmitDecisionRegulatedMutationService submitDecisionService = mock(SubmitDecisionRegulatedMutationService.class);
        var service = service(repository, alertPublisher, fraudCaseManagementService, metrics, submitDecisionService);
        var event = TransactionFixtures.scoredTransaction().build();

        when(repository.existsByTransactionId(event.transactionId())).thenReturn(false);
        when(repository.save(any(AlertDocument.class))).thenThrow(new DuplicateKeyException("duplicate transaction alert"));

        service.handleScoredTransaction(event);

        verify(alertPublisher, never()).publish(any(FraudAlertEvent.class));
    }

    @Test
    void shouldDelegateAnalystDecisionToRegulatedMutationCoordinatorBoundary() {
        AlertRepository repository = mock(AlertRepository.class);
        FraudAlertEventPublisher alertPublisher = mock(FraudAlertEventPublisher.class);
        FraudCaseManagementService fraudCaseManagementService = mock(FraudCaseManagementService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        SubmitDecisionRegulatedMutationService submitDecisionService = mock(SubmitDecisionRegulatedMutationService.class);
        var service = service(repository, alertPublisher, fraudCaseManagementService, metrics, submitDecisionService);
        SubmitAnalystDecisionRequest request = new SubmitAnalystDecisionRequest(
                "analyst-7",
                AnalystDecision.CONFIRMED_FRAUD,
                "Confirmed after manual review",
                List.of("chargeback"),
                Map.of()
        );
        SubmitAnalystDecisionResponse expected = new SubmitAnalystDecisionResponse(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                Instant.parse("2026-05-01T00:00:00Z"),
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
        );

        when(submitDecisionService.submit("alert-1", request, "idem-1")).thenReturn(expected);

        SubmitAnalystDecisionResponse actual = service.submitDecision("alert-1", request, "idem-1");

        assertThat(actual).isEqualTo(expected);
        verify(submitDecisionService).submit("alert-1", request, "idem-1");
        verify(metrics).recordAnalystDecisionSubmitted();
        verify(repository, never()).save(any(AlertDocument.class));
    }

    private AlertManagementService service(
            AlertRepository repository,
            FraudAlertEventPublisher alertPublisher,
            FraudCaseManagementService fraudCaseManagementService,
            AlertServiceMetrics metrics,
            SubmitDecisionRegulatedMutationService submitDecisionService
    ) {
        return service(
                repository,
                alertPublisher,
                fraudCaseManagementService,
                metrics,
                submitDecisionService,
                new AlertEvidenceSnapshotProjectionService(
                        new ScoringEvidenceSnapshotMapper(),
                        new AlertEvidenceSnapshotProperties(null),
                        metrics
                )
        );
    }

    private AlertManagementService service(
            AlertRepository repository,
            FraudAlertEventPublisher alertPublisher,
            FraudCaseManagementService fraudCaseManagementService,
            AlertServiceMetrics metrics,
            SubmitDecisionRegulatedMutationService submitDecisionService,
            AlertEvidenceSnapshotProjectionService projectionService
    ) {
        return new AlertManagementService(
                repository,
                new AlertDocumentMapper(),
                new FraudAlertEventMapper(),
                new AlertCaseFactory(),
                projectionService,
                alertPublisher,
                fraudCaseManagementService,
                metrics,
                submitDecisionService
        );
    }

    private ScoringEvidenceItem scoringEvidence() {
        return new ScoringEvidenceItem(
                "evidence-1",
                "COUNTRY_MISMATCH",
                ScoringEvidenceType.GEO_SIGNAL,
                ScoringEvidenceSource.RULE_BASED_SCORING,
                ScoringEvidenceStatus.AVAILABLE,
                ScoringEvidenceSeverity.HIGH,
                "Country mismatch",
                "Transaction geography differed from expected context.",
                null,
                null,
                Map.of(),
                Instant.parse("2026-05-18T10:00:00Z")
        );
    }

    private EvidenceSnapshotItem errorDiagnostic() {
        return new EvidenceSnapshotItem(
                "event-1:projection_failed:0",
                "event-1",
                "txn-1",
                "corr-1",
                null,
                EvidenceType.DIAGNOSTIC,
                EvidenceSource.ALERT_SERVICE,
                EvidenceStatus.ERROR,
                EvidenceSeverity.LOW,
                "Alert evidence snapshot projection failed",
                "Projection failure diagnostic.",
                "projection_failed",
                null,
                Map.of(
                        "diagnostic", true,
                        "supportedEvidenceCreated", false,
                        "reasonCodeApplicable", false,
                        "projectionError", true,
                        "projectionErrorType", "IllegalStateException",
                        "evidenceProjectionState", EvidenceProjectionState.ERROR_PROJECTION_FAILED.name()
                ),
                Instant.parse("2026-05-18T10:00:00Z"),
                Instant.parse("2026-05-18T10:01:00Z"),
                "RULE_BASED",
                "rule-based",
                "v1",
                Instant.parse("2026-05-18T10:00:00Z")
        );
    }
}
