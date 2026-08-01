package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.recommendation.AnalystRecommendation;
import com.frauddetection.common.events.recommendation.AnalystRecommendationStatus;

import java.time.Instant;
import java.util.List;

public record FraudFeedbackResponse(
        String feedbackId,
        String transactionId,
        String customerId,
        String correlationId,
        AnalystDecision analystDecision,
        FraudFeedbackLabel feedbackLabel,
        FeedbackLabelSource labelSource,
        FraudFeedbackStatus feedbackStatus,
        Instant createdAt,
        String createdBy,
        List<String> decisionReasonCodes,
        Boolean notesPresent,
        Double fraudScore,
        RiskLevel riskLevel,
        Boolean alertRecommended,
        Instant scoredAt,
        Instant transactionTimestamp,
        EngineIntelligenceResponseStatus engineIntelligenceStatus,
        EngineIntelligenceComparisonType comparisonType,
        List<String> comparedEngineIds,
        EngineIntelligenceAgreementStatus agreementStatus,
        EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
        EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
        AnalystRecommendationStatus analystRecommendationStatus,
        AnalystRecommendation analystRecommendation,
        String analystRecommendationVersion,
        Instant analystRecommendationGeneratedAt,
        List<String> analystRecommendationReasonCodes
) {
    public FraudFeedbackResponse {
        decisionReasonCodes = immutable(decisionReasonCodes);
        analystRecommendationReasonCodes = immutable(analystRecommendationReasonCodes);
        if (hasEngineIntelligenceComparisonSnapshot(
                comparisonType,
                comparedEngineIds,
                agreementStatus,
                riskMismatchStatus,
                scoreDeltaBucket
        )) {
            EngineIntelligenceComparison comparison = new EngineIntelligenceComparison(
                    comparisonType,
                    comparedEngineIds,
                    agreementStatus,
                    riskMismatchStatus,
                    scoreDeltaBucket
            );
            comparisonType = comparison.comparisonType();
            comparedEngineIds = comparison.comparedEngineIds();
        } else {
            comparisonType = null;
            comparedEngineIds = List.of();
            agreementStatus = null;
            riskMismatchStatus = null;
            scoreDeltaBucket = null;
        }
    }

    private static boolean hasEngineIntelligenceComparisonSnapshot(
            EngineIntelligenceComparisonType comparisonType,
            List<String> comparedEngineIds,
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
    ) {
        return comparisonType != null
                || comparedEngineIds != null && !comparedEngineIds.isEmpty()
                || agreementStatus != null
                || riskMismatchStatus != null
                || scoreDeltaBucket != null;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
