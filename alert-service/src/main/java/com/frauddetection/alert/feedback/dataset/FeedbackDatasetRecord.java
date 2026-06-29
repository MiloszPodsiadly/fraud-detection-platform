package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.recommendation.AnalystRecommendation;
import com.frauddetection.common.events.recommendation.AnalystRecommendationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FeedbackDatasetRecord(
        String datasetVersion,
        String evaluationRecordId,
        String transactionReference,
        FraudFeedbackLabel feedbackLabel,
        FeedbackEvaluationLabel evaluationLabel,
        List<String> decisionReasonCodes,
        Instant feedbackCreatedAt,
        Double fraudScore,
        RiskLevel riskLevel,
        Boolean alertRecommended,
        EngineIntelligenceResponseStatus engineIntelligenceStatus,
        EngineIntelligenceAgreementStatus agreementStatus,
        EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
        EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
        AnalystRecommendationStatus analystRecommendationStatus,
        AnalystRecommendation analystRecommendation,
        String analystRecommendationVersion,
        Instant analystRecommendationGeneratedAt,
        List<String> analystRecommendationReasonCodes,
        Instant scoredAt,
        Instant transactionTimestamp
) {

    public FeedbackDatasetRecord {
        datasetVersion = requireText(datasetVersion, "datasetVersion");
        evaluationRecordId = FeedbackDatasetIdentifierHasher.requireEvaluationRecordId(evaluationRecordId);
        transactionReference = FeedbackDatasetIdentifierHasher.requireTransactionReference(transactionReference);
        feedbackLabel = Objects.requireNonNull(feedbackLabel, "feedbackLabel is required");
        evaluationLabel = Objects.requireNonNull(evaluationLabel, "evaluationLabel is required");
        validateLabelConsistency(feedbackLabel, evaluationLabel);
        feedbackCreatedAt = Objects.requireNonNull(feedbackCreatedAt, "feedbackCreatedAt is required");
        decisionReasonCodes = FeedbackDatasetReasonCodePolicy.validatedDecisionReasonCodes(
                feedbackLabel,
                decisionReasonCodes
        );
        analystRecommendationReasonCodes = FeedbackDatasetSafety.copyMachineCodes(
                analystRecommendationReasonCodes,
                "analystRecommendationReasonCodes",
                20
        );
        analystRecommendationVersion = FeedbackDatasetSafety.optionalSafeIdentifier(
                analystRecommendationVersion,
                "analystRecommendationVersion"
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static void validateLabelConsistency(
            FraudFeedbackLabel feedbackLabel,
            FeedbackEvaluationLabel evaluationLabel
    ) {
        boolean consistent = switch (feedbackLabel) {
            case CONFIRMED_FRAUD -> evaluationLabel == FeedbackEvaluationLabel.POSITIVE_FRAUD;
            case CONFIRMED_LEGITIMATE -> evaluationLabel == FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE;
            case INCONCLUSIVE, NEEDS_MORE_INFO -> false;
        };
        if (!consistent) {
            throw new IllegalArgumentException("feedbackLabel must match evaluationLabel");
        }
    }
}
