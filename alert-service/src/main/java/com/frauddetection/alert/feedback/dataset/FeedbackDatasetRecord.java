package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.common.events.engine.FraudEngineScorePolicy;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
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
        FeedbackDatasetMlPredictionEvidenceResolutionProvenance mlPredictionEvidenceResolutionProvenance,
        MlPredictionEvidenceOmissionReason mlPredictionEvidenceOmissionReason,
        Double mlPredictionScore,
        RiskLevel mlPredictionRiskLevel,
        Instant mlPredictionExecutedAt,
        String mlModelName,
        String mlModelVersion,
        String mlFeatureContractVersion,
        String mlModelArtifactSha256,
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
        mlModelArtifactSha256 = FeedbackDatasetSafety.optionalModelArtifactSha256(
                mlModelArtifactSha256,
                "mlModelArtifactSha256"
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
                mlPredictionEvidenceResolutionProvenance,
                mlPredictionEvidenceOmissionReason,
                mlPredictionScore,
                mlPredictionRiskLevel,
                mlPredictionExecutedAt,
                mlModelName,
                mlModelVersion,
                mlFeatureContractVersion,
                mlModelArtifactSha256
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
            FeedbackDatasetMlPredictionEvidenceResolutionProvenance resolutionProvenance,
            MlPredictionEvidenceOmissionReason omissionReason,
            Double score,
            RiskLevel riskLevel,
            Instant executedAt,
            String modelName,
            String modelVersion,
            String featureContractVersion,
            String modelArtifactSha256
    ) {
        Objects.requireNonNull(status, "mlPredictionEvidenceStatus is required");
        if ((status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE)
                != (resolutionProvenance != null)) {
            throw new IllegalArgumentException(
                    "ML prediction evidence availability must match resolution provenance"
            );
        }
        boolean directEvidenceComplete = score != null && riskLevel != null && executedAt != null;
        boolean modelIdentityComplete = modelName != null && modelVersion != null && featureContractVersion != null;
        if (status == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE) {
            if (!directEvidenceComplete || !modelIdentityComplete || omissionReason != null
                    || modelArtifactSha256 == null) {
                throw new IllegalArgumentException("exact-artifact ML prediction evidence must be complete");
            }
            return;
        }
        if (score != null || riskLevel != null || executedAt != null || modelIdentityComplete
                || modelArtifactSha256 != null) {
            throw new IllegalArgumentException("unavailable ML prediction evidence must not carry prediction values");
        }
        if (status == FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT
                && omissionReason == null) {
            throw new IllegalArgumentException("legitimate absence requires authoritative omission proof");
        }
        if (omissionReason != null
                && status != FeedbackDatasetMlPredictionEvidenceStatus.fromAuthoritativeOmission(omissionReason)) {
            throw new IllegalArgumentException("ML prediction omission reason contradicts evidence status");
        }
    }
}
