package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackDatasetRecordContractTest {

    @Test
    void datasetRecordRejectsUnsupportedDatasetVersion() {
        assertThatThrownBy(() -> record(
                "feedback-dataset-v1",
                null,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("ANALYST_CONFIRMED_FRAUD")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void datasetRecordRejectsInvalidPlatformScore() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1, 0.12345}) {
            assertThatThrownBy(() -> record(
                    FeedbackDatasetBuilder.DATASET_VERSION,
                    invalid,
                    FraudFeedbackLabel.CONFIRMED_FRAUD,
                    FeedbackEvaluationLabel.POSITIVE_FRAUD,
                    List.of("ANALYST_CONFIRMED_FRAUD")
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void datasetRecordContainsOnlyAllowedFieldNames() {
        assertThat(FeedbackDatasetRecord.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "datasetVersion",
                        "evaluationRecordId",
                        "transactionReference",
                        "feedbackLabel",
                        "evaluationLabel",
                        "decisionReasonCodes",
                        "feedbackCreatedAt",
                        "fraudScore",
                        "riskLevel",
                        "alertRecommended",
                        "engineIntelligenceStatus",
                        "agreementStatus",
                        "riskMismatchStatus",
                        "scoreDeltaBucket",
                        "rulesEvidenceStatus",
                        "rulesRiskLevel",
                        "mlPredictionEvidenceStatus",
                        "mlPredictionEvidenceOmissionReason",
                        "mlPredictionScore",
                        "mlPredictionRiskLevel",
                        "mlPredictionExecutedAt",
                        "mlModelName",
                        "mlModelVersion",
                        "mlFeatureContractVersion",
                        "analystRecommendationStatus",
                        "analystRecommendation",
                        "analystRecommendationVersion",
                        "analystRecommendationGeneratedAt",
                        "analystRecommendationReasonCodes",
                        "scoredAt",
                        "transactionTimestamp"
                );
    }

    @Test
    void datasetRecordDoesNotContainForbiddenRawOrDecisionFields() {
        assertThat(FeedbackDatasetRecord.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain(
                        "transactionId",
                        "feedbackId",
                        "customerId",
                        "correlationId",
                        "createdBy",
                        "notes",
                        "rawNotes",
                        "analystDecision",
                        "labelSource",
                        "feedbackStatus",
                        "rawMlRequest",
                        "rawMlResponse",
                        "rawFeatureVector",
                        "rawEvidence",
                        "groundTruth",
                        "trainingLabel",
                        "finalDecision",
                        "paymentDecision",
                        "paymentAuthorization",
                        "token",
                        "secret",
                        "password"
                );
    }

    @Test
    void evaluationLabelDoesNotContainGroundTruthOrTrainingNames() {
        assertThat(FeedbackEvaluationLabel.class.getEnumConstants())
                .extracting(Enum::name)
                .containsExactly("POSITIVE_FRAUD", "NEGATIVE_LEGITIMATE")
                .allSatisfy(name -> assertThat(name)
                        .doesNotContain("GROUND", "TRUTH", "TRAINING", "FINAL", "PAYMENT"));
    }

    @Test
    void confirmedFraudPositiveFraudPairIsAccepted() {
        assertThatCode(() -> record(FraudFeedbackLabel.CONFIRMED_FRAUD, FeedbackEvaluationLabel.POSITIVE_FRAUD))
                .doesNotThrowAnyException();
    }

    @Test
    void confirmedLegitimateNegativeLegitimatePairIsAccepted() {
        assertThatCode(() -> record(FraudFeedbackLabel.CONFIRMED_LEGITIMATE, FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE))
                .doesNotThrowAnyException();
    }

    @Test
    void confirmedFraudNegativeLegitimatePairIsRejected() {
        assertThatThrownBy(() -> record(FraudFeedbackLabel.CONFIRMED_FRAUD, FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmedLegitimatePositiveFraudPairIsRejected() {
        assertThatThrownBy(() -> record(FraudFeedbackLabel.CONFIRMED_LEGITIMATE, FeedbackEvaluationLabel.POSITIVE_FRAUD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unresolvedLabelsCannotBecomePositiveOrNegativeEvaluationRows() {
        assertThatThrownBy(() -> record(FraudFeedbackLabel.INCONCLUSIVE, FeedbackEvaluationLabel.POSITIVE_FRAUD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(FraudFeedbackLabel.INCONCLUSIVE, FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(FraudFeedbackLabel.NEEDS_MORE_INFO, FeedbackEvaluationLabel.POSITIVE_FRAUD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(FraudFeedbackLabel.NEEDS_MORE_INFO, FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTenDecisionReasonCodesWhenTheyAreValidAndCompatible() {
        assertThatCode(() -> record(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of(
                        "ANALYST_CONFIRMED_FRAUD",
                        "CUSTOMER_CONFIRMED_FRAUD",
                        "DOCUMENTATION_CONFIRMED_FRAUD",
                        "CHARGEBACK_SIGNAL",
                        "ACCOUNT_TAKEOVER_INDICATOR",
                        "ANALYST_CONFIRMED_FRAUD",
                        "CUSTOMER_CONFIRMED_FRAUD",
                        "DOCUMENTATION_CONFIRMED_FRAUD",
                        "CHARGEBACK_SIGNAL",
                        "ACCOUNT_TAKEOVER_INDICATOR"
                )
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsElevenDecisionReasonCodes() {
        assertThatThrownBy(() -> record(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of(
                        "ANALYST_CONFIRMED_FRAUD",
                        "CUSTOMER_CONFIRMED_FRAUD",
                        "DOCUMENTATION_CONFIRMED_FRAUD",
                        "CHARGEBACK_SIGNAL",
                        "ACCOUNT_TAKEOVER_INDICATOR",
                        "ANALYST_CONFIRMED_FRAUD",
                        "CUSTOMER_CONFIRMED_FRAUD",
                        "DOCUMENTATION_CONFIRMED_FRAUD",
                        "CHARGEBACK_SIGNAL",
                        "ACCOUNT_TAKEOVER_INDICATOR",
                        "ANALYST_CONFIRMED_FRAUD"
                )
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLabelIncompatibleDecisionReasonCodes() {
        assertThatThrownBy(() -> record(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("CUSTOMER_CONFIRMED_LEGITIMATE")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(
                FraudFeedbackLabel.CONFIRMED_LEGITIMATE,
                FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE,
                List.of("CUSTOMER_CONFIRMED_FRAUD")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownOrUnsafeDecisionReasonCodes() {
        assertThatThrownBy(() -> record(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("RANDOM_REASON")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("TOKEN_SECRET")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundedMlModelIdentitySnapshotFields() {
        FeedbackDatasetRecord record = recordWithMlIdentity(
                "python-logistic-fraud-model",
                "2026-06-25.v1",
                "feature-contract-v2"
        );

        assertThat(record.mlModelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(record.mlModelVersion()).isEqualTo("2026-06-25.v1");
        assertThat(record.mlFeatureContractVersion()).isEqualTo("feature-contract-v2");
    }

    @Test
    void acceptsExplicitlyAbsentMlModelIdentitySnapshot() {
        FeedbackDatasetRecord record = recordWithMlIdentity(null, null, null);

        assertThat(record.mlModelName()).isNull();
        assertThat(record.mlModelVersion()).isNull();
        assertThat(record.mlFeatureContractVersion()).isNull();
    }

    @Test
    void availableMlPredictionEvidenceRequiresCompleteDirectSignal() {
        assertThatCode(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE,
                0.8123,
                RiskLevel.HIGH,
                Instant.parse("2026-06-01T00:00:01Z"),
                "python-logistic-fraud-model",
                "2026-06-25.v1",
                "feature-contract-v2"
        )).doesNotThrowAnyException();

        Object[][] incomplete = {
                {null, RiskLevel.HIGH, Instant.parse("2026-06-01T00:00:01Z")},
                {0.8123, null, Instant.parse("2026-06-01T00:00:01Z")},
                {0.8123, RiskLevel.HIGH, null}
        };
        for (Object[] values : incomplete) {
            assertThatThrownBy(() -> recordWithMlEvidence(
                    FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE,
                    (Double) values[0],
                    (RiskLevel) values[1],
                    (Instant) values[2],
                    "python-logistic-fraud-model",
                    "2026-06-25.v1",
                    "feature-contract-v2"
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void absentMlPredictionEvidenceRejectsPredictionValuesAndIdentity() {
        assertThatCode(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT,
                null,
                null,
                null,
                null,
                null,
                null
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT,
                0.0,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT,
                null,
                null,
                null,
                "python-logistic-fraud-model",
                "2026-06-25.v1",
                "feature-contract-v2"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedNonAvailableResolutionStatusesEnterDatasetWithoutPredictionValues() {
        assertThatCode(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY,
                MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE,
                null,
                null,
                null,
                null,
                null,
                null
        )).doesNotThrowAnyException();
        assertThatCode(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED,
                MlPredictionEvidenceOmissionReason.IDENTITY_VALIDATION_FAILURE,
                null,
                null,
                null,
                null,
                null,
                null
        )).doesNotThrowAnyException();
        assertThatCode(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )).doesNotThrowAnyException();
    }

    @Test
    void legitimateAbsenceRequiresAuthoritativeProofAndStatusesMustMatchReasons() {
        assertThatThrownBy(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordWithMlEvidence(
                FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY,
                MlPredictionEvidenceOmissionReason.INVALID_SCORE,
                null,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPartialMlModelIdentitySnapshotFields() {
        String[][] partialIdentities = {
                {"python-logistic-fraud-model", null, null},
                {null, "2026-06-25.v1", null},
                {null, null, "feature-contract-v2"},
                {"python-logistic-fraud-model", "2026-06-25.v1", null},
                {"python-logistic-fraud-model", null, "feature-contract-v2"},
                {null, "2026-06-25.v1", "feature-contract-v2"}
        };

        for (String[] identity : partialIdentities) {
            assertThatThrownBy(() -> recordWithMlIdentity(identity[0], identity[1], identity[2]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ML model identity");
        }
    }

    @Test
    void rejectsUnsafeMlModelIdentitySnapshotValues() {
        assertThatThrownBy(() -> recordWithMlIdentity(
                "s3://bucket/model",
                "2026-06-25.v1",
                "feature-contract-v2"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordWithMlIdentity(
                "python-logistic-fraud-model",
                "v1/token-secret",
                "feature-contract-v2"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordWithMlIdentity(
                "python-logistic-fraud-model",
                "2026-06-25:v1",
                "feature-contract-v2"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordWithMlIdentity(
                "python-logistic-fraud-model",
                "2026-06-25.v1",
                "feature contract v2"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordWithMlIdentity(
                "python-logistic-fraud-model",
                "2026-06-25.v1",
                "f".repeat(97)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private FeedbackDatasetRecord record(FraudFeedbackLabel feedbackLabel, FeedbackEvaluationLabel evaluationLabel) {
        return record(feedbackLabel, evaluationLabel, List.of(reasonCode(feedbackLabel)));
    }

    private FeedbackDatasetRecord record(
            FraudFeedbackLabel feedbackLabel,
            FeedbackEvaluationLabel evaluationLabel,
            List<String> decisionReasonCodes
    ) {
        return record(
                FeedbackDatasetBuilder.DATASET_VERSION,
                null,
                feedbackLabel,
                evaluationLabel,
                decisionReasonCodes
        );
    }

    private FeedbackDatasetRecord record(
            String datasetVersion,
            Double fraudScore,
            FraudFeedbackLabel feedbackLabel,
            FeedbackEvaluationLabel evaluationLabel,
            List<String> decisionReasonCodes
    ) {
        return new FeedbackDatasetRecord(
                datasetVersion,
                FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-1"),
                FeedbackDatasetIdentifierHasher.transactionReference("txn-1"),
                feedbackLabel,
                evaluationLabel,
                decisionReasonCodes,
                Instant.parse("2026-06-01T00:00:00Z"),
                fraudScore,
                null,
                null,
                null,
                null,
                null,
                null,
                FeedbackDatasetRulesEvidenceStatus.UNAVAILABLE,
                null,
                FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT,
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null
        );
    }

    private FeedbackDatasetRecord recordWithMlIdentity(
            String mlModelName,
            String mlModelVersion,
            String mlFeatureContractVersion
    ) {
        boolean absent = mlModelName == null && mlModelVersion == null && mlFeatureContractVersion == null;
        return recordWithMlEvidence(
                absent
                        ? FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT
                        : FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE,
                absent ? null : 0.8123,
                absent ? null : RiskLevel.HIGH,
                absent ? null : Instant.parse("2026-06-01T00:00:01Z"),
                mlModelName,
                mlModelVersion,
                mlFeatureContractVersion
        );
    }

    private FeedbackDatasetRecord recordWithMlEvidence(
            FeedbackDatasetMlPredictionEvidenceStatus status,
            Double score,
            RiskLevel riskLevel,
            Instant executedAt,
            String mlModelName,
            String mlModelVersion,
            String mlFeatureContractVersion
    ) {
        return recordWithMlEvidence(
                status,
                status == FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT
                        ? MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
                        : null,
                score,
                riskLevel,
                executedAt,
                mlModelName,
                mlModelVersion,
                mlFeatureContractVersion
        );
    }

    private FeedbackDatasetRecord recordWithMlEvidence(
            FeedbackDatasetMlPredictionEvidenceStatus status,
            MlPredictionEvidenceOmissionReason omissionReason,
            Double score,
            RiskLevel riskLevel,
            Instant executedAt,
            String mlModelName,
            String mlModelVersion,
            String mlFeatureContractVersion
    ) {
        return new FeedbackDatasetRecord(
                FeedbackDatasetBuilder.DATASET_VERSION,
                FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-1"),
                FeedbackDatasetIdentifierHasher.transactionReference("txn-1"),
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("ANALYST_CONFIRMED_FRAUD"),
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                FeedbackDatasetRulesEvidenceStatus.UNAVAILABLE,
                null,
                status,
                omissionReason,
                score,
                riskLevel,
                executedAt,
                mlModelName,
                mlModelVersion,
                mlFeatureContractVersion,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null
        );
    }

    private String reasonCode(FraudFeedbackLabel feedbackLabel) {
        if (feedbackLabel == FraudFeedbackLabel.CONFIRMED_LEGITIMATE) {
            return "ANALYST_CONFIRMED_LEGITIMATE";
        }
        return "ANALYST_CONFIRMED_FRAUD";
    }
}
