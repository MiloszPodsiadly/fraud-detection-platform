package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceV1;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MlPredictionEvidenceProjectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:16:00Z");

    private final MlPredictionEvidenceProjectionRepository repository =
            mock(MlPredictionEvidenceProjectionRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MlPredictionEvidenceProjectionService service = new MlPredictionEvidenceProjectionService(
            repository,
            new EngineIntelligenceProjectionPolicy(),
            new AlertServiceMetrics(meterRegistry),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void missingOptionalEvidenceIsExplicitlyOmittedWithoutStorage() {
        MlPredictionEvidenceProjectionResult result = service.project(
                EngineIntelligenceProjectionTestFixtures.oldEvent()
        );

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.OMITTED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.EVIDENCE_ABSENT);
        verify(repository, never()).insert(any(MlPredictionEvidenceProjection.class));
    }

    @Test
    void invalidEvidenceIsRejectedBeforeStorage() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        MlPredictionEvidenceV1 invalid = mock(MlPredictionEvidenceV1.class);
        when(event.mlPredictionEvidence()).thenReturn(invalid);

        MlPredictionEvidenceProjectionResult result = service.project(event);

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.FAILED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.INVALID_EVIDENCE);
        verify(repository, never()).insert(any(MlPredictionEvidenceProjection.class));
    }

    @Test
    void mongoUnavailableIsObservableAndDoesNotReportSuccess() {
        when(repository.insert(any(MlPredictionEvidenceProjection.class)))
                .thenThrow(new DataAccessResourceFailureException("raw-secret-store-error"));

        MlPredictionEvidenceProjectionResult result = service.project(
                MlPredictionEvidenceProjectionTestSupport.event("evt-store-down", 0.8123d, "model-v1")
        );

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.FAILED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE);
        assertThat(meterRegistry.get("ml_prediction_evidence_projection_failure_total")
                .tag("reason", "STORE_UNAVAILABLE")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    void mongoReadFailureDuringDuplicateClassificationIsObservableAndCannotReportReplaySuccess() {
        when(repository.insert(any(MlPredictionEvidenceProjection.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(repository.findById("evt-read-down"))
                .thenThrow(new DataAccessResourceFailureException("raw-secret-store-error"));

        MlPredictionEvidenceProjectionResult result = service.project(
                MlPredictionEvidenceProjectionTestSupport.event("evt-read-down", 0.8123d, "model-v1")
        );

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.FAILED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE);
        assertThat(meterRegistry.get("ml_prediction_evidence_projection_failure_total")
                .tag("reason", "STORE_UNAVAILABLE")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    void mongoTimeoutIsClassifiedAsRetryableStoreUnavailability() {
        when(repository.insert(any(MlPredictionEvidenceProjection.class)))
                .thenThrow(new QueryTimeoutException("simulated bounded store timeout"));

        MlPredictionEvidenceProjectionResult result = service.project(
                MlPredictionEvidenceProjectionTestSupport.event("evt-store-timeout", 0.8123d, "model-v1")
        );

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.FAILED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE);
    }

    @Test
    void unexpectedRuntimeFailureIsNotMisreportedAsMongoUnavailability() {
        when(repository.insert(any(MlPredictionEvidenceProjection.class)))
                .thenThrow(new IllegalStateException("unexpected-programming-failure"));

        MlPredictionEvidenceProjectionResult result = service.project(
                MlPredictionEvidenceProjectionTestSupport.event("evt-unknown", 0.8123d, "model-v1")
        );

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.FAILED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.UNKNOWN_FAILURE);
        assertThat(meterRegistry.get("ml_prediction_evidence_projection_failure_total")
                .tag("reason", "UNKNOWN_FAILURE")
                .counter().count()).isEqualTo(1.0d);
    }
}
