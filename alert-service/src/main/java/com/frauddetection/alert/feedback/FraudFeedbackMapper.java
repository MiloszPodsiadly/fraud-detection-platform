package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FraudFeedbackMapper {

    public FraudFeedbackResponse toResponse(FraudFeedbackRecord record) {
        EngineIntelligenceComparisonSnapshot comparison = comparisonSnapshot(record);
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
                safeEngineIntelligenceStatus(record, comparison),
                comparison.comparisonType(),
                comparison.comparedEngineIds(),
                comparison.agreementStatus(),
                comparison.riskMismatchStatus(),
                comparison.scoreDeltaBucket(),
                record.getAnalystRecommendationStatus(),
                record.getAnalystRecommendation(),
                record.getAnalystRecommendationVersion(),
                record.getAnalystRecommendationGeneratedAt(),
                immutable(record.getAnalystRecommendationReasonCodes())
        );
    }

    private static EngineIntelligenceComparisonSnapshot comparisonSnapshot(FraudFeedbackRecord record) {
        if (!hasComparisonSnapshot(record)) {
            return EngineIntelligenceComparisonSnapshot.absent();
        }
        try {
            EngineIntelligenceComparison comparison = new EngineIntelligenceComparison(
                    record.getComparisonType(),
                    record.getComparedEngineIds(),
                    record.getAgreementStatus(),
                    record.getRiskMismatchStatus(),
                    record.getScoreDeltaBucket()
            );
            return new EngineIntelligenceComparisonSnapshot(
                    comparison.comparisonType(),
                    comparison.comparedEngineIds(),
                    comparison.agreementStatus(),
                    comparison.riskMismatchStatus(),
                    comparison.scoreDeltaBucket(),
                    false
            );
        } catch (RuntimeException exception) {
            return EngineIntelligenceComparisonSnapshot.corruptSnapshot();
        }
    }

    private static EngineIntelligenceResponseStatus safeEngineIntelligenceStatus(
            FraudFeedbackRecord record,
            EngineIntelligenceComparisonSnapshot comparison
    ) {
        if (comparison.corruptionDetected()
                && (record.getEngineIntelligenceStatus() == EngineIntelligenceResponseStatus.AVAILABLE
                || record.getEngineIntelligenceStatus() == EngineIntelligenceResponseStatus.DEGRADED)) {
            return EngineIntelligenceResponseStatus.UNAVAILABLE;
        }
        return record.getEngineIntelligenceStatus();
    }

    private static boolean hasComparisonSnapshot(FraudFeedbackRecord record) {
        return record.getComparisonType() != null
                || record.getComparedEngineIds() != null && !record.getComparedEngineIds().isEmpty()
                || record.getAgreementStatus() != null
                || record.getRiskMismatchStatus() != null
                || record.getScoreDeltaBucket() != null;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record EngineIntelligenceComparisonSnapshot(
            EngineIntelligenceComparisonType comparisonType,
            List<String> comparedEngineIds,
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket,
            boolean corruptionDetected
    ) {
        private static EngineIntelligenceComparisonSnapshot absent() {
            return new EngineIntelligenceComparisonSnapshot(null, List.of(), null, null, null, false);
        }

        private static EngineIntelligenceComparisonSnapshot corruptSnapshot() {
            return new EngineIntelligenceComparisonSnapshot(null, List.of(), null, null, null, true);
        }

        private EngineIntelligenceComparisonSnapshot {
            comparedEngineIds = immutable(comparedEngineIds);
        }
    }
}
