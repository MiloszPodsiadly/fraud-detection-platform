package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.feedback.governance.FeedbackDatasetEligibilityPolicy;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeedbackDatasetBuilderTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");
    private static final Instant BUILT_AT = Instant.parse("2026-06-30T12:00:00Z");
    private static final Instant EXECUTED_AT = Instant.parse("2026-06-01T00:00:00.500Z");
    private static final String MODEL_NAME = "python-logistic-fraud-model";
    private static final String FEATURE_CONTRACT_VERSION = "feature-contract-v2";
    private static final String MODEL_ARTIFACT_SHA256 = "a".repeat(64);

    private final FeedbackDatasetCandidateStore store = mock(FeedbackDatasetCandidateStore.class);
    private final MlPredictionEvidenceProjectionRepository evidenceRepository =
            mock(MlPredictionEvidenceProjectionRepository.class);
    private final FeedbackDatasetBuilder builder = new FeedbackDatasetBuilder(
            store,
            new FeedbackDatasetMappingPolicy(new FeedbackDatasetEligibilityPolicy()),
            evidenceRepository,
            Clock.fixed(BUILT_AT, ZoneOffset.UTC)
    );

    @Test
    void exportsConfirmedFraudAsPositiveFraud() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isFalse();
        assertThat(result.records()).singleElement()
                .extracting(FeedbackDatasetRecord::evaluationLabel)
                .isEqualTo(FeedbackEvaluationLabel.POSITIVE_FRAUD);
    }

    @Test
    void exportsConfirmedLegitimateAsNegativeLegitimate() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-2", "txn-2", FraudFeedbackLabel.CONFIRMED_LEGITIMATE, FROM.plusSeconds(2))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).singleElement()
                .extracting(FeedbackDatasetRecord::evaluationLabel)
                .isEqualTo(FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE);
    }

    @Test
    void excludesUnresolvedLabelsAndCountsThem() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", FraudFeedbackLabel.INCONCLUSIVE, FROM.plusSeconds(1)),
                feedback("feedback-2", "txn-2", FraudFeedbackLabel.NEEDS_MORE_INFO, FROM.plusSeconds(2))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.excludedUnresolvedCount()).isEqualTo(2);
    }

    @Test
    void nullLabelIsExcludedForGovernanceReview() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", null, FROM.plusSeconds(1))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.excludedGovernanceReviewCount()).isEqualTo(1);
    }

    @Test
    void missingOptionalDiagnosticFieldsAreAllowed() {
        FraudFeedbackRecord feedback = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        feedback.setFraudScore(null);
        feedback.setRiskLevel(null);
        feedback.setEngineIntelligenceStatus(null);
        feedback.setAnalystRecommendation(null);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(feedback));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().fraudScore()).isNull();
        assertThat(result.records().getFirst().analystRecommendation()).isNull();
    }

    @Test
    void missingRequiredFieldIsSkippedExplicitly() {
        FraudFeedbackRecord missingTransaction = feedback("feedback-1", null, FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        FraudFeedbackRecord missingReason = feedback("feedback-2", "txn-2", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1));
        missingReason.setDecisionReasonCodes(List.of());
        FraudFeedbackRecord nullReason = feedback("feedback-3", "txn-3", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(2));
        nullReason.setDecisionReasonCodes(null);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(missingTransaction, missingReason, nullReason));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.skippedMissingRequiredFieldCount()).isEqualTo(3);
        assertThat(result.skippedInvalidSourceRecordCount()).isZero();
    }

    @Test
    void validFraudCompatibleReasonCodeIsExported() {
        FraudFeedbackRecord feedback = feedback(
                "feedback-1",
                "txn-1",
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FROM
        );
        feedback.setDecisionReasonCodes(List.of("CUSTOMER_CONFIRMED_FRAUD"));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(feedback));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().decisionReasonCodes()).containsExactly("CUSTOMER_CONFIRMED_FRAUD");
    }

    @Test
    void validLegitimateCompatibleReasonCodeIsExported() {
        FraudFeedbackRecord feedback = feedback(
                "feedback-1",
                "txn-1",
                FraudFeedbackLabel.CONFIRMED_LEGITIMATE,
                FROM
        );
        feedback.setDecisionReasonCodes(List.of("CUSTOMER_CONFIRMED_LEGITIMATE"));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(feedback));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().decisionReasonCodes()).containsExactly("CUSTOMER_CONFIRMED_LEGITIMATE");
    }

    @Test
    void fraudLabelWithLegitimateOnlyReasonCodeIsSkippedAsInvalidSource() {
        FraudFeedbackRecord feedback = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        feedback.setDecisionReasonCodes(List.of("CUSTOMER_CONFIRMED_LEGITIMATE"));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(feedback));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isFalse();
        assertThat(result.rawRowsRead()).isEqualTo(1);
        assertThat(result.recordsReturned()).isZero();
        assertThat(result.skippedMissingRequiredFieldCount()).isZero();
        assertThat(result.skippedInvalidSourceRecordCount()).isEqualTo(1);
    }

    @Test
    void legitimateLabelWithFraudOnlyReasonCodeIsSkippedAsInvalidSource() {
        FraudFeedbackRecord feedback = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_LEGITIMATE, FROM);
        feedback.setDecisionReasonCodes(List.of("CUSTOMER_CONFIRMED_FRAUD"));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(feedback));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.skippedInvalidSourceRecordCount()).isEqualTo(1);
    }

    @Test
    void unknownReasonCodeIsSkippedAsInvalidSource() {
        FraudFeedbackRecord feedback = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        feedback.setDecisionReasonCodes(List.of("RANDOM_REASON"));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(feedback));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.skippedInvalidSourceRecordCount()).isEqualTo(1);
    }

    @Test
    void unsafeReasonCodeIsSkippedAsInvalidSourceAndNotSerialized() {
        FraudFeedbackRecord feedback = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        feedback.setDecisionReasonCodes(List.of("TOKEN_SECRET"));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(feedback));

        FeedbackDatasetBuildResult result = builder.build(request(10));
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result);

        assertThat(result.records()).isEmpty();
        assertThat(result.skippedInvalidSourceRecordCount()).isEqualTo(1);
        assertThat(jsonl)
                .contains("\"skippedInvalidSourceRecordCount\":1")
                .doesNotContain("TOKEN_SECRET");
    }

    @Test
    void storeFailureReturnsBoundedFailure() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenThrow(new RuntimeException("db raw token"));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).isEqualTo(FeedbackDatasetBuildFailureReason.FEEDBACK_STORE_UNAVAILABLE);
        assertThat(result.records()).isEmpty();
        assertThat(result.toString()).doesNotContain("db raw token");
    }

    @Test
    void invalidRequestReturnsInvalidRequestFailure() {
        FeedbackDatasetBuildResult result = builder.build(new FeedbackDatasetBuildRequest(TO, FROM, 10));

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).isEqualTo(FeedbackDatasetBuildFailureReason.INVALID_REQUEST);
        assertThat(result.records()).isEmpty();
    }

    @Test
    void emptyEligibleResultIsSuccessfulEmptyDataset() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of());

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isFalse();
        assertThat(result.recordsReturned()).isZero();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void truncatedResultSetsTruncatedAndCapsRecords() {
        when(store.findBoundedByCreatedAt(FROM, TO, 1)).thenReturn(List.of(
                feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1)),
                feedback("feedback-2", "txn-2", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(2))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(1));

        assertThat(result.truncated()).isTrue();
        assertThat(result.rawRowsRead()).isEqualTo(2);
        assertThat(result.recordsReturned()).isEqualTo(1);
        assertThat(result.records()).hasSize(1);
    }

    @Test
    void maxRecordsIsCappedAtHardLimit() {
        when(store.findBoundedByCreatedAt(FROM, TO, FeedbackDatasetBuildRequest.HARD_MAX_RECORDS)).thenReturn(List.of());

        builder.build(new FeedbackDatasetBuildRequest(FROM, TO, 5000));

        verify(store).findBoundedByCreatedAt(FROM, TO, FeedbackDatasetBuildRequest.HARD_MAX_RECORDS);
    }

    @Test
    void dateRangeCapIsEnforced() {
        FeedbackDatasetBuildResult result = builder.build(new FeedbackDatasetBuildRequest(
                FROM,
                FROM.plusSeconds(32L * 24L * 60L * 60L),
                10
        ));

        assertThat(result.failureReason()).isEqualTo(FeedbackDatasetBuildFailureReason.INVALID_REQUEST);
    }

    @Test
    void outputOrderFollowsCreatedAtAndFeedbackIdQueryOrder() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1)),
                feedback("feedback-b", "txn-b", FraudFeedbackLabel.CONFIRMED_LEGITIMATE, FROM.plusSeconds(2))
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).extracting(FeedbackDatasetRecord::evaluationRecordId)
                .containsExactly(
                        FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-a"),
                        FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-b")
                );
    }

    @Test
    void recordContainsPseudonymousReferencesNotRawIds() {
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(
                feedback("feedback-secret-1", "txn-secret-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM)
        ));

        FeedbackDatasetRecord record = builder.build(request(10)).records().getFirst();

        assertThat(record.evaluationRecordId()).doesNotContain("feedback-secret-1");
        assertThat(record.transactionReference()).doesNotContain("txn-secret-1");
    }

    @Test
    void exportsModelIdentityOnlyAfterExactEvidenceAgreement() {
        FraudFeedbackRecord source = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-1", FROM.minusSeconds(1));
        source.setMlModelName(MODEL_NAME);
        source.setMlModelVersion("2026-06-25.v1");
        source.setMlFeatureContractVersion(FEATURE_CONTRACT_VERSION);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(evidence(
                "event-1",
                "txn-1",
                FROM.minusSeconds(1),
                "2026-06-25.v1",
                0.81
        )));

        FeedbackDatasetRecord record = builder.build(request(10)).records().getFirst();

        assertThat(record.mlModelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(record.mlModelVersion()).isEqualTo("2026-06-25.v1");
        assertThat(record.mlFeatureContractVersion()).isEqualTo("feature-contract-v2");
    }

    @Test
    void historicalAvailableMlSnapshotWithoutLineageIsNotCurrentDatasetObservation() {
        FraudFeedbackRecord source = feedback(
                "feedback-historical",
                "txn-historical",
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FROM
        );
        source.setEngineIntelligenceStatus(EngineIntelligenceResponseStatus.AVAILABLE);
        source.setAgreementStatus(EngineIntelligenceAgreementStatus.DISAGREEMENT);
        source.setRiskMismatchStatus(EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH);
        source.setScoreDeltaBucket(EngineIntelligenceScoreDeltaBucket.LARGE);
        clearOccurrence(source);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isFalse();
        assertThat(result.rawRowsRead()).isEqualTo(1);
        assertThat(result.records()).isEmpty();
        assertThat(result.skippedMissingRequiredFieldCount()).isEqualTo(1);
        assertThat(result.skippedInvalidSourceRecordCount()).isZero();
        assertThat(new FeedbackDatasetJsonlWriter().writeJsonl(result))
                .doesNotContain("\"type\":\"DATASET_RECORD\"");
        verifyNoInteractions(evidenceRepository);
    }

    @Test
    void partialOccurrenceLineageIsCountedAsInvalidSource() {
        FraudFeedbackRecord source = feedback(
                "feedback-partial",
                "txn-partial",
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FROM
        );
        ReflectionTestUtils.setField(source, "sourceEventFingerprint", null);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.rawRowsRead()).isEqualTo(1);
        assertThat(result.records()).isEmpty();
        assertThat(result.skippedMissingRequiredFieldCount()).isZero();
        assertThat(result.skippedInvalidSourceRecordCount()).isEqualTo(1);
        verifyNoInteractions(evidenceRepository);
    }

    @Test
    void partialMlModelIdentitySnapshotRemainsAsMalformedEvidence() {
        FraudFeedbackRecord source = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        source.setMlModelName("python-logistic-fraud-model");
        source.setMlFeatureContractVersion("feature-contract-v2");
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.mlPredictionEvidenceStatus())
                    .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED);
            assertThat(record.mlPredictionEvidenceOmissionReason()).isNull();
            assertThat(record.mlModelName()).isNull();
            assertThat(record.mlModelVersion()).isNull();
            assertThat(record.mlFeatureContractVersion()).isNull();
        });
        assertThat(result.skippedInvalidSourceRecordCount()).isZero();
        assertThat(result.skippedMissingRequiredFieldCount()).isZero();
    }

    @Test
    void mixedTransactionsRetainDifferentMlModelVersions() {
        FraudFeedbackRecord first = feedback("feedback-1", "txn-1", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(first, "event-1", FROM.minusSeconds(2));
        first.setMlModelName(MODEL_NAME);
        first.setMlModelVersion("2026-06-25.v1");
        first.setMlFeatureContractVersion(FEATURE_CONTRACT_VERSION);
        FraudFeedbackRecord second = feedback("feedback-2", "txn-2", FraudFeedbackLabel.CONFIRMED_LEGITIMATE, FROM.plusSeconds(1));
        captureOccurrence(second, "event-2", FROM.minusSeconds(1));
        second.setMlModelName(MODEL_NAME);
        second.setMlModelVersion("2026-06-26.v1");
        second.setMlFeatureContractVersion(FEATURE_CONTRACT_VERSION);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(first, second));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-1", "txn-1", FROM.minusSeconds(2), "2026-06-25.v1", 0.81),
                evidence("event-2", "txn-2", FROM.minusSeconds(1), "2026-06-26.v1", 0.72)
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).extracting(FeedbackDatasetRecord::mlModelVersion)
                .containsExactly("2026-06-25.v1", "2026-06-26.v1");
    }

    @Test
    void exactOccurrenceAUsesEvidenceAAndNeverLaterOccurrenceB() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-shared", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(2));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-shared", FROM.minusSeconds(2), "model-a", 0.91)
        ));

        FeedbackDatasetRecord record = builder.build(request(10)).records().getFirst();

        assertThat(record.mlModelVersion()).isEqualTo("model-a");
        assertThat(requestedEvidenceIds()).containsExactly("event-a").doesNotContain("event-b");
    }

    @Test
    void exportsPersistedSameOccurrenceRulesEvidenceWithoutUsingPlatformRiskAsFallback() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-shared", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(2));
        source.setRiskLevel(RiskLevel.CRITICAL);
        source.setRulesEngineStatus(FraudEngineStatus.AVAILABLE);
        source.setRulesRiskLevel(RiskLevel.LOW);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-shared", FROM.minusSeconds(2), "model-a", 0.91)
        ));

        FeedbackDatasetRecord record = builder.build(request(10)).records().getFirst();

        assertThat(record.rulesEvidenceStatus()).isEqualTo(FeedbackDatasetRulesEvidenceStatus.AVAILABLE);
        assertThat(record.rulesRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(record.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void missingHistoricalRulesEvidenceRemainsUnavailableInsteadOfBecomingLowRisk() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-a", FROM.minusSeconds(1), "model-a", 0.91)
        ));

        FeedbackDatasetRecord record = builder.build(request(10)).records().getFirst();

        assertThat(record.rulesEvidenceStatus()).isEqualTo(FeedbackDatasetRulesEvidenceStatus.UNAVAILABLE);
        assertThat(record.rulesRiskLevel()).isNull();
    }

    @Test
    void malformedRulesEvidenceIsRejectedInsteadOfBecomingUnavailableOrLowRisk() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        source.setRulesEngineStatus(FraudEngineStatus.AVAILABLE);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-a", FROM.minusSeconds(1), "model-a", 0.91)
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).isEmpty();
        assertThat(result.skippedInvalidSourceRecordCount()).isEqualTo(1);
    }

    @Test
    void exactReplayResolvesDeterministically() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-a", FROM.minusSeconds(1), "model-a", 0.91)
        ));

        FeedbackDatasetBuildResult first = builder.build(request(10));
        FeedbackDatasetBuildResult replay = builder.build(request(10));

        assertThat(replay.records()).isEqualTo(first.records());
        verify(evidenceRepository, times(2)).findAllById(any());
    }

    @Test
    void missingProjectionWithoutFeedbackIdentityIsUnexpectedRatherThanLegitimateAbsence() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of());

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.mlPredictionEvidenceStatus())
                    .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY);
            assertThat(record.mlPredictionEvidenceOmissionReason()).isNull();
            assertThat(record.mlModelName()).isNull();
            assertThat(record.mlModelVersion()).isNull();
            assertThat(record.mlFeatureContractVersion()).isNull();
        });
        assertThat(result.skippedInvalidSourceRecordCount()).isZero();
    }

    @Test
    void authoritativeOmissionReasonsMapToBoundedDatasetStatuses() {
        List<MlPredictionEvidenceOmissionReason> reasons = List.of(
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED,
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_ENRICHMENT_UNAVAILABLE,
                MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE,
                MlPredictionEvidenceOmissionReason.SOURCE_TIMESTAMP_MISSING,
                MlPredictionEvidenceOmissionReason.INVALID_SCORE,
                MlPredictionEvidenceOmissionReason.IDENTITY_VALIDATION_FAILURE,
                MlPredictionEvidenceOmissionReason.EVIDENCE_SOURCE_INTEGRITY_FAILURE,
                MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED
        );
        List<FraudFeedbackRecord> sources = new java.util.ArrayList<>();
        for (int index = 0; index < reasons.size(); index++) {
            FraudFeedbackRecord source = feedback(
                    "feedback-" + index,
                    "txn-" + index,
                    FraudFeedbackLabel.CONFIRMED_FRAUD,
                    FROM.plusSeconds(index)
            );
            captureOccurrence(source, "event-" + index, FROM.minusSeconds(index + 1L));
            source.setMlPredictionEvidenceOmissionReason(reasons.get(index));
            sources.add(source);
        }
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(sources);
        when(evidenceRepository.findAllById(any())).thenReturn(List.of());

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).extracting(FeedbackDatasetRecord::mlPredictionEvidenceStatus)
                .containsExactly(
                        FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT,
                        FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY,
                        FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY,
                        FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED,
                        FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED,
                        FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED,
                        FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED,
                        FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED
                );
        assertThat(result.records()).extracting(FeedbackDatasetRecord::mlPredictionEvidenceOmissionReason)
                .containsExactlyElementsOf(reasons);
        assertThat(result.skippedInvalidSourceRecordCount()).isZero();
    }

    @Test
    void recoveredExactEvidenceChangesUnexpectedMissingToAvailableWithoutRewritingEarlierBuild() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(evidence("event-a", "txn-a", FROM.minusSeconds(1), "model-a", 0.91)));

        FeedbackDatasetBuildResult beforeRecovery = builder.build(request(10));
        FeedbackDatasetBuildResult afterRecovery = builder.build(request(10));

        assertThat(beforeRecovery.records()).singleElement().satisfies(record -> {
            assertThat(record.mlPredictionEvidenceStatus())
                    .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY);
            assertThat(record.mlPredictionEvidenceOmissionReason()).isNull();
        });
        assertThat(afterRecovery.records()).singleElement().satisfies(record -> {
            assertThat(record.mlPredictionEvidenceStatus())
                    .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE);
            assertThat(record.mlModelVersion()).isEqualTo("model-a");
            assertThat(record.mlModelArtifactSha256()).isEqualTo(MODEL_ARTIFACT_SHA256);
        });
    }

    @Test
    void missingEvidenceWithCapturedMlIdentityRemainsExplicitlyRepresented() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        setModelIdentity(source, "model-a");
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of());

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).singleElement().extracting(FeedbackDatasetRecord::mlPredictionEvidenceStatus)
                .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY);
        assertThat(result.skippedInvalidSourceRecordCount()).isZero();
    }

    @Test
    void modelIdentityMismatchRemainsExplicitWithoutMergingSources() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        setModelIdentity(source, "model-a");
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-a", FROM.minusSeconds(1), "model-b", 0.91)
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).singleElement().extracting(FeedbackDatasetRecord::mlPredictionEvidenceStatus)
                .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH);
        assertThat(result.skippedInvalidSourceRecordCount()).isZero();
    }

    @Test
    void transactionOwnershipMismatchRemainsExplicit() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-other", FROM.minusSeconds(1), "model-a", 0.91)
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).singleElement().extracting(FeedbackDatasetRecord::mlPredictionEvidenceStatus)
                .isEqualTo(FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH);
        assertThat(result.skippedInvalidSourceRecordCount()).isZero();
    }

    @Test
    void evidenceStoreFailureReturnsEvidenceStoreFailureWithoutPartialRecords() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any()))
                .thenThrow(new DataAccessResourceFailureException("raw database detail"));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
                .isEqualTo(FeedbackDatasetBuildFailureReason.ML_PREDICTION_EVIDENCE_STORE_UNAVAILABLE);
        assertThat(result.records()).isEmpty();
        assertThat(result.toString()).doesNotContain("raw database detail");
    }

    @Test
    void evidenceInvariantFailureReturnsIntegrityFailureWithoutPartialRecords() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenThrow(new IllegalStateException("raw invariant detail"));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failureReason())
                .isEqualTo(FeedbackDatasetBuildFailureReason.ML_PREDICTION_EVIDENCE_INTEGRITY_FAILURE);
        assertThat(result.records()).isEmpty();
        assertThat(result.toString()).doesNotContain("raw invariant detail");
    }

    @Test
    void duplicateExactEvidenceReturnsIntegrityFailure() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        captureOccurrence(source, "event-a", FROM.minusSeconds(1));
        MlPredictionEvidenceProjection projection = evidence(
                "event-a",
                "txn-a",
                FROM.minusSeconds(1),
                "model-a",
                0.91
        );
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(projection, projection));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.failureReason())
                .isEqualTo(FeedbackDatasetBuildFailureReason.ML_PREDICTION_EVIDENCE_INTEGRITY_FAILURE);
        assertThat(result.records()).isEmpty();
    }

    @Test
    void sameTransactionOccurrencesStaySeparatedBySourceEventId() {
        FraudFeedbackRecord first = feedback("feedback-a", "txn-shared", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        FraudFeedbackRecord second = feedback(
                "feedback-b",
                "txn-shared",
                FraudFeedbackLabel.CONFIRMED_LEGITIMATE,
                FROM.plusSeconds(1)
        );
        captureOccurrence(first, "event-a", FROM.minusSeconds(2));
        captureOccurrence(second, "event-b", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(first, second));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-shared", FROM.minusSeconds(2), "model-a", 0.91),
                evidence("event-b", "txn-shared", FROM.minusSeconds(1), "model-b", 0.21)
        ));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.records()).extracting(FeedbackDatasetRecord::mlModelVersion)
                .containsExactly("model-a", "model-b");
        assertThat(requestedEvidenceIds()).containsExactly("event-a", "event-b");
    }

    @Test
    void evidenceLookupIsOneBoundedBatchAfterDatasetLimit() {
        FraudFeedbackRecord first = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        FraudFeedbackRecord beyondLimit = feedback("feedback-b", "txn-b", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM.plusSeconds(1));
        captureOccurrence(first, "event-a", FROM.minusSeconds(2));
        captureOccurrence(beyondLimit, "event-b", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 1)).thenReturn(List.of(first, beyondLimit));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(
                evidence("event-a", "txn-a", FROM.minusSeconds(2), "model-a", 0.91)
        ));

        FeedbackDatasetBuildResult result = builder.build(request(1));

        assertThat(result.records()).hasSize(1);
        assertThat(requestedEvidenceIds()).containsExactly("event-a");
    }

    @Test
    void evidenceLookupIncludesOnlyExactOccurrenceEligibleRows() {
        FraudFeedbackRecord eligible = feedback(
                "feedback-eligible",
                "txn-eligible",
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FROM
        );
        captureOccurrence(eligible, "event-eligible", FROM.minusSeconds(1));
        FraudFeedbackRecord missing = feedback(
                "feedback-missing",
                "txn-missing",
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FROM.plusSeconds(1)
        );
        clearOccurrence(missing);
        FraudFeedbackRecord invalid = feedback(
                "feedback-invalid",
                "txn-invalid",
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FROM.plusSeconds(2)
        );
        ReflectionTestUtils.setField(invalid, "sourceEventCreatedAtNano", null);
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(eligible, missing, invalid));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(evidence(
                "event-eligible", "txn-eligible", FROM.minusSeconds(1), "model-a", 0.91
        )));

        FeedbackDatasetBuildResult result = builder.build(request(10));

        assertThat(result.rawRowsRead()).isEqualTo(3);
        assertThat(result.recordsReturned()).isEqualTo(1);
        assertThat(result.skippedMissingRequiredFieldCount()).isEqualTo(1);
        assertThat(result.skippedInvalidSourceRecordCount()).isEqualTo(1);
        assertThat(requestedEvidenceIds()).containsExactly("event-eligible");
    }

    @Test
    void internalOccurrenceIdentifiersDoNotLeakIntoJsonl() {
        FraudFeedbackRecord source = feedback("feedback-a", "txn-a", FraudFeedbackLabel.CONFIRMED_FRAUD, FROM);
        source.setCorrelationId("correlation-secret");
        captureOccurrence(source, "source-event-secret", FROM.minusSeconds(1));
        when(store.findBoundedByCreatedAt(FROM, TO, 10)).thenReturn(List.of(source));
        when(evidenceRepository.findAllById(any())).thenReturn(List.of(evidence(
                "source-event-secret",
                "txn-a",
                "correlation-secret",
                FROM.minusSeconds(1),
                "model-a",
                0.91
        )));

        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(builder.build(request(10)));

        assertThat(jsonl).doesNotContain("source-event-secret", "correlation-secret", "sourceEventId");
    }

    private FeedbackDatasetBuildRequest request(int maxRecords) {
        return new FeedbackDatasetBuildRequest(FROM, TO, maxRecords);
    }

    private FraudFeedbackRecord feedback(
            String feedbackId,
            String transactionId,
            FraudFeedbackLabel label,
            Instant createdAt
    ) {
        FraudFeedbackRecord record = new FraudFeedbackRecord();
        record.setFeedbackId(feedbackId);
        record.setTransactionId(transactionId);
        record.setFeedbackLabel(label);
        record.setCreatedAt(createdAt);
        record.setDecisionReasonCodes(defaultReasonCodes(label));
        record.setFraudScore(0.91);
        record.setRiskLevel(RiskLevel.HIGH);
        captureOccurrence(record, "event-" + feedbackId, createdAt.minusSeconds(1));
        return record;
    }

    private void clearOccurrence(FraudFeedbackRecord record) {
        ReflectionTestUtils.setField(record, "sourceEventId", null);
        ReflectionTestUtils.setField(record, "sourceEventCreatedAt", null);
        ReflectionTestUtils.setField(record, "sourceEventCreatedAtEpochSecond", null);
        ReflectionTestUtils.setField(record, "sourceEventCreatedAtNano", null);
        ReflectionTestUtils.setField(record, "sourceEventFingerprint", null);
    }

    private void captureOccurrence(FraudFeedbackRecord record, String sourceEventId, Instant sourceEventCreatedAt) {
        ReflectionTestUtils.setField(record, "sourceEventId", sourceEventId);
        ReflectionTestUtils.setField(record, "sourceEventCreatedAt", sourceEventCreatedAt.toString());
        ReflectionTestUtils.setField(record, "sourceEventCreatedAtEpochSecond", sourceEventCreatedAt.getEpochSecond());
        ReflectionTestUtils.setField(record, "sourceEventCreatedAtNano", sourceEventCreatedAt.getNano());
        ReflectionTestUtils.setField(record, "sourceEventFingerprint", "a".repeat(64));
    }

    private void setModelIdentity(FraudFeedbackRecord record, String modelVersion) {
        record.setMlModelName(MODEL_NAME);
        record.setMlModelVersion(modelVersion);
        record.setMlFeatureContractVersion(FEATURE_CONTRACT_VERSION);
    }

    private MlPredictionEvidenceProjection evidence(
            String sourceEventId,
            String transactionId,
            Instant sourceEventCreatedAt,
            String modelVersion,
            double score
    ) {
        return evidence(sourceEventId, transactionId, null, sourceEventCreatedAt, modelVersion, score);
    }

    private MlPredictionEvidenceProjection evidence(
            String sourceEventId,
            String transactionId,
            String correlationId,
            Instant sourceEventCreatedAt,
            String modelVersion,
            double score
    ) {
        return new MlPredictionEvidenceProjection(
                sourceEventId,
                transactionId,
                correlationId == null ? "correlation-" + sourceEventId : correlationId,
                sourceEventCreatedAt.toString(),
                score,
                score >= 0.8 ? RiskLevel.HIGH : RiskLevel.LOW,
                MODEL_NAME,
                modelVersion,
                FEATURE_CONTRACT_VERSION,
                MODEL_ARTIFACT_SHA256,
                EXECUTED_AT.toString(),
                BUILT_AT
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> requestedEvidenceIds() {
        ArgumentCaptor<Iterable<String>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(evidenceRepository).findAllById(captor.capture());
        return StreamSupport.stream(captor.getValue().spliterator(), false).toList();
    }

    private List<String> defaultReasonCodes(FraudFeedbackLabel label) {
        if (label == FraudFeedbackLabel.CONFIRMED_LEGITIMATE) {
            return List.of("ANALYST_CONFIRMED_LEGITIMATE");
        }
        if (label == FraudFeedbackLabel.INCONCLUSIVE) {
            return List.of("ANALYST_INCONCLUSIVE");
        }
        if (label == FraudFeedbackLabel.NEEDS_MORE_INFO) {
            return List.of("ANALYST_NEEDS_MORE_INFO");
        }
        return List.of("ANALYST_CONFIRMED_FRAUD");
    }
}
