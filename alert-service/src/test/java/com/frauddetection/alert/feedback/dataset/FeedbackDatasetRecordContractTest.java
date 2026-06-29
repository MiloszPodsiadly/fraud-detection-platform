package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackDatasetRecordContractTest {

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

    private FeedbackDatasetRecord record(FraudFeedbackLabel feedbackLabel, FeedbackEvaluationLabel evaluationLabel) {
        return new FeedbackDatasetRecord(
                FeedbackDatasetBuilder.DATASET_VERSION,
                FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-1"),
                FeedbackDatasetIdentifierHasher.transactionReference("txn-1"),
                feedbackLabel,
                evaluationLabel,
                List.of(reasonCode(feedbackLabel)),
                Instant.parse("2026-06-01T00:00:00Z"),
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
