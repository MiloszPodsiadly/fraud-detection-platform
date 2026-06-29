package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.alert.feedback.governance.FeedbackDatasetEligibility;
import com.frauddetection.alert.feedback.governance.FeedbackDatasetEligibilityPolicy;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
public class FeedbackDatasetMappingPolicy {

    private final FeedbackDatasetEligibilityPolicy eligibilityPolicy;

    public FeedbackDatasetMappingPolicy(FeedbackDatasetEligibilityPolicy eligibilityPolicy) {
        this.eligibilityPolicy = Objects.requireNonNull(eligibilityPolicy, "eligibilityPolicy is required");
    }

    Optional<FeedbackEvaluationLabel> evaluationLabel(FraudFeedbackLabel label) {
        if (eligibilityPolicy.eligibilityFor(label) != FeedbackDatasetEligibility.EVALUATION_CANDIDATE) {
            return Optional.empty();
        }
        return switch (label) {
            case CONFIRMED_FRAUD -> Optional.of(FeedbackEvaluationLabel.POSITIVE_FRAUD);
            case CONFIRMED_LEGITIMATE -> Optional.of(FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE);
            default -> Optional.empty();
        };
    }

    FeedbackDatasetEligibility eligibilityFor(FraudFeedbackLabel label) {
        return eligibilityPolicy.eligibilityFor(label);
    }
}
