package com.frauddetection.alert.feedback;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FraudFeedbackMapper {

    public FraudFeedbackResponse toResponse(FraudFeedbackRecord record) {
        return new FraudFeedbackResponse(
                record.getFeedbackId(),
                record.getTransactionId(),
                record.getCustomerId(),
                record.getCorrelationId(),
                record.getAnalystDecision(),
                record.getFeedbackLabel(),
                record.getLabelSource(),
                record.getFeedbackStatus(),
                record.getCreatedAt(),
                record.getCreatedBy(),
                immutable(record.getDecisionReasonCodes()),
                record.getNotes() != null && !record.getNotes().isBlank(),
                record.getFraudScore(),
                record.getRiskLevel(),
                record.getAlertRecommended(),
                record.getScoredAt(),
                record.getTransactionTimestamp(),
                record.getEngineIntelligenceStatus(),
                record.getAgreementStatus(),
                record.getRiskMismatchStatus(),
                record.getScoreDeltaBucket(),
                record.getAnalystRecommendationStatus(),
                record.getAnalystRecommendation(),
                record.getAnalystRecommendationVersion(),
                record.getAnalystRecommendationGeneratedAt(),
                immutable(record.getAnalystRecommendationReasonCodes())
        );
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
