package com.frauddetection.alert.feedback.governance;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackDatasetEligibilityPolicyTest {

    private final FeedbackDatasetEligibilityPolicy policy = new FeedbackDatasetEligibilityPolicy();

    @Test
    void confirmedLabelsAreEvaluationCandidatesOnly() {
        assertThat(policy.eligibilityFor(FraudFeedbackLabel.CONFIRMED_FRAUD))
                .isEqualTo(FeedbackDatasetEligibility.EVALUATION_CANDIDATE);
        assertThat(policy.eligibilityFor(FraudFeedbackLabel.CONFIRMED_LEGITIMATE))
                .isEqualTo(FeedbackDatasetEligibility.EVALUATION_CANDIDATE);
        assertThat(policy.eligibleForBinaryEvaluation(FraudFeedbackLabel.CONFIRMED_FRAUD)).isTrue();
        assertThat(policy.eligibleForBinaryEvaluation(FraudFeedbackLabel.CONFIRMED_LEGITIMATE)).isTrue();
    }

    @Test
    void unresolvedLabelsAreExcludedFromBinaryEvaluation() {
        assertThat(policy.eligibilityFor(FraudFeedbackLabel.INCONCLUSIVE))
                .isEqualTo(FeedbackDatasetEligibility.UNRESOLVED_EXCLUDED);
        assertThat(policy.eligibilityFor(FraudFeedbackLabel.NEEDS_MORE_INFO))
                .isEqualTo(FeedbackDatasetEligibility.UNRESOLVED_EXCLUDED);
        assertThat(policy.eligibleForBinaryEvaluation(FraudFeedbackLabel.INCONCLUSIVE)).isFalse();
        assertThat(policy.eligibleForBinaryEvaluation(FraudFeedbackLabel.NEEDS_MORE_INFO)).isFalse();
    }

    @Test
    void trainingExportIsFalseForAllFdp122Labels() {
        assertThat(Arrays.stream(FraudFeedbackLabel.values())
                .allMatch(label -> !policy.eligibleForTrainingExport(label)))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "notes",
            "rawNotes",
            "rawNotesExport",
            "groundTruth",
            "trainingLabel",
            "finalDecision",
            "paymentDecision",
            "paymentAuthorization",
            "rawMlRequest",
            "rawMlResponse",
            "rawFeatureVector",
            "rawEvidence",
            "customerPayload",
            "transactionPayload",
            "token",
            "secret",
            "password"
    })
    void dangerousFieldsAreNotEligibleDatasetFields(String fieldName) {
        assertThat(policy.eligibleDatasetField(fieldName)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "feedbackId",
            "transactionId",
            "feedbackLabel",
            "decisionReasonCodes",
            "riskLevel",
            "analystRecommendationStatus"
    })
    void explicitlyAllowedFieldsAreEligibleDatasetFields(String fieldName) {
        assertThat(policy.eligibleDatasetField(fieldName)).isTrue();
    }

    @Test
    void blankDatasetFieldIsNotEligible() {
        assertThat(policy.eligibleDatasetField(null)).isFalse();
        assertThat(policy.eligibleDatasetField(" ")).isFalse();
    }
}
