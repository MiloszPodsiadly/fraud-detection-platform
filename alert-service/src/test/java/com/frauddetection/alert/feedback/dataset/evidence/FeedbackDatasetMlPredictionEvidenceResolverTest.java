package com.frauddetection.alert.feedback.dataset.evidence;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.feedback.dataset.FeedbackDatasetMlPredictionEvidenceResolutionProvenance;
import com.frauddetection.alert.feedback.dataset.FeedbackDatasetMlPredictionEvidenceStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackDatasetMlPredictionEvidenceResolverTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-01T10:15:30Z");
    private static final String MODEL_NAME = "python-logistic-fraud-model";
    private static final String MODEL_VERSION = "2026-10-01.v1";
    private static final String FEATURE_CONTRACT = "feature-contract-v2";
    private static final String ARTIFACT_SHA256 = "a".repeat(64);

    private final MlPredictionEvidenceProjectionRepository repository =
            mock(MlPredictionEvidenceProjectionRepository.class);
    private final FeedbackDatasetMlPredictionEvidenceResolver resolver =
            new FeedbackDatasetMlPredictionEvidenceResolver(repository);

    @Test
    void confirmsAvailableEvidenceAgainstCapturedFourPartIdentity() {
        FraudFeedbackRecord source = source("event-a", "txn-a");
        captureModelIdentity(source);
        when(repository.findAllById(any())).thenReturn(List.of(evidence("event-a", "txn-a")));

        var resolution = resolver.resolve(List.of(candidate(source, "event-a"))).getFirst();

        assertThat(resolution.status()).isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE);
        assertThat(resolution.resolutionProvenance()).contains(
                FeedbackDatasetMlPredictionEvidenceResolutionProvenance.CAPTURED_AND_CONFIRMED
        );
        assertThat(resolution.projection()).isPresent();
    }

    @Test
    void recoversAvailableEvidenceOnlyFromExactOccurrenceProjection() {
        FraudFeedbackRecord source = source("event-a", "txn-a");
        when(repository.findAllById(any())).thenReturn(List.of(evidence("event-a", "txn-a")));

        var resolution = resolver.resolve(List.of(candidate(source, "event-a"))).getFirst();

        assertThat(resolution.status()).isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE);
        assertThat(resolution.resolutionProvenance()).contains(
                FeedbackDatasetMlPredictionEvidenceResolutionProvenance.RECOVERED_FROM_EXACT_OCCURRENCE_PROJECTION
        );
    }

    @Test
    void preservesLegitimateAuthoritativeOmission() {
        FraudFeedbackRecord source = source("event-a", "txn-a");
        source.setMlPredictionEvidenceOmissionReason(
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
        );
        when(repository.findAllById(any())).thenReturn(List.of());

        var resolution = resolver.resolve(List.of(candidate(source, "event-a"))).getFirst();

        assertThat(resolution.status())
                .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT);
        assertThat(resolution.omissionReason())
                .contains(MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED);
    }

    @Test
    void missingProjectionWithoutOmissionIsUnexpected() {
        FraudFeedbackRecord source = source("event-a", "txn-a");
        when(repository.findAllById(any())).thenReturn(List.of());

        var resolution = resolver.resolve(List.of(candidate(source, "event-a"))).getFirst();

        assertThat(resolution.status())
                .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY);
    }

    @Test
    void partialCapturedModelIdentityIsMalformed() {
        FraudFeedbackRecord source = source("event-a", "txn-a");
        source.setMlModelName(MODEL_NAME);
        when(repository.findAllById(any())).thenReturn(List.of(evidence("event-a", "txn-a")));

        var resolution = resolver.resolve(List.of(candidate(source, "event-a"))).getFirst();

        assertThat(resolution.status()).isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED);
        assertThat(resolution.projection()).isEmpty();
    }

    @Test
    void fourPartModelIdentityMismatchIsExplicit() {
        FraudFeedbackRecord source = source("event-a", "txn-a");
        captureModelIdentity(source);
        source.setMlModelArtifactSha256("b".repeat(64));
        when(repository.findAllById(any())).thenReturn(List.of(evidence("event-a", "txn-a")));

        var resolution = resolver.resolve(List.of(candidate(source, "event-a"))).getFirst();

        assertThat(resolution.status()).isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH);
        assertThat(resolution.projection()).isEmpty();
    }

    @Test
    void corruptProjectionIsMalformed() {
        FraudFeedbackRecord source = source("event-a", "txn-a");
        MlPredictionEvidenceProjection corrupt = mock(MlPredictionEvidenceProjection.class);
        when(corrupt.getSourceEventId()).thenReturn("event-a");
        when(corrupt.getSourceEventCreatedAt()).thenThrow(new IllegalArgumentException("corrupt timestamp"));
        when(repository.findAllById(any())).thenReturn(List.of(corrupt));

        var resolution = resolver.resolve(List.of(candidate(source, "event-a"))).getFirst();

        assertThat(resolution.status()).isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED);
    }

    @Test
    void duplicateProjectionForExactOccurrenceFailsClosed() {
        FraudFeedbackRecord source = source("event-a", "txn-a");
        MlPredictionEvidenceProjection projection = evidence("event-a", "txn-a");
        when(repository.findAllById(any())).thenReturn(List.of(projection, projection));

        assertThatThrownBy(() -> resolver.resolve(List.of(candidate(source, "event-a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ML prediction evidence lookup returned duplicate projections");
    }

    @Test
    void resolvesUniqueOccurrenceIdsInOneOrderedBatch() {
        FraudFeedbackRecord first = source("event-a", "txn-a");
        FraudFeedbackRecord second = source("event-b", "txn-b");
        when(repository.findAllById(any())).thenReturn(List.of(
                evidence("event-b", "txn-b"),
                evidence("event-a", "txn-a")
        ));

        var resolutions = resolver.resolve(List.of(
                candidate(first, "event-a"),
                candidate(second, "event-b"),
                candidate(first, "event-a")
        ));

        assertThat(resolutions).extracting(resolution -> resolution.projection().orElseThrow().getSourceEventId())
                .containsExactly("event-a", "event-b", "event-a");
        ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(repository, times(1)).findAllById(ids.capture());
        assertThat(StreamSupport.stream(ids.getValue().spliterator(), false))
                .containsExactly("event-a", "event-b");
    }

    private FeedbackDatasetMlPredictionEvidenceResolver.Candidate candidate(
            FraudFeedbackRecord source,
            String sourceEventId
    ) {
        return new FeedbackDatasetMlPredictionEvidenceResolver.Candidate(
                source,
                ScoringOccurrenceOwnership.authoritative(sourceEventId, OCCURRED_AT, "f".repeat(64))
        );
    }

    private FraudFeedbackRecord source(String sourceEventId, String transactionId) {
        FraudFeedbackRecord source = new FraudFeedbackRecord();
        source.setFeedbackId("feedback-" + sourceEventId);
        source.setTransactionId(transactionId);
        source.setCorrelationId("correlation-" + sourceEventId);
        return source;
    }

    private void captureModelIdentity(FraudFeedbackRecord source) {
        source.setMlModelName(MODEL_NAME);
        source.setMlModelVersion(MODEL_VERSION);
        source.setMlFeatureContractVersion(FEATURE_CONTRACT);
        source.setMlModelArtifactSha256(ARTIFACT_SHA256);
    }

    private MlPredictionEvidenceProjection evidence(String sourceEventId, String transactionId) {
        return new MlPredictionEvidenceProjection(
                sourceEventId,
                transactionId,
                "correlation-" + sourceEventId,
                OCCURRED_AT.toString(),
                0.91,
                RiskLevel.HIGH,
                MODEL_NAME,
                MODEL_VERSION,
                FEATURE_CONTRACT,
                ARTIFACT_SHA256,
                OCCURRED_AT.plusSeconds(1).toString(),
                OCCURRED_AT.plusSeconds(2)
        );
    }
}
