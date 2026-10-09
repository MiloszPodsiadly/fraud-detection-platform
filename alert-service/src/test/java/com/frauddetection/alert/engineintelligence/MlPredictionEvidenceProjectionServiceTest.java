package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void validOmissionIsPersistedAsTheAuthoritativeOutcome() {
        MlPredictionEvidenceProjectionResult result = service.project(
                EngineIntelligenceProjectionTestFixtures.oldEvent()
        );

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.OMITTED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.EVIDENCE_ABSENT);
        verify(repository).insert(any(MlPredictionEvidenceProjection.class));
    }

    @Test
    void immutableOutcomeReplayMatrixDistinguishesIdentityFromConflict() {
        var evidenceSame = MlPredictionEvidenceProjectionTestSupport.event(
                "evidence-same", 0.8123d, "model-v1"
        );
        var evidenceDifferent = MlPredictionEvidenceProjectionTestSupport.event(
                "evidence-different", 0.7123d, "model-v1"
        );
        var evidenceToOmission = MlPredictionEvidenceProjectionTestSupport.event(
                "evidence-to-omission", 0.8123d, "model-v1"
        );
        var omissionSame = MlPredictionEvidenceProjectionTestSupport.eventWithoutEvidence("omission-same");
        var omissionDifferent = MlPredictionEvidenceProjectionTestSupport.eventWithoutEvidence("omission-different");
        var omissionToEvidence = MlPredictionEvidenceProjectionTestSupport.eventWithoutEvidence(
                "omission-to-evidence"
        );
        when(repository.insert(any(MlPredictionEvidenceProjection.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(repository.findById(eq("evidence-same"))).thenReturn(Optional.of(outcome(evidenceSame)));
        when(repository.findById(eq("evidence-different"))).thenReturn(Optional.of(outcome(
                MlPredictionEvidenceProjectionTestSupport.event("evidence-different", 0.8123d, "model-v1")
        )));
        when(repository.findById(eq("evidence-to-omission"))).thenReturn(Optional.of(outcome(evidenceToOmission)));
        when(repository.findById(eq("omission-same"))).thenReturn(Optional.of(outcome(omissionSame)));
        when(repository.findById(eq("omission-different"))).thenReturn(Optional.of(outcome(omissionDifferent)));
        when(repository.findById(eq("omission-to-evidence"))).thenReturn(Optional.of(outcome(omissionToEvidence)));

        assertThat(service.project(evidenceSame).status())
                .isEqualTo(MlPredictionEvidenceProjectionStatus.IDEMPOTENT_REPLAY);
        assertConflict(service.project(evidenceDifferent));
        assertConflict(service.project(MlPredictionEvidenceProjectionTestSupport.eventWithoutEvidence(
                "evidence-to-omission"
        )));
        assertThat(service.project(omissionSame).status())
                .isEqualTo(MlPredictionEvidenceProjectionStatus.IDEMPOTENT_REPLAY);
        assertConflict(service.project(MlPredictionEvidenceProjectionTestSupport.eventWithoutEvidence(
                "omission-different",
                com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.INVALID_SCORE
        )));
        assertConflict(service.project(MlPredictionEvidenceProjectionTestSupport.event(
                "omission-to-evidence", 0.8123d, "model-v1"
        )));
    }

    @Test
    void invalidEvidenceIsRejectedBeforeStorage() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        MlPredictionEvidence invalid = mock(MlPredictionEvidence.class);
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

    private MlPredictionEvidenceProjection outcome(TransactionScoredEvent event) {
        if (event.mlPredictionEvidence() != null) {
            return MlPredictionEvidenceProjection.create(
                    event.eventId(),
                    event.transactionId(),
                    event.correlationId(),
                    event.createdAt(),
                    event.mlPredictionEvidence(),
                    NOW
            );
        }
        return MlPredictionEvidenceProjection.omitted(
                event.eventId(),
                event.transactionId(),
                event.correlationId(),
                event.createdAt(),
                event.mlPredictionEvidenceOmissionReason(),
                NOW
        );
    }

    private void assertConflict(MlPredictionEvidenceProjectionResult result) {
        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.FAILED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.REPLAY_CONFLICT);
    }
}
