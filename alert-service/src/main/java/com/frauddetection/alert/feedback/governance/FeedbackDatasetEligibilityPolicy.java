package com.frauddetection.alert.feedback.governance;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;

import java.util.Set;

public class FeedbackDatasetEligibilityPolicy {

    private static final Set<String> ELIGIBLE_DATASET_FIELDS = Set.of(
            "feedbackId",
            "transactionId",
            "feedbackLabel",
            "labelSource",
            "feedbackStatus",
            "createdAt",
            "decisionReasonCodes",
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
            "analystRecommendationReasonCodes"
    );

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
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        return ELIGIBLE_DATASET_FIELDS.contains(fieldName.trim());
    }
}
