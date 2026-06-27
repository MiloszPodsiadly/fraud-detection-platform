package com.frauddetection.alert.feedback.governance;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import org.junit.jupiter.api.Test;

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

    @Test
    void notesAreNotEligibleDatasetFields() {
        assertThat(policy.eligibleDatasetField("notes")).isFalse();
        assertThat(policy.eligibleDatasetField(" NOTES ")).isFalse();
        assertThat(policy.eligibleDatasetField("feedbackLabel")).isTrue();
    }
}
