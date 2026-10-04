package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionReason;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionResult;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionService;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MlPredictionEvidenceEventListenerTest {

    private final MlPredictionEvidenceProjectionService projectionService =
            mock(MlPredictionEvidenceProjectionService.class);
    private final MlPredictionEvidenceEventListener listener =
            new MlPredictionEvidenceEventListener(projectionService);
    private final TransactionScoredEvent event = mock(TransactionScoredEvent.class);

    @Test
    void acceptsProjectedIdempotentAndAbsentEvidenceOutcomes() {
        for (MlPredictionEvidenceProjectionResult result : new MlPredictionEvidenceProjectionResult[]{
                MlPredictionEvidenceProjectionResult.projected(),
                MlPredictionEvidenceProjectionResult.idempotentReplay(),
                MlPredictionEvidenceProjectionResult.omitted(MlPredictionEvidenceProjectionReason.EVIDENCE_ABSENT)
        }) {
            when(projectionService.project(event)).thenReturn(result);

            assertThatCode(() -> listener.onMessage(event)).doesNotThrowAnyException();
        }

        verify(projectionService, org.mockito.Mockito.times(3)).project(event);
    }

    @Test
    void transientStoreFailureRaisesBoundedRetryableFailure() {
        when(projectionService.project(event)).thenReturn(
                MlPredictionEvidenceProjectionResult.failed(MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE)
        );

        assertThatThrownBy(() -> listener.onMessage(event))
                .isInstanceOf(MlPredictionEvidenceTransientProcessingException.class)
                .hasMessage("ML_PREDICTION_EVIDENCE_PROJECTION_STORE_UNAVAILABLE")
                .hasMessageNotContaining("payload")
                .hasMessageNotContaining("customer")
                .hasMessageNotContaining("modelVersion");
    }

    @Test
    void permanentProjectionFailuresAreSeparatedFromRetryableStoreFailures() {
        for (MlPredictionEvidenceProjectionReason reason : new MlPredictionEvidenceProjectionReason[]{
                MlPredictionEvidenceProjectionReason.INVALID_EVIDENCE,
                MlPredictionEvidenceProjectionReason.INVALID_STORED_SHAPE,
                MlPredictionEvidenceProjectionReason.REPLAY_CONFLICT
        }) {
            when(projectionService.project(event)).thenReturn(MlPredictionEvidenceProjectionResult.failed(reason));

            assertThatThrownBy(() -> listener.onMessage(event))
                    .isInstanceOf(MlPredictionEvidencePermanentProcessingException.class)
                    .hasMessage("ML_PREDICTION_EVIDENCE_PROJECTION_" + reason.name());
        }
    }

    @Test
    void repeatedTransientFailuresRemainRetryable() {
        when(projectionService.project(event)).thenReturn(
                MlPredictionEvidenceProjectionResult.failed(MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE)
        );

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> listener.onMessage(event))
                    .isInstanceOf(MlPredictionEvidenceTransientProcessingException.class);
        }

        verify(projectionService, org.mockito.Mockito.times(3)).project(event);
    }

    @Test
    void slowEvidencePersistenceDoesNotBlockBaselineAlertHandling() throws Exception {
        CountDownLatch evidenceStarted = new CountDownLatch(1);
        CountDownLatch releaseEvidence = new CountDownLatch(1);
        when(projectionService.project(event)).thenAnswer(invocation -> {
            evidenceStarted.countDown();
            releaseEvidence.await(5, TimeUnit.SECONDS);
            return MlPredictionEvidenceProjectionResult.projected();
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var slowEvidence = executor.submit(() -> listener.onMessage(event));
            org.assertj.core.api.Assertions.assertThat(evidenceStarted.await(2, TimeUnit.SECONDS)).isTrue();

            AlertManagementUseCase alertManagement = mock(AlertManagementUseCase.class);
            TransactionMonitoringUseCase monitoring = mock(TransactionMonitoringUseCase.class);
            TransactionScoredEventListener baseline = new TransactionScoredEventListener(
                    alertManagement,
                    monitoring,
                    new KafkaTopicProperties(
                            "transactions.scored",
                            "fraud.alerts",
                            "fraud.decisions",
                            "transactions.dead-letter"
                    )
            );
            when(monitoring.recordScoredTransaction(event)).thenReturn(new ScoringOccurrenceAdmissionResult(
                    ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEW,
                    ScoringOccurrenceAdmissionResult.ReasonCode.FIRST_OCCURRENCE_ACCEPTED
            ));

            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> baseline.onMessage(event, null));
            verify(monitoring).recordScoredTransaction(event);
            verify(alertManagement).handleScoredTransaction(event);

            releaseEvidence.countDown();
            slowEvidence.get(2, TimeUnit.SECONDS);
        } finally {
            releaseEvidence.countDown();
            executor.shutdownNow();
        }
    }
}
