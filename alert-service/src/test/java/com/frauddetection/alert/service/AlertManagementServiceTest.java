package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudAlertEventMapper;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        return new AlertManagementService(
                repository,
                new AlertDocumentMapper(),
                new FraudAlertEventMapper(),
                new AlertCaseFactory(),
                alertPublisher,
                fraudCaseManagementService,
                metrics,
                submitDecisionService
        );
    }
}
