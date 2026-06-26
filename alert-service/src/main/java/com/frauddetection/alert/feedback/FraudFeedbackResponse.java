package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
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
        EngineIntelligenceAgreementStatus agreementStatus,
        EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
        EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
        AnalystRecommendationStatus analystRecommendationStatus,
        AnalystRecommendation analystRecommendation,
        String analystRecommendationVersion,
        Instant analystRecommendationGeneratedAt,
        List<String> analystRecommendationReasonCodes
) {
}
