package com.frauddetection.alert.feedback.governance;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;

public class FeedbackDatasetEligibilityPolicy {

    public FeedbackDatasetEligibility eligibilityFor(FraudFeedbackLabel label) {
        if (label == null) {
            return FeedbackDatasetEligibility.REQUIRES_GOVERNANCE_REVIEW;
        }
        return switch (label) {
            case CONFIRMED_FRAUD, CONFIRMED_LEGITIMATE -> FeedbackDatasetEligibility.EVALUATION_CANDIDATE;
            case INCONCLUSIVE, NEEDS_MORE_INFO -> FeedbackDatasetEligibility.UNRESOLVED_EXCLUDED;
        };
    }

    public boolean eligibleForBinaryEvaluation(FraudFeedbackLabel label) {
        return eligibilityFor(label) == FeedbackDatasetEligibility.EVALUATION_CANDIDATE;
    }

    public boolean eligibleForTrainingExport(FraudFeedbackLabel label) {
        return false;
    }

    public boolean eligibleDatasetField(String fieldName) {
        return fieldName != null
                && !fieldName.isBlank()
                && !"notes".equalsIgnoreCase(fieldName.trim());
    }
}
