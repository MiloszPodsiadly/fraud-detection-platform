package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.common.events.engine.FraudEngineScorePolicy;
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
        FeedbackDatasetRulesEvidenceStatus rulesEvidenceStatus,
        RiskLevel rulesRiskLevel,
        FeedbackDatasetMlPredictionEvidenceStatus mlPredictionEvidenceStatus,
        Double mlPredictionScore,
        RiskLevel mlPredictionRiskLevel,
        Instant mlPredictionExecutedAt,
        String mlModelName,
        String mlModelVersion,
        String mlFeatureContractVersion,
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
        if (!FeedbackDatasetBuilder.DATASET_VERSION.equals(datasetVersion)) {
            throw new IllegalArgumentException("datasetVersion is unsupported");
        }
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
        mlModelName = FeedbackDatasetSafety.optionalModelIdentityPart(mlModelName, "mlModelName");
        mlModelVersion = FeedbackDatasetSafety.optionalModelIdentityPart(mlModelVersion, "mlModelVersion");
        mlFeatureContractVersion = FeedbackDatasetSafety.optionalModelIdentityPart(
                mlFeatureContractVersion,
                "mlFeatureContractVersion"
        );
        fraudScore = FraudEngineScorePolicy.validateOptional(fraudScore, "fraudScore");
        mlPredictionScore = FraudEngineScorePolicy.validateOptional(mlPredictionScore, "mlPredictionScore");
        FeedbackDatasetSafety.validateMlModelIdentity(
                mlModelName,
                mlModelVersion,
                mlFeatureContractVersion
        );
        validateMlPredictionEvidence(
                mlPredictionEvidenceStatus,
                mlPredictionScore,
                mlPredictionRiskLevel,
                mlPredictionExecutedAt,
                mlModelName,
                mlModelVersion,
                mlFeatureContractVersion
        );
        validateRulesEvidence(rulesEvidenceStatus, rulesRiskLevel);
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

    private static void validateRulesEvidence(
            FeedbackDatasetRulesEvidenceStatus status,
            RiskLevel riskLevel
    ) {
        Objects.requireNonNull(status, "rulesEvidenceStatus is required");
        if ((status == FeedbackDatasetRulesEvidenceStatus.AVAILABLE) != (riskLevel != null)) {
            throw new IllegalArgumentException("Rules evidence availability must match rulesRiskLevel");
        }
    }

    private static void validateMlPredictionEvidence(
            FeedbackDatasetMlPredictionEvidenceStatus status,
            Double score,
            RiskLevel riskLevel,
            Instant executedAt,
            String modelName,
            String modelVersion,
            String featureContractVersion
    ) {
        Objects.requireNonNull(status, "mlPredictionEvidenceStatus is required");
        boolean directEvidenceComplete = score != null && riskLevel != null && executedAt != null;
        boolean modelIdentityComplete = modelName != null && modelVersion != null && featureContractVersion != null;
        if (status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE) {
            if (!directEvidenceComplete || !modelIdentityComplete) {
                throw new IllegalArgumentException("available ML prediction evidence must be complete");
            }
            return;
        }
        if (status != FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT) {
            throw new IllegalArgumentException("invalid ML prediction evidence cannot enter the dataset");
        }
        if (score != null || riskLevel != null || executedAt != null || modelIdentityComplete) {
            throw new IllegalArgumentException("absent ML prediction evidence must not carry prediction values");
        }
    }
}
