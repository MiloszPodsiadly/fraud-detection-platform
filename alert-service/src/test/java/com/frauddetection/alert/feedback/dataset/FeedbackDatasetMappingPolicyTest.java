package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.alert.feedback.governance.FeedbackDatasetEligibilityPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FeedbackDatasetMappingPolicyTest {

    private final FeedbackDatasetEligibilityPolicy eligibilityPolicy = mock(FeedbackDatasetEligibilityPolicy.class);
    private final FeedbackDatasetMappingPolicy mappingPolicy = new FeedbackDatasetMappingPolicy(new FeedbackDatasetEligibilityPolicy());

    @Test
    void confirmedFraudMapsToPositiveFraudAfterEligibilityPolicy() {
        assertThat(mappingPolicy.evaluationLabel(FraudFeedbackLabel.CONFIRMED_FRAUD))
                .contains(FeedbackEvaluationLabel.POSITIVE_FRAUD);
    }

    @Test
    void confirmedLegitimateMapsToNegativeLegitimateAfterEligibilityPolicy() {
        assertThat(mappingPolicy.evaluationLabel(FraudFeedbackLabel.CONFIRMED_LEGITIMATE))
                .contains(FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE);
    }

    @Test
    void inconclusiveIsExcluded() {
        assertThat(mappingPolicy.evaluationLabel(FraudFeedbackLabel.INCONCLUSIVE)).isEmpty();
    }

    @Test
    void needsMoreInfoIsExcluded() {
        assertThat(mappingPolicy.evaluationLabel(FraudFeedbackLabel.NEEDS_MORE_INFO)).isEmpty();
    }

    @Test
    void nullLabelIsExcludedForGovernanceReview() {
        assertThat(mappingPolicy.evaluationLabel(null)).isEmpty();
    }

    @Test
    void mappingConsultsFeedbackDatasetEligibilityPolicy() {
        FeedbackDatasetMappingPolicy policy = new FeedbackDatasetMappingPolicy(eligibilityPolicy);

        policy.evaluationLabel(FraudFeedbackLabel.CONFIRMED_FRAUD);

        verify(eligibilityPolicy).eligibilityFor(FraudFeedbackLabel.CONFIRMED_FRAUD);
    }
}
